package io.i10nstaller.model

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.Intent_rename
import android.content.res.AssetFileDescriptor
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageInstaller_rename
import android.content.pm.PackageManager
import android.content.pm.PackageManager_rename
import android.content.pm.VersionedPackage
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.ParcelFileDescriptor
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
import io.i10nstaller.model.InstallAborted.Companion.ABORT_FAILURE
import io.i10nstaller.model.InstallAborted.Companion.ABORT_INFO
import io.i10nstaller.model.InstallAborted.Companion.ABORT_NOTFOUND
import io.i10nstaller.model.InstallAborted.Companion.ABORT_PARSE
import io.i10nstaller.model.InstallAborted.Companion.ABORT_SPLIT
import io.i10nstaller.model.InstallAborted.Companion.ABORT_WRITE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

private const val INTERACT_ACROSS_USERS = "android.permission.INTERACT_ACROSS_USERS"

class InstallRepository(private val context: Application) {
    private val TAG = InstallRepository::class.java.simpleName
    private val CLEANUP_TAG = "InstallCleanup"
    private val postInstallExceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.e("InstallerCrash", "Post-install coroutine failed", error)
    }
    private val postInstallScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + postInstallExceptionHandler
    )
    val installResult = MutableLiveData<InstallStage>()
    val stagingProgress = MutableLiveData<Int>()
    private lateinit var packageManager: PackageManager
    private lateinit var currentUserPackageManager: PackageManager
    private lateinit var packageInstaller: PackageInstaller
    private var stagedSessionId = SessionInfo.INVALID_ID
    private var callingUid = Process.INVALID_UID
    private lateinit var intent: Intent
    private var apkLite: ApkLite? = null
    private var sourceUri: Uri? = null
    private var sourcePath: String? = null
    private var stagedSourceFile: File? = null
    private var grantPermissionsAfterInstall = false
    private var deleteSourceAfterInstall = false
    private var useShizuku = false

    fun preCheck(intent: Intent): Boolean {
        this.intent = intent
        sourceUri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
        sourcePath = null
        Log.v(TAG, "Intent: $intent")

        return try {
            currentUserPackageManager =
                context.createPackageContext(context.packageName, 0).packageManager
            if (hasShizukuInstallPermission()) {
                Hook.init(context)
                packageManager = Hook.pm
                packageInstaller = Hook.installer
                useShizuku = true
                try {
                    Hook.disableAdbVerify(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disable ADB install verification", e)
                }
            } else {
                packageManager = context.packageManager
                packageInstaller = packageManager.packageInstaller
                useShizuku = false
            }
            installResult.value = InstallParse()
            true
        } catch (e: Exception) {
            installResult.value = failureStage(e)
            false
        } catch (e: LinkageError) {
            installResult.value = failureStage(e)
            false
        }
    }

    private fun hasShizukuInstallPermission(): Boolean {
        return hasShizukuPermission(Manifest.permission.INSTALL_PACKAGES)
    }

    private fun hasShizukuPermission(permission: String): Boolean {
        return try {
            Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                && (Shizuku.checkRemotePermission(permission)
                    == PackageManager.PERMISSION_GRANTED)
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify Shizuku permission $permission", e)
            false
        } catch (e: LinkageError) {
            Log.w(TAG, "Could not verify Shizuku permission $permission", e)
            false
        }
    }

    private fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        val includesAnyUser = flags and MATCH_ANY_USER != 0
        val currentUserFlags = flags and MATCH_ANY_USER.inv()
        if (includesAnyUser &&
            (!useShizuku || !hasShizukuPermission(INTERACT_ACROSS_USERS))
        ) {
            Log.i(TAG, "Querying $packageName for the current user; cross-user access is unavailable")
            return currentUserPackageManager.getPackageInfo(packageName, currentUserFlags)
        }

        return try {
            packageManager.getPackageInfo(packageName, flags)
        } catch (e: SecurityException) {
            if (!includesAnyUser && !useShizuku) throw e
            Log.w(
                TAG,
                "Package query for $packageName was denied; retrying for the current user",
                e
            )
            currentUserPackageManager.getPackageInfo(packageName, currentUserFlags)
        }
    }

    private fun getApplicationInfo(packageName: String, flags: Int): android.content.pm.ApplicationInfo {
        val includesAnyUser = flags and MATCH_ANY_USER != 0
        val currentUserFlags = flags and MATCH_ANY_USER.inv()
        if (includesAnyUser &&
            (!useShizuku || !hasShizukuPermission(INTERACT_ACROSS_USERS))
        ) {
            Log.i(TAG, "Querying $packageName for the current user; cross-user access is unavailable")
            return currentUserPackageManager.getApplicationInfo(packageName, currentUserFlags)
        }

        return try {
            packageManager.getApplicationInfo(packageName, flags)
        } catch (e: SecurityException) {
            if (!includesAnyUser && !useShizuku) throw e
            Log.w(
                TAG,
                "Application query for $packageName was denied; retrying for the current user",
                e
            )
            currentUserPackageManager.getApplicationInfo(packageName, currentUserFlags)
        }
    }

    private fun resolveContentProvider(authority: String): android.content.pm.ProviderInfo? {
        return try {
            packageManager.resolveContentProvider(authority, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not resolve provider $authority; retrying with the current-user manager", e)
            currentUserPackageManager.resolveContentProvider(authority, 0)
        }
    }

    private fun getInstallerPackageName(packageName: String): String? {
        return try {
            packageManager.getInstallerPackageName(packageName)
        } catch (e: SecurityException) {
            Log.w(
                TAG,
                "Could not query installer for $packageName; retrying with the current-user manager",
                e
            )
            currentUserPackageManager.getInstallerPackageName(packageName)
        }
    }

    private fun getInstallSessions(): List<SessionInfo> {
        val privilegedCrossUserQuery =
            useShizuku && hasShizukuPermission(INTERACT_ACROSS_USERS)
        val currentUserInstaller = if (privilegedCrossUserQuery) {
            packageInstaller
        } else {
            currentUserPackageManager.packageInstaller
        }
        return try {
            currentUserInstaller.allSessions
        } catch (e: SecurityException) {
            if (currentUserInstaller === packageInstaller) {
                Log.w(TAG, "Could not query privileged install sessions; retrying for current user", e)
                try {
                    currentUserPackageManager.packageInstaller.allSessions
                } catch (fallbackError: SecurityException) {
                    Log.w(TAG, "Could not query current-user install sessions", fallbackError)
                    emptyList()
                }
            } else {
                Log.w(TAG, "Could not query current-user install sessions", e)
                emptyList()
            }
        }
    }

    private fun getInstallSession(sessionId: Int): SessionInfo? {
        val privilegedCrossUserQuery =
            useShizuku && hasShizukuPermission(INTERACT_ACROSS_USERS)
        val currentUserInstaller = if (privilegedCrossUserQuery) {
            packageInstaller
        } else {
            currentUserPackageManager.packageInstaller
        }
        return try {
            currentUserInstaller.getSessionInfo(sessionId)
        } catch (e: SecurityException) {
            if (currentUserInstaller === packageInstaller) {
                Log.w(TAG, "Could not query privileged session $sessionId; retrying for current user", e)
                try {
                    currentUserPackageManager.packageInstaller.getSessionInfo(sessionId)
                } catch (fallbackError: SecurityException) {
                    Log.w(TAG, "Could not query current-user session $sessionId", fallbackError)
                    null
                }
            } else {
                Log.w(TAG, "Could not query current-user session $sessionId", e)
                null
            }
        }
    }

    fun parseUri() {
        try {
            parseUriInternal()
        } catch (e: Exception) {
            reportUnexpectedFailure(e)
        } catch (e: LinkageError) {
            reportUnexpectedFailure(e)
        }
    }

    private fun parseUriInternal() {
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
        try {
            installInternal(
                setInstaller,
                grantPermissions,
                deleteSource,
                commit,
                full,
                removeSplit
            )
        } catch (e: Exception) {
            cleanupInstall()
            reportUnexpectedFailure(e)
        } catch (e: LinkageError) {
            cleanupInstall()
            reportUnexpectedFailure(e)
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            cleanupInstall()
            reportUnexpectedFailure(t)
        }
    }

    private fun installInternal(
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
        if (useShizuku && !hasShizukuInstallPermission()) {
            throw SecurityException("Shizuku disconnected before the installation started")
        }
        if (!useShizuku &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            throw SecurityException(
                "Allow this app to install unknown apps in Android settings, then retry"
            )
        }
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create a staging session", e)
                reportUnexpectedFailure(e)
                return
            }
        }

        try {
            val session = packageInstaller.openSession(stagedSessionId)
            try {
                if (useShizuku) session.wrap()
                if (removeSplit) {
                    session.removeSplit(apkLite!!.splitName!!)
                } else {
                    val afd = stagedSourceFile?.let(::openStagedSourceDescriptor)
                        ?: context.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?: throw IOException("Could not open the selected package file")
                    afd.use {
                        captureSourcePath(it.parcelFileDescriptor.fd)
                        if (apkLite!!.zip) {
                            PackageUtil.stageZip(session, it) {
                                stagingProgress.postValue(it)
                            }
                        } else {
                            PackageUtil.stageApk(session, it) {
                                stagingProgress.postValue(it)
                            }
                        }
                    }
                }
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            cleanupInstall()
            Log.e(TAG, "Could not stage APK.", e)
            reportUnexpectedFailure(e)
            return
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            cleanupInstall()
            Log.e(TAG, "Could not stream package into the install session.", t)
            reportUnexpectedFailure(t)
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
                resolveContentProvider(authority)?.also { info ->
                    callingUid = info.applicationInfo.uid
                }
            }
        }
        val apk = try {
            openPackageDescriptor(uri)?.use { afd ->
                captureSourcePath(afd.parcelFileDescriptor.fd)
                PackageUtil.parseZipFromFd(context, afd, stagedSourceFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse APK from content URI: $uri", e)
            null
        }
        if (apk == null) {
            return InstallAborted(ABORT_PARSE)
        }
        val old = try {
            getPackageInfo(apk.packageName, MATCH_KNOWN_PACKAGES)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        var full = true
        if (apk.isSplit()) {
            for (item in getInstallSessions()) {
                if (item.isActive && item.appPackageName == apk.packageName) {
                    if (useShizuku && hasShizukuPermission(INTERACT_ACROSS_USERS)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // noinspection NewApi
                            if (item.installerUid != Shizuku.getUid()) continue
                        } else {
                            if (item.installReason != PackageManager.INSTALL_REASON_USER) continue
                            item as PackageInstaller_rename.SessionInfo
                            if (item.installFlags and INSTALL_FROM_ADB == 0) {
                                continue
                            }
                        }
                    } else {
                        if (item.installerPackageName != context.packageName) continue
                    }
                    val info = getInstallSession(item.sessionId) ?: continue
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
            old.sharedUserId = getInstallerPackageName(apk.packageName)
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

    private fun openPackageDescriptor(uri: Uri): AssetFileDescriptor? {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return context.contentResolver.openAssetFileDescriptor(uri, "r")
        }

        val cachedFile = File.createTempFile("installer-source-", ".apk", context.cacheDir)
        try {
            val input = when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> {
                    val path = uri.path ?: throw IOException("Package file URI has no path")
                    File(path).inputStream()
                }
                ContentResolver.SCHEME_CONTENT ->
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not read the selected package")
                else -> throw IOException("Unsupported package URI: $uri")
            }
            input.use { source ->
                cachedFile.outputStream().use { destination ->
                    source.copyTo(destination)
                }
            }
            if (cachedFile.length() == 0L) {
                throw IOException("The selected package file is empty")
            }

            val descriptor = openStagedSourceDescriptor(cachedFile)
            stagedSourceFile?.delete()
            stagedSourceFile = cachedFile
            return descriptor
        } catch (e: Exception) {
            cachedFile.delete()
            throw e
        } catch (e: LinkageError) {
            cachedFile.delete()
            throw e
        }
    }

    private fun openStagedSourceDescriptor(file: File): AssetFileDescriptor {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, file.length())
    }

    private fun processPackageUri(packageName: String): InstallStage {
        val info = try {
            getPackageInfo(packageName, MATCH_KNOWN_PACKAGES)
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
                INSTALL_SUCCEEDED,
                null
            )
        } catch (e: PackageManager.NameNotFoundException) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE,
                INSTALL_FAILED_INTERNAL_ERROR,
                e.localizedMessage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not install existing package", e)
            reportUnexpectedFailure(e)
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
        if (setInstaller && useShizuku) {
            try {
                getPackageInfo("com.android.vending", PackageManager.MATCH_SYSTEM_ONLY)
                installer = "com.android.vending"
            } catch (_: PackageManager.NameNotFoundException) {
                installer = "com.android.shell"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        } else {
            val referrerUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_REFERRER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setReferrerUri(referrerUri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val source = if (referrerUri != null) PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                params.setPackageSource(source)
            }
            params.setOriginatingUri(intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI))
            if (useShizuku) {
                params.setOriginatingUid(
                    intent.getIntExtra(Intent_rename.EXTRA_ORIGINATING_UID, callingUid)
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useShizuku) {
            params.setInstallerPackageName(installer)
        }
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setAppPackageName(apkLite!!.packageName)
        if (apkLite!!.needSplit()) {
            params.setAppIcon(apkLite!!.icon?.toBitmap())
            params.setAppLabel(apkLite!!.label)
        }

        if (useShizuku) {
            val p = params as PackageInstaller_rename.SessionParams
            p.installFlags = p.installFlags or INSTALL_ALLOW_TEST
            p.installFlags = p.installFlags or INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS
            p.installFlags = p.installFlags or INSTALL_REPLACE_EXISTING
            p.installFlags = p.installFlags or INSTALL_REQUEST_DOWNGRADE
            p.installFlags = p.installFlags or INSTALL_FULL_APP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                p.installFlags = p.installFlags or INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK
                p.installFlags = p.installFlags or INSTALL_REQUEST_UPDATE_OWNERSHIP
            }
        }
        return params
    }

    private fun commit() {
        var resultReceiver: InstallResultSender? = null
        try {
            val callbackReceiver = createInstallResultSender { status, legacyStatus, message, resultIntent ->
                try {
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        launchPendingUserAction(resultIntent)
                    } else {
                        setStageBasedOnResult(status, legacyStatus, message)
                    }
                } catch (e: Exception) {
                    reportUnexpectedFailure(e)
                } catch (e: LinkageError) {
                    reportUnexpectedFailure(e)
                } catch (t: Throwable) {
                    if (t is VirtualMachineError || t is ThreadDeath) throw t
                    reportUnexpectedFailure(t)
                }
            }
            resultReceiver = callbackReceiver
            val session = packageInstaller.openSession(stagedSessionId)
            try {
                if (useShizuku) session.wrap()
                // noinspection RequestInstallPackagesPolicy
                session.commit(callbackReceiver.intentSender)
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit staged session", e)
            cleanupInstall()
            resultReceiver?.close?.invoke()
            reportUnexpectedFailure(e)
        } catch (e: LinkageError) {
            Log.e(TAG, "Failed to create or commit install result receiver", e)
            cleanupInstall()
            resultReceiver?.close?.invoke()
            reportUnexpectedFailure(e)
        }
    }

    private fun createInstallResultSender(
        callback: (Int, Int, String?, Intent) -> Unit
    ): InstallResultSender {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val helper = Class.forName(
                    "io.i10nstaller.model.ModernInstallResultSender"
                )
                val sender = helper.getMethod(
                    "create",
                    kotlin.jvm.functions.Function4::class.java
                ).invoke(null, callback) as IntentSender
                return InstallResultSender(sender) {}
            } catch (e: LinkageError) {
                Log.w(TAG, "Hidden install-result receiver is unavailable; using PendingIntent", e)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create hidden install-result receiver; using PendingIntent", e)
            }
        }

        val receiver = PendingIntentInstallReceiver(context, callback)
        return InstallResultSender(receiver.pendingIntent.intentSender, receiver::close)
    }

    private data class InstallResultSender(
        val intentSender: IntentSender,
        val close: () -> Unit
    )

    private fun launchPendingUserAction(resultIntent: Intent) {
        val confirmationIntent = resultIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            ?: throw IllegalStateException("Package installer did not provide a confirmation screen")
        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(confirmationIntent)
    }

    private fun setStageBasedOnResult(statusCode: Int, legacyStatus: Int, message: String?) {
        try {
            setStageBasedOnResultInternal(statusCode, legacyStatus, message)
        } catch (e: Exception) {
            reportUnexpectedFailure(e)
        } catch (e: LinkageError) {
            reportUnexpectedFailure(e)
        }
    }

    private fun setStageBasedOnResultInternal(statusCode: Int, legacyStatus: Int, message: String?) {
        val installedApk = apkLite
        if (installedApk == null) {
            reportUnexpectedFailure(IllegalStateException("Install result received without a package"))
            return
        }
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            val packageName = installedApk.packageName
            val installedSource = sourceUri
            val launchIntent = try {
                packageManager.getLaunchIntentForPackage(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve launch activity for $packageName", e)
                null
            }
            installResult.postValue(InstallSuccess(installedApk, launchIntent))
            postInstallScope.launch {
                if (grantPermissionsAfterInstall) grantPostInstallAccess(packageName)
                if (deleteSourceAfterInstall) deleteSourceApk(installedSource)
            }
        } else {
            installResult.postValue(InstallFailed(installedApk, legacyStatus, statusCode, message))
        }
    }

    fun reportUnexpectedFailure(error: Throwable) {
        installResult.postValue(failureStage(error))
    }

    private fun failureStage(error: Throwable): InstallStage {
        Log.e(TAG, "Install operation failed", error)
        val message = error.localizedMessage
            ?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        val currentApk = apkLite
        return if (currentApk != null) {
            InstallFailed(
                currentApk,
                INSTALL_FAILED_INTERNAL_ERROR,
                PackageInstaller.STATUS_FAILURE,
                message
            )
        } else {
            InstallAborted(ABORT_FAILURE, message = message)
        }
    }

    private fun grantPostInstallAccess(packageName: String) {
        if (!useShizuku || !hasShizukuInstallPermission()) {
            Log.w(TAG, "Skipping post-install permissions because Shizuku is unavailable")
            return
        }
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return

        val uid = try {
            getApplicationInfo(packageName, 0).uid
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

    private data class MediaStoreEntry(
        val collectionUri: Uri,
        val id: Long,
        val dataPath: String?,
        val relativePath: String?
    ) {
        val itemUri: Uri
            get() = ContentUris.withAppendedId(collectionUri, id)
    }

    private fun deleteSourceApk(uri: Uri?) {
        if (uri == null) return

        val sourceMetadata = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            querySourceMetadata(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
            )
        } else {
            null
        }
        val path = sourcePath ?: resolveSourcePath(uri)
            ?: sourceMetadata?.first?.takeIf { it.startsWith("/") }
        val paths = sourcePaths(path, sourceMetadata?.first)
        val displayName = sourceMetadata?.third?.takeIf { it.isNotBlank() }
            ?: path?.let { File(it).name }
        val relativePath = sourceMetadata?.second?.takeIf { it.isNotBlank() }
            ?: path?.let { File(it).parentFile?.let(::mediaRelativePath) }
        Log.d(CLEANUP_TAG, "Source URI=$uri resolvedPath=$path pathCandidates=$paths")

        val entries = queryMediaStoreEntries(paths, displayName, relativePath)
        Log.d(
            CLEANUP_TAG,
            "Pre-delete MediaStore entries=${entries.map { "${it.collectionUri}/${it.id}:${it.dataPath}" }}"
        )

        var deletionSucceeded = false
        val resolver = context.contentResolver

        // Tier 1: ask the source provider to delete its own document/URI.
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            if (DocumentsContract.isDocumentUri(context, uri) || DocumentsContract.isTreeUri(uri)) {
                try {
                    val rows = DocumentsContract.deleteDocument(resolver, uri)
                    deletionSucceeded = deletionSucceeded || rows
                    Log.d(CLEANUP_TAG, "DocumentContract delete uri=$uri result=$rows")
                } catch (e: Exception) {
                    Log.d(CLEANUP_TAG, "DocumentContract delete failed uri=$uri: ${e.message}")
                }
            }
            try {
                val rows = resolver.delete(uri, null, null)
                deletionSucceeded = deletionSucceeded || rows > 0
                Log.d(CLEANUP_TAG, "Source URI delete uri=$uri rows=$rows")
            } catch (e: Exception) {
                Log.d(CLEANUP_TAG, "Source URI delete failed uri=$uri: ${e.message}")
            }
        }

        // Tier 2: remove the captured MediaStore rows by item ID and by file path.
        for (entry in entries) {
            try {
                val rows = resolver.delete(entry.itemUri, null, null)
                Log.d(
                    CLEANUP_TAG,
                    "Resolver item delete uri=${entry.itemUri} id=${entry.id} rows=$rows"
                )
            } catch (e: Exception) {
                Log.d(CLEANUP_TAG, "Resolver item delete failed uri=${entry.itemUri}: ${e.message}")
            }
        }
        val collections = mediaStoreCollections()
        for (collection in collections) {
            for (candidatePath in paths) {
                try {
                    val rows = resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DATA}=?",
                        arrayOf(candidatePath)
                    )
                    Log.d(
                        CLEANUP_TAG,
                        "Resolver path delete collection=$collection path=$candidatePath rows=$rows"
                    )
                } catch (e: Exception) {
                    Log.d(
                        CLEANUP_TAG,
                        "Resolver path delete failed collection=$collection path=$candidatePath: ${e.message}"
                    )
                }
            }
            if (entries.none { it.collectionUri == collection } &&
                !displayName.isNullOrBlank() && !relativePath.isNullOrBlank()
            ) {
                try {
                    val rows = resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(displayName, relativePath)
                    )
                    Log.d(
                        CLEANUP_TAG,
                        "Resolver name delete collection=$collection name=$displayName rows=$rows"
                    )
                } catch (e: Exception) {
                    Log.d(CLEANUP_TAG, "Resolver name delete failed collection=$collection: ${e.message}")
                }
            }
        }

        // Tier 3: Shizuku's shell identity can delete rows owned by other apps.
        if (!path.isNullOrBlank()) {
            val result = runShizukuCommand(
                "rm -f -- ${shellQuote(path)}",
                "delete source APK",
                logCleanupOutput = true
            )
            deletionSucceeded = deletionSucceeded || result
        }
        val sqlPaths = paths.map { it.replace("'", "''") }.distinct()
        for (collection in collections) {
            for (candidatePath in sqlPaths) {
                val where = "${MediaStore.MediaColumns.DATA}='$candidatePath'"
                runShizukuCommand(
                    "content delete --uri ${shellQuote(collection.toString())} " +
                        "--where ${shellQuote(where)}",
                    "delete MediaStore path row",
                    logCleanupOutput = true
                )
            }
        }
        for (entry in entries) {
            runShizukuCommand(
                "content delete --uri ${shellQuote(entry.itemUri.toString())}",
                "delete MediaStore row ${entry.id}",
                logCleanupOutput = true
            )
        }

        // Tier 4: refresh the scanner for the containing directory and deleted path.
        if (paths.isNotEmpty()) {
            val scanPaths = paths.flatMap { candidate ->
                listOfNotNull(File(candidate).parent, candidate)
            }.distinct().toTypedArray()
            Log.d(CLEANUP_TAG, "Scanning paths=${scanPaths.contentToString()}")
            MediaScannerConnection.scanFile(context, scanPaths, null) { scannedPath, scannedUri ->
                Log.d(CLEANUP_TAG, "Media scan completed path=$scannedPath uri=$scannedUri")
            }
        }

        Log.d(
            CLEANUP_TAG,
            "Cleanup complete uri=$uri path=$path providerDeleteSucceeded=$deletionSucceeded"
        )
    }

    private fun mediaStoreCollections(): List<Uri> {
        val collections = mutableListOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections += MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        return collections
    }

    private fun sourcePaths(vararg paths: String?): List<String> {
        return paths.filterNotNull().filter { it.startsWith('/') }.flatMap { path ->
            val file = File(path)
            listOfNotNull(
                file.absolutePath,
                try {
                    file.canonicalPath
                } catch (_: IOException) {
                    null
                }
            )
        }.distinct()
    }

    private fun queryMediaStoreEntries(
        paths: List<String>,
        displayName: String?,
        relativePath: String?
    ): List<MediaStoreEntry> {
        val entries = mutableListOf<MediaStoreEntry>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        for (collection in mediaStoreCollections()) {
            if (paths.isNotEmpty()) {
                val placeholders = paths.joinToString(",") { "?" }
                queryMediaStoreRows(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.DATA} IN ($placeholders)",
                    paths.toTypedArray()
                )?.let(entries::addAll)
            }
            if (entries.none { it.collectionUri == collection } && !displayName.isNullOrBlank()) {
                val selection = if (relativePath.isNullOrBlank()) {
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                } else {
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                }
                val args = if (relativePath.isNullOrBlank()) {
                    arrayOf(displayName)
                } else {
                    arrayOf(displayName, relativePath)
                }
                val fallbackRows = queryMediaStoreRows(collection, projection, selection, args)
                val matchingRows = fallbackRows.orEmpty().filter { entry ->
                    entry.dataPath in paths ||
                        (!relativePath.isNullOrBlank() && entry.relativePath == relativePath) ||
                        fallbackRows?.size == 1
                }
                entries += matchingRows
            }
        }
        return entries.distinctBy { it.collectionUri to it.id }
    }

    private fun queryMediaStoreRows(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>
    ): List<MediaStoreEntry>? {
        return try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relativeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                buildList {
                    while (cursor.moveToNext()) {
                        if (idIndex < 0) continue
                        add(
                            MediaStoreEntry(
                                collection,
                                cursor.getLong(idIndex),
                                if (dataIndex >= 0) cursor.getString(dataIndex) else null,
                                if (relativeIndex >= 0) cursor.getString(relativeIndex) else null
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(CLEANUP_TAG, "MediaStore query failed collection=$collection: ${e.message}")
            null
        }
    }

    private fun mediaRelativePath(directory: File): String? {
        return try {
            val root = Environment.getExternalStorageDirectory().canonicalPath
            val directoryPath = directory.canonicalPath
            val rootPrefix = root.trimEnd(File.separatorChar) + File.separator
            directoryPath.removePrefix(rootPrefix)
                .takeIf { directoryPath.startsWith(rootPrefix) }
                ?.let { "$it${File.separator}" }
        } catch (e: IOException) {
            Log.w(TAG, "Could not resolve MediaStore relative path for $directory", e)
            null
        }
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

    private fun runShizukuCommand(
        command: String,
        operation: String,
        logCleanupOutput: Boolean = false
    ): Boolean {
        try {
            if (!hasShizukuInstallPermission()) {
                Log.w(TAG, "Skipping Shizuku operation because permission is unavailable: $operation")
                return false
            }
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
            if (logCleanupOutput) {
                Log.d(CLEANUP_TAG, "Shizuku operation=$operation exitCode=$exitCode output=$output")
            }
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
        if (stagedSessionId != SessionInfo.INVALID_ID) {
            try {
                packageInstaller.abandonSession(stagedSessionId)
            } catch (e: Exception) {
                Log.w(TAG, "Could not abandon install session $stagedSessionId", e)
            }
            stagedSessionId = SessionInfo.INVALID_ID
        }
        stagedSourceFile?.let { file ->
            if (!file.delete()) {
                Log.w(TAG, "Could not delete staged package ${file.absolutePath}")
            }
            stagedSourceFile = null
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
            var resultReceiver: InstallResultSender? = null
            try {
                val callbackReceiver =
                    createInstallResultSender { statusCode, legacyCode, msg, resultIntent ->
                        try {
                            if (statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                                launchPendingUserAction(resultIntent)
                            } else if (statusCode == PackageInstaller.STATUS_SUCCESS) {
                                installResult.postValue(InstallSuccess(apkLite!!, intent, path))
                            } else {
                                installResult.postValue(
                                    InstallSuccess(apkLite!!, intent, "$path\n\n$msg")
                                )
                            }
                        } catch (e: Exception) {
                            reportUnexpectedFailure(e)
                        } catch (e: LinkageError) {
                            reportUnexpectedFailure(e)
                        }
                    }
                resultReceiver = callbackReceiver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    try {
                        // noinspection MissingPermission
                        packageInstaller.requestArchive(
                            info.packageName,
                            callbackReceiver.intentSender
                        )
                        return
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Failed to archive for package ${info.packageName}", e)
                    }
                }
                // noinspection MissingPermission NewApi
                packageInstaller.uninstall(
                    VersionedPackage(info.packageName, info.longVersionCode),
                    DELETE_KEEP_DATA,
                    callbackReceiver.intentSender
                )
                val userId = Os.getuid() / 100000
                val pm = packageManager as PackageManager_rename
                pm.deleteApplicationCacheFilesAsUser(info.packageName, userId, null)
            } catch (e: Exception) {
                resultReceiver?.close?.invoke()
                reportUnexpectedFailure(e)
            } catch (e: LinkageError) {
                resultReceiver?.close?.invoke()
                reportUnexpectedFailure(e)
            }
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
