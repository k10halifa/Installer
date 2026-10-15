package io.i10nstaller.model

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.Intent_rename
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller_rename
import android.content.pm.PackageManager
import android.content.pm.PackageManager_rename
import android.content.pm.VersionedPackage
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import io.i10nstaller.R
import io.i10nstaller.model.Hook.wrap
import io.i10nstaller.model.InstallAborted.Companion.ABORT_CLOSE
import io.i10nstaller.model.InstallAborted.Companion.ABORT_CREATE
import io.i10nstaller.model.InstallAborted.Companion.ABORT_INFO
import io.i10nstaller.model.InstallAborted.Companion.ABORT_NOTFOUND
import io.i10nstaller.model.InstallAborted.Companion.ABORT_PARSE
import io.i10nstaller.model.InstallAborted.Companion.ABORT_SHIZUKU
import io.i10nstaller.model.InstallAborted.Companion.ABORT_SPLIT
import io.i10nstaller.model.InstallAborted.Companion.ABORT_WRITE
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File
import java.io.IOException

class InstallRepository(private val context: Application) {
    private val TAG = InstallRepository::class.java.simpleName
    val installResult = MutableLiveData<InstallStage>()
    val stagingProgress = MutableLiveData<Int>()
    private lateinit var packageManager: PackageManager
    private lateinit var packageInstaller: PackageInstaller
    private var stagedSessionId = SessionInfo.INVALID_ID
    private var callingUid = Process.INVALID_UID
    private lateinit var intent: Intent
    private var apkLite: ApkLite? = null
    private var sourceUri: Uri? = null
    private var sourcePath: String? = null
    private var grantPermissionsAfterInstall = false
    private var deleteSourceAfterInstall = false

    fun preCheck(intent: Intent): Boolean {
        if (!Shizuku.pingBinder()
            || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            || Shizuku.checkRemotePermission(Manifest.permission.INSTALL_PACKAGES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            installResult.value = InstallAborted(
                ABORT_SHIZUKU,
                context.packageManager.getLaunchIntentForPackage(
                    ShizukuProvider.MANAGER_APPLICATION_ID
                )
            )
            return false
        }

        this.intent = intent
        sourceUri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
        sourcePath = null
        Log.v(TAG, "Intent: $intent")

        Hook.init(context)
        packageManager = Hook.pm
        packageInstaller = Hook.installer
        Hook.disableAdbVerify(context)

        installResult.value = InstallParse()
        return true
    }

    fun parseUri() {
        var uri = intent.data
        if (uri == null) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            intent.setData(uri)
        }
        if (uri != null && "package" == uri.scheme) {
            val packageName = uri.schemeSpecificPart
            installResult.postValue(processPackageUri(packageName))
            return
        }
        if (uri != null && "market" == uri.scheme && uri.authority == "details") {
            uri.getQueryParameter("id")?.let {
                installResult.postValue(processPackageUri(it))
                return
            }
        }
        if (uri != null && (ContentResolver.SCHEME_CONTENT == uri.scheme ||
            ContentResolver.SCHEME_FILE == uri.scheme)
        ) {
            installResult.postValue(processContentUri(uri))
            return
        }
        if (uri == null) {
            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.let {
                installResult.postValue(processPackageUri(it))
                return
            }
        }
        installResult.postValue(InstallAborted(ABORT_INFO))
    }

    fun install(
        setInstaller: Boolean,
        grantPermissions: Boolean,
        deleteSource: Boolean,
        commit: Boolean,
        full: Boolean,
        removeSplit: Boolean
    ) {
        val uri = intent.data
        if (sourceUri != uri) sourcePath = null
        sourceUri = uri
        grantPermissionsAfterInstall = grantPermissions
        deleteSourceAfterInstall = deleteSource
        installResult.postValue(InstallInstalling(apkLite!!))
        if (ContentResolver.SCHEME_CONTENT != uri?.scheme &&
            ContentResolver.SCHEME_FILE != uri?.scheme
        ) {
            installPackageUri()
            return
        }

        if (stagedSessionId == SessionInfo.INVALID_ID) {
            try {
                val params: SessionParams = createSessionParams(setInstaller, full)
                stagedSessionId = packageInstaller.createSession(params)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create a staging session", e)
                installResult.postValue(InstallAborted(ABORT_CREATE))
                return
            }
        }

        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.wrap()
            if (removeSplit) {
                session.removeSplit(apkLite!!.splitName!!)
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    captureSourcePath(afd.parcelFileDescriptor.fd)
                    if (apkLite!!.zip) {
                        PackageUtil.stageZip(session, afd) {
                            stagingProgress.postValue(it)
                        }
                    } else {
                        PackageUtil.stageApk(session, afd) {
                            stagingProgress.postValue(it)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            cleanupInstall()
            Log.e(TAG, "Could not stage APK.", e)
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (commit) {
            stagingProgress.postValue(101)
            commit()
        } else {
            installResult.postValue(InstallAborted(ABORT_CLOSE))
        }
    }

    private fun processContentUri(uri: Uri): InstallStage {
        Log.v(TAG, "content URI: $uri")
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            uri.authority?.let { authority ->
                packageManager.resolveContentProvider(authority, 0)?.also { info ->
                    callingUid = info.applicationInfo.uid
                }
            }
        }
        val apk = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                captureSourcePath(afd.parcelFileDescriptor.fd)
                PackageUtil.parseZipFromFd(afd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK from content URI: $uri", e)
            null
        }
        if (apk == null) {
            return InstallAborted(ABORT_PARSE)
        }
        val old = try {
            packageManager.getPackageInfo(apk.packageName, PackageManager_rename.MATCH_KNOWN_PACKAGES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        var full = true
        if (apk.isSplit()) {
            for (item in packageInstaller.allSessions) {
                if (item.isActive && item.appPackageName == apk.packageName) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // noinspection NewApi
                        if (item.installerUid != Shizuku.getUid()) {
                            continue
                        }
                    } else {
                        if (item.installReason != PackageManager.INSTALL_REASON_USER) continue
                        item as PackageInstaller_rename.SessionInfo
                        if (item.installFlags and PackageManager_rename.INSTALL_FROM_ADB == 0) {
                            continue
                        }
                    }
                    val info = packageInstaller.getSessionInfo(item.sessionId)!!
                    stagedSessionId = info.sessionId
                    full = info.mode == SessionParams.MODE_FULL_INSTALL
                    apk.label = info.appLabel as String?
                    apk.icon = info.appIcon?.toDrawable(context.resources)
                    break
                }
            }
            if (stagedSessionId == SessionInfo.INVALID_ID) {
                if (old != null && old.longVersionCode == apk.versionCode) {
                    full = false
                } else {
                    return InstallAborted(ABORT_SPLIT)
                }
            }
        }
        if (old != null) {
            if (apk.label == null) {
                apk.label = old.applicationInfo!!.loadLabel(packageManager).toString()
            }
            if (apk.icon == null) {
                apk.icon = old.applicationInfo!!.loadIcon(packageManager)
            }
            // use this field to store the installer package name
            old.sharedUserId = packageManager.getInstallerPackageName(apk.packageName)
        }
        if (apk.label == null && ContentResolver.SCHEME_CONTENT == uri.scheme) {
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        apk.label = cursor.getString(displayNameIndex)
                    }
                }
            }
        } else if (apk.label == null && ContentResolver.SCHEME_FILE == uri.scheme) {
            apk.label = uri.path?.let { File(it).name }
        }
        apkLite = apk
        val skipCreate = stagedSessionId != SessionInfo.INVALID_ID
        return InstallUserAction(apk, old, full, skipCreate)
    }

    private fun processPackageUri(packageName: String): InstallStage {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager_rename.MATCH_KNOWN_PACKAGES)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Requested package not available.", e)
            return InstallAborted(ABORT_NOTFOUND)
        }

        val apk = PackageUtil.getApkLite(packageManager, info!!)
        apkLite = apk

        val path = info.applicationInfo!!.sourceDir
        if (path == null || !File(path).exists()) {
            queryZipUri(info)?.let {
                intent.setData(it)
                return processContentUri(it)
            }
        } else if (info.applicationInfo!!.flags and ApplicationInfo.FLAG_INSTALLED != 0) {
            return PackageUserAction(apk, info)
        }
        return InstallUserAction(apk, info)
    }

    private fun queryZipUri(info: PackageInfo): Uri? {
        val name = "${info.packageName}-${info.longVersionCode}.zip"
        val name2 = "${info.packageName}-${info.longVersionCode} (%).zip"
        val dir = Environment.DIRECTORY_DOCUMENTS + File.separator +
            context.getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cr = context.contentResolver
            val tableUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(name, name2)
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            cr.query(tableUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(index)
                    return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
                }
            }
        } else {
            val file = File(context.getExternalFilesDir(dir), name)
            if (file.exists()) {
                return file.toUri()
            }
        }
        return null
    }

    private fun installPackageUri() {
        stagingProgress.postValue(101)
        try {
            val pm = packageManager as PackageManager_rename
            pm.installExistingPackage(apkLite!!.packageName, PackageManager.INSTALL_REASON_USER)
            setStageBasedOnResult(
                PackageInstaller.STATUS_SUCCESS,
                PackageManager_rename.INSTALL_SUCCEEDED,
                null
            )
        } catch (e: PackageManager.NameNotFoundException) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun createSessionParams(setInstaller: Boolean, full: Boolean): SessionParams {
        val mode = if (full) {
            SessionParams.MODE_FULL_INSTALL
        } else {
            SessionParams.MODE_INHERIT_EXISTING
        }
        val params = SessionParams(mode)
        var installer = context.packageName
        if (setInstaller) {
            try {
                packageManager.getPackageInfo("com.android.vending", PackageManager.MATCH_SYSTEM_ONLY)
                installer = "com.android.vending"
            } catch (_: PackageManager.NameNotFoundException) {
                installer = "com.android.shell"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        } else {
            val referrerUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_REFERRER)
            params.setReferrerUri(referrerUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val source = if (referrerUri != null) PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                params.setPackageSource(source)
            }
            params.setOriginatingUri(intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI))
            params.setOriginatingUid(intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, callingUid))
        }
        // noinspection NewApi
        params.setInstallerPackageName(installer)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setAppPackageName(apkLite!!.packageName)
        if (apkLite!!.needSplit()) {
            params.setAppIcon(apkLite!!.icon?.toBitmap())
            params.setAppLabel(apkLite!!.label)
        }

        val p = params as PackageInstaller_rename.SessionParams
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_ALLOW_TEST
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REPLACE_EXISTING
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REQUEST_DOWNGRADE
        p.installFlags = p.installFlags or PackageManager_rename.INSTALL_FULL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            p.installFlags = p.installFlags or PackageManager_rename.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK
            p.installFlags = p.installFlags or PackageManager_rename.INSTALL_REQUEST_UPDATE_OWNERSHIP
        }
        return params
    }

    private fun commit() {
        val receiver = LocalIntentReceiver(::setStageBasedOnResult)
        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.wrap()
            // noinspection RequestInstallPackagesPolicy
            session.commit(receiver.intentSender as IntentSender)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit staged session", e)
            cleanupInstall()
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                PackageManager_rename.INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        }
    }

    private fun setStageBasedOnResult(statusCode: Int, legacyStatus: Int, message: String?) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            val packageName = apkLite!!.packageName
            val installedSource = sourceUri
            Thread {
                if (grantPermissionsAfterInstall) grantPostInstallAccess(packageName)
                if (deleteSourceAfterInstall) deleteSourceApk(installedSource)

                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                packageManager.getPackageInfo(packageName, 0)?.let { info ->
                    apkLite!!.icon = info.applicationInfo!!.loadIcon(packageManager)
                    apkLite!!.label = info.applicationInfo!!.loadLabel(packageManager).toString()
                }
                installResult.postValue(InstallSuccess(apkLite!!, launchIntent))
            }.start()
        } else {
            installResult.postValue(InstallFailed(apkLite!!, legacyStatus, statusCode, message))
        }
    }

    private fun grantPostInstallAccess(packageName: String) {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return

        val uid = try {
            packageManager.getApplicationInfo(packageName, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not resolve UID for $packageName", e)
            return
        }
        val packageArg = shellQuote(packageName)
        val commands = """
            appops set $packageArg REQUEST_INSTALL_PACKAGES allow || true
            appops set $packageArg SYSTEM_ALERT_WINDOW allow || true
            appops set $packageArg WRITE_SETTINGS allow || true
            appops set $packageArg MANAGE_EXTERNAL_STORAGE allow || true
            dumpsys deviceidle whitelist +$packageArg || true
            am set-standby-bucket $packageArg active || true
            cmd activity set-inactive $packageArg false || true
            cmd appops set $packageArg 40 allow || true
            cmd appops set $packageArg 63 allow || true
            cmd appops set $packageArg 70 allow || true
            cmd appops set $packageArg 114 allow || true
            cmd appops set $packageArg 115 allow || true
            cmd appops set $packageArg 119 allow || true
            cmd appops set $packageArg 128 allow || true
            cmd netpolicy remove restrict-background-blacklist $uid 2>/dev/null || true
            cmd netpolicy add restrict-background-whitelist $uid 2>/dev/null || true
        """.trimIndent()
        runShizukuCommand(commands, "post-install permissions for $packageName")
    }

    private fun deleteSourceApk(uri: Uri?) {
        if (uri == null) return

        val path = sourcePath ?: resolveSourcePath(uri)
        if (!path.isNullOrBlank() && runShizukuCommand(
                "rm -f -- ${shellQuote(path)}",
                "delete source APK"
            )
        ) {
            return
        }

        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            if (!path.isNullOrBlank() && File(path).delete()) return
        } else if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                if (DocumentsContract.isDocumentUri(context, uri) &&
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                ) {
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete source document $uri", e)
            }

            try {
                if (context.contentResolver.delete(uri, null, null) > 0) return
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete source URI $uri", e)
            }
        }

        Log.w(TAG, "Could not delete source APK: $uri (resolved path: $path)")
    }

    private fun resolveSourcePath(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path?.let(::canonicalPath)
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null

        uri.path?.let(::canonicalPath)?.let { return it }
        uri.pathSegments.firstOrNull { it.startsWith("raw:") }
            ?.removePrefix("raw:")
            ?.let(::canonicalPath)
            ?.let { return it }

        val authority = uri.authority.orEmpty()
        val documentId = try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read document ID for $uri", e)
            null
        }
        documentId?.let { id ->
            val rawPath = when {
                id.startsWith("raw:") -> id.removePrefix("raw:")
                id.startsWith("/") -> id
                else -> null
            }
            rawPath?.let(::canonicalPath)?.let { return it }
        }

        if (authority == "com.android.externalstorage.documents" && documentId != null) {
            resolveExternalStorageDocument(documentId)?.let { return it }
        }
        if (authority == "com.android.providers.downloads.documents" && documentId != null) {
            resolveDownloadsDocument(documentId, uri)?.let { return it }
        }
        if (authority == "com.android.providers.media.documents" && documentId != null) {
            resolveMediaDocument(documentId)?.let { return it }
        }

        resolveProviderPath(uri)?.let { return it }
        return null
    }

    private fun resolveExternalStorageDocument(documentId: String): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0 || separator == documentId.lastIndex) return null

        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volume)
        }
        return resolveUnderRoot(root, relativePath)
    }

    private fun resolveDownloadsDocument(documentId: String, originalUri: Uri): String? {
        if (documentId.startsWith("raw:")) {
            val rawPath = documentId.removePrefix("raw:")
            return canonicalPath(rawPath)
        }

        val numericId = documentId.removePrefix("msf:").removePrefix("msd:").toLongOrNull()
        if (numericId != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolveProviderPath(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL, numericId)
                )?.let { return it }
            }
            val publicDownloads = Uri.parse("content://downloads/public_downloads")
            resolveProviderPath(ContentUris.withAppendedId(publicDownloads, numericId))?.let {
                return it
            }
        }
        return resolveProviderPath(originalUri)
    }

    private fun resolveMediaDocument(documentId: String): String? {
        val separator = documentId.indexOf(':')
        if (separator <= 0 || separator == documentId.lastIndex) return null
        val mediaType = documentId.substring(0, separator)
        val id = documentId.substring(separator + 1).toLongOrNull() ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val volume = MediaStore.VOLUME_EXTERNAL
        val mediaUri = when (mediaType) {
            "document" -> MediaStore.Files.getContentUri(volume, id)
            "downloads" -> MediaStore.Downloads.getContentUri(volume, id)
            else -> null
        } ?: return null
        return resolveProviderPath(mediaUri)
    }

    private fun resolveProviderPath(uri: Uri): String? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        var values = querySourceMetadata(uri, projection)
        if (values?.first.isNullOrBlank() &&
            (values?.second.isNullOrBlank() || values?.third.isNullOrBlank())
        ) {
            values = querySourceMetadata(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
            ) ?: values
        }

        values?.first?.takeIf { it.startsWith('/') }?.let { return canonicalPath(it) }
        val relativePath = values?.second?.takeIf { it.isNotBlank() } ?: return null
        val displayName = values.third?.takeIf { it.isNotBlank() } ?: return null
        val root = mediaVolumeRoot(uri) ?: return null
        return resolveUnderRoot(root, relativePath, displayName)
    }

    private fun querySourceMetadata(
        uri: Uri,
        projection: Array<String>
    ): Triple<String?, String?, String?>? {
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relativeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                Triple(
                    if (dataIndex >= 0) cursor.getString(dataIndex) else null,
                    if (relativeIndex >= 0) cursor.getString(relativeIndex) else null,
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query source metadata for $uri", e)
            null
        }
    }

    private fun mediaVolumeRoot(uri: Uri): File? {
        val volume = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getVolumeName(uri)
            else MediaStore.VOLUME_EXTERNAL
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve MediaStore volume for $uri", e)
            return null
        }
        return when (volume) {
            MediaStore.VOLUME_EXTERNAL,
            MediaStore.VOLUME_EXTERNAL_PRIMARY -> Environment.getExternalStorageDirectory()
            else -> File("/storage", volume)
        }
    }

    private fun resolveUnderRoot(root: File, vararg relativeParts: String): String? {
        return try {
            val canonicalRoot = root.canonicalFile
            val candidate = relativeParts.fold(canonicalRoot) { parent, part ->
                File(parent, part)
            }.canonicalFile
            val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
            candidate.path.takeIf { it.startsWith(rootPrefix) }
        } catch (e: IOException) {
            Log.w(TAG, "Could not canonicalize source path under $root", e)
            null
        }
    }

    private fun canonicalPath(path: String): String? {
        if (!path.startsWith('/')) return null
        return try {
            File(path).canonicalPath.takeIf(::isSharedStoragePath)
        } catch (e: IOException) {
            Log.w(TAG, "Could not canonicalize source path $path", e)
            null
        }
    }

    private fun isSharedStoragePath(path: String): Boolean =
        path.startsWith("/storage/") ||
            path.startsWith("/data/media/") ||
            path.startsWith("/mnt/media_rw/") ||
            path.startsWith("/mnt/user/") ||
            path.startsWith("/mnt/runtime/") ||
            path.startsWith("/mnt/pass_through/") ||
            path.startsWith("/sdcard/")

    private fun captureSourcePath(fd: Int) {
        val descriptor = "/proc/self/fd/$fd"
        val path = try {
            Os.readlink(descriptor)
        } catch (e: Exception) {
            try {
                File(descriptor).canonicalPath
            } catch (canonicalError: IOException) {
                Log.w(TAG, "Could not resolve source descriptor $descriptor", canonicalError)
                null
            }
        }
        path?.let(::canonicalPath)?.also { sourcePath = it }
    }

    private fun runShizukuCommand(command: String, operation: String): Boolean {
        try {
            val factory = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            val process = factory.invoke(
                null,
                arrayOf("sh", "-c", "exec 2>&1\n$command"),
                null,
                null
            ) as java.lang.Process
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "Shizuku command failed for $operation (exit $exitCode): $output")
            }
            return exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Could not run Shizuku command for $operation", e)
            return false
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun cleanupInstall() {
        if (stagedSessionId > 0) {
            try {
                packageInstaller.abandonSession(stagedSessionId)
            } catch (_: SecurityException) {
            }
            stagedSessionId = 0
        }
    }

    fun archivePackage(info: PackageInfo, uninstall: Boolean) {
        installResult.postValue(InstallInstalling(apkLite!!))

        val name = "${info.packageName}-${info.longVersionCode}.zip"
        val dir = Environment.DIRECTORY_DOCUMENTS + File.separator +
            context.getString(R.string.app_name)
        var path = ""
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, true)
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            }
            val tableUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            cr.insert(tableUri, values)
        } else {
            val file = File(context.getExternalFilesDir(dir), name)
            path = file.absolutePath
            file.toUri()
        }
        if (uri == null) {
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        val list = ArrayList<File>()
        list.add(File(info.applicationInfo!!.sourceDir))
        info.applicationInfo!!.splitSourceDirs?.let {
            for (split in it) {
                list.add(File(split))
            }
        }

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "wt")?.use { afd ->
                PackageUtil.archivePackage(list, afd) {
                    stagingProgress.postValue(it)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to archive package", e)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                context.contentResolver.delete(uri, null, null)
            } else {
                File(uri.path!!).delete()
            }
            installResult.postValue(InstallAborted(ABORT_WRITE))
            return
        }

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cr = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, false)
            }
            cr.update(uri, values, null, null)
            cr.query(uri, null, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (index != -1) {
                        path = cursor.getString(index)
                    }
                }
            }
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/zip")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (uninstall) {
            val receiver = LocalIntentReceiver { statusCode, legacyCode, msg ->
                if (statusCode == PackageInstaller.STATUS_SUCCESS) {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, path))
                } else {
                    installResult.postValue(InstallSuccess(apkLite!!, intent, "$path\n\n$msg"))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    // noinspection MissingPermission
                    packageInstaller.requestArchive(
                        info.packageName,
                        receiver.intentSender as IntentSender
                    )
                    return
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Failed to archive for package ${info.packageName}", e)
                }
            }
            // noinspection MissingPermission NewApi
            packageInstaller.uninstall(
                VersionedPackage(info.packageName, info.longVersionCode),
                PackageManager_rename.DELETE_KEEP_DATA,
                receiver.intentSender as IntentSender
            )
            val userId = Os.getuid() / 100000
            val pm = packageManager as PackageManager_rename
            pm.deleteApplicationCacheFilesAsUser(info.packageName, userId, null)
        } else {
            installResult.postValue(InstallSuccess(apkLite!!, intent, path))
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
        packageManager.setApplicationEnabledSetting(packageName, newState, 0)
    }

}
