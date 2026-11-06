package io.i10nstaller.model

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.Context
import android.content.res.ApkAssets
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.content.res.`AssetManager$Builder`
import android.content.res.Resources
import android.content.res.XmlBlock
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Build.VERSION_CODES
import android.util.DisplayMetrics
import android.util.Log
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.io.IOUtils
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.function.Consumer

object PackageUtil {
    private const val TAG = "PackageUtil"

    fun getApkLite(pm: PackageManager, pkgInfo: PackageInfo): ApkLite {
        val info = pkgInfo.applicationInfo ?: ApplicationInfo().apply {
            packageName = pkgInfo.packageName
        }
        return ApkLite(
            pkgInfo.packageName,
            null,
            null,
            null,
            pkgInfo.getLongVersionCode(),
            pkgInfo.versionName ?: "null",
            info.minSdkVersion.toString(),
            info.targetSdkVersion.toString(),
            info.loadLabel(pm).toString(),
            info.loadIcon(pm),
        ).apply { zip = true }
    }

    fun parseZipFromFd(
        context: Context,
        afd: AssetFileDescriptor,
        archivePath: File? = null
    ): ApkLite? {
        ZipFile.builder()
            .setIgnoreLocalFileHeader(true)
            .setSeekableByteChannel(afd.createInputStream().getChannel())
            .get().use { zipFile ->
                val xml = zipFile.getEntry("AndroidManifest.xml")
                if (xml != null) {
                    val apkLite = try {
                        getAppLiteFromApkFd(
                            afd.getFileDescriptor(),
                            afd.toString(),
                            afd.getLength()
                        )
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Could not load APK assets from file descriptor; parsing manifest bytes",
                            e
                        )
                        null
                    } catch (e: LinkageError) {
                        Log.w(TAG, "APK assets API is unavailable; parsing manifest bytes", e)
                        null
                    }
                    if (apkLite != null) {
                        resolveArchiveResources(
                            context,
                            afd,
                            apkLite,
                            archivePath,
                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                        )
                        return apkLite
                    }

                    zipFile.getInputStream(xml).use { input ->
                        val bytes = IOUtils.toByteArray(input)
                        return getAppLiteFromXmlBytes(bytes)?.also {
                            resolveArchiveResources(
                                context,
                                afd,
                                it,
                                archivePath,
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                            )
                        }
                    }
                }

                var base = zipFile.getEntry("base.apk")
                    ?: zipFile.getEntry("splits/base-master_2.apk")
                if (base == null) {
                    for (entry in zipFile.getEntries()) {
                        if (entry.getName().endsWith(".apk")) {
                            if (base == null) {
                                base = entry
                            } else {
                                return null
                            }
                        }
                    }
                    if (base == null) {
                        return null
                    }
                }

                ZipArchiveInputStream(zipFile.getInputStream(base)).use { input ->
                    var innerEntry = input.getNextEntry()
                    while (innerEntry != null) {
                        if (innerEntry.getName() == "AndroidManifest.xml") {
                            val bytes = IOUtils.toByteArray(input)
                            return getAppLiteFromXmlBytes(bytes)?.apply {
                                zip = true
                            }
                        }
                        innerEntry = input.getNextEntry()
                    }
                    return null
                }
            }

    }

    private fun resolveArchiveResources(
        context: Context,
        afd: AssetFileDescriptor,
        apkLite: ApkLite,
        archivePath: File?,
        force: Boolean
    ) {
        if (!force &&
            !apkLite.label.isNullOrBlank() &&
            !apkLite.label!!.startsWith("@") &&
            apkLite.icon != null
        ) {
            return
        }

        var temporaryArchive: File? = null
        try {
            val archiveFile = archivePath ?: File.createTempFile(
                "installer-archive-",
                ".apk",
                context.cacheDir
            ).also { temporaryArchive = it }
            if (archivePath == null) {
                afd.createInputStream().use { input ->
                    FileOutputStream(archiveFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val packageManager = context.packageManager
            val archiveInfo = packageManager.getPackageArchiveInfo(
                archiveFile.absolutePath,
                PackageManager.GET_META_DATA
            ) ?: return
            val applicationInfo = archiveInfo.applicationInfo ?: return
            applicationInfo.sourceDir = archiveFile.absolutePath
            applicationInfo.publicSourceDir = archiveFile.absolutePath

            val label = applicationInfo.loadLabel(packageManager)?.toString()
                ?.takeIf { it.isNotBlank() && !it.startsWith("@") }
                ?: archiveInfo.packageName
            if (force || apkLite.label.isNullOrBlank() || apkLite.label!!.startsWith("@")) {
                apkLite.label = label
            }
            applicationInfo.loadIcon(packageManager)?.let { apkLite.icon = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve app label and icon from APK archive resources", e)
        } catch (e: LinkageError) {
            Log.w(TAG, "APK archive metadata API is unavailable", e)
        } finally {
            temporaryArchive?.delete()
        }
    }

    private fun getAppLiteFromXmlBytesLink(xmlBytes: ByteArray): ApkLite? {
        XmlBlock(xmlBytes).use {
            it.newParser().use { parser ->
                val res = Resources.getSystem()
                return parsingAndroidManifest(parser, res)
            }
        }
    }

    private fun getAppLiteFromXmlBytes(xmlBytes: ByteArray): ApkLite? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getAppLiteFromXmlBytesLink(xmlBytes)
        }
        val clazz = Class.forName("android.content.res.XmlBlock")
        clazz.getConstructor(ByteArray::class.java).run {
            setAccessible(true)
            newInstance(xmlBytes) as AutoCloseable
        }.use {
            clazz.getDeclaredMethod("newParser").run {
                invoke(it) as XmlResourceParser
            }.use { parser ->
                val res = Resources.getSystem()
                return parsingAndroidManifest(parser, res)
            }
        }
    }

    private fun getAppLiteFromApkFd(fd: FileDescriptor, name: String, length: Long): ApkLite? {
        val assets = loadApkAssetsFromFd(fd, name, length)
        val am = `AssetManager$Builder`()
            .addApkAssets(assets)
            .build() as AssetManager
        val res = Resources.getSystem()
        val apkResources = Resources(am, res.getDisplayMetrics(), res.getConfiguration())

        val xmlParser = assets.openXml("AndroidManifest.xml")
        return xmlParser?.use {
            parsingAndroidManifest(xmlParser, apkResources)
        }
    }

    private fun loadApkAssetsFromFd(
        fd: FileDescriptor,
        friendlyName: String,
        length: Long
    ): ApkAssets {
        val method = ApkAssets::class.java.declaredMethods
            .filter { candidate ->
                candidate.name == "loadFromFd" &&
                    java.lang.reflect.Modifier.isStatic(candidate.modifiers) &&
                    isSupportedLoadFromFdSignature(candidate.parameterTypes)
            }
            .minByOrNull { candidate -> loadFromFdPriority(candidate.parameterTypes) }
            ?: throw NoSuchMethodException("No supported ApkAssets.loadFromFd method")
        method.isAccessible = true

        val parameters = method.parameterTypes
        val arguments = when {
            parameters.size == 6 -> arrayOf(fd, friendlyName, 0L, length, 0, null)
            parameters.size == 4 && parameters[2] == Int::class.javaPrimitiveType ->
                arrayOf(fd, friendlyName, 0, null)
            parameters.size == 3 -> arrayOf(fd, friendlyName, 0)
            else -> arrayOf(fd, friendlyName, false, false)
        }
        return method.invoke(null, *arguments) as ApkAssets
    }

    private fun loadFromFdPriority(parameters: Array<Class<*>>): Int =
        when {
            parameters.size == 6 -> 0
            parameters.size == 4 && parameters[2] == Int::class.javaPrimitiveType -> 1
            parameters.size == 3 -> 2
            else -> 3
        }

    private fun isSupportedLoadFromFdSignature(parameters: Array<Class<*>>): Boolean {
        if (parameters.size < 2 ||
            parameters[0] != FileDescriptor::class.java ||
            parameters[1] != String::class.java
        ) {
            return false
        }
        return when (parameters.size) {
            6 -> parameters[2] == Long::class.javaPrimitiveType &&
                parameters[3] == Long::class.javaPrimitiveType &&
                parameters[4] == Int::class.javaPrimitiveType &&
                parameters[5].simpleName == "AssetsProvider"
            4 -> (parameters[2] == Int::class.javaPrimitiveType &&
                parameters[3].simpleName == "AssetsProvider") ||
                (parameters[2] == Boolean::class.javaPrimitiveType &&
                    parameters[3] == Boolean::class.javaPrimitiveType)
            3 -> parameters[2] == Int::class.javaPrimitiveType
            else -> false
        }
    }

    private fun parsingAndroidManifest(parser: XmlResourceParser, res: Resources): ApkLite? {
        val ns = "http://schemas.android.com/apk/res/android"
        var icon: Drawable? = null
        var label: String? = null
        var packageName: String? = null
        var splitName: String? = null
        var versionCode = 0
        var versionCodeMajor = 0
        var versionName: String? = null
        var requiredSplitTypes: String? = null
        var splitTypes: String? = null
        var minSdk: String? = null
        var targetSdk: String? = null

        var eventType = parser.getEventType()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.getName() == "application") {
                val iconId = parser.getAttributeResourceValue(ns, "icon", 0)
                icon = try {
                    res.getDrawable(iconId, null)
                } catch (_: Resources.NotFoundException) {
                    null
                }
                val labelId = parser.getAttributeResourceValue(ns, "label", 0)
                label = if (labelId == 0) {
                    parser.getAttributeValue(ns, "label")
                } else try {
                    res.getString(labelId)
                } catch (_: Resources.NotFoundException) {
                    null
                }
                if (packageName != null && minSdk != null) {
                    break
                }
            } else if (eventType == XmlPullParser.START_TAG && parser.getName() == "manifest") {
                packageName = parser.getAttributeValue(null, "package")
                splitName = parser.getAttributeValue(null, "split")
                versionCode = parser.getAttributeIntValue(ns, "versionCode", 0)
                versionCodeMajor = parser.getAttributeIntValue(ns, "versionCodeMajor", 0)
                versionName = parser.getAttributeValue(ns, "versionName")
                requiredSplitTypes =
                    parser.getAttributeValue(ns, "requiredSplitTypes")
                splitTypes = parser.getAttributeValue(ns, "splitTypes")
            } else if (eventType == XmlPullParser.START_TAG && parser.getName() == "uses-sdk") {
                minSdk = parser.getAttributeValue(ns, "minSdkVersion")
                targetSdk = parser.getAttributeValue(ns, "targetSdkVersion")
            }
            eventType = parser.next()
        }
        if (packageName == null) {
            return null
        }
        return ApkLite(
            packageName,
            splitName,
            requiredSplitTypes,
            splitTypes,
            ((versionCodeMajor.toLong()) shl 32) or ((versionCode.toLong()) and 0xffffffffL),
            versionName ?: "null",
            minSdk ?: "1",
            targetSdk ?: "0",
            label,
            icon
        )
    }

    private fun densityFilter(): List<String> {
        val allDpis = arrayOf(
            DisplayMetrics.DENSITY_LOW,
            DisplayMetrics.DENSITY_MEDIUM,
            DisplayMetrics.DENSITY_TV,
            DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_XHIGH,
            DisplayMetrics.DENSITY_XXHIGH,
            DisplayMetrics.DENSITY_XXXHIGH
        )
        val deviceDpi = Resources.getSystem().getDisplayMetrics().densityDpi
        val (higherOrEqual, lower) = allDpis.partition { it >= deviceDpi }
        val sortedDpis = higherOrEqual.sorted() + lower.sortedDescending()
        return sortedDpis.map {
            when (it) {
                DisplayMetrics.DENSITY_LOW -> "ldpi"
                DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
                DisplayMetrics.DENSITY_TV -> "tvdpi"
                DisplayMetrics.DENSITY_HIGH -> "hdpi"
                DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
                DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
                DisplayMetrics.DENSITY_XXXHIGH -> "xxxhdpi"
                else -> "unknown"
            }
        }
    }

    private fun languageFilter(): List<String> {
        val res = Resources.getSystem()
        val languageSet = LinkedHashSet<String>()
        val localeList = res.getConfiguration().getLocales()
        for (i in 0..<localeList.size()) {
            val locale = localeList.get(i)
            languageSet.add(locale.toLanguageTag().substringBefore('-'))
        }
        languageSet.add("base")
        val allLocal = res.getAssets().getLocales()
        for (tag in allLocal) {
            val locale = Locale.forLanguageTag(tag)
            languageSet.add(locale.toLanguageTag().substringBefore('-'))
        }
        return languageSet.toList()
    }

    private fun abiFilter(): List<String> {
        val abiSet = LinkedHashSet<String>()
        Build.SUPPORTED_ABIS.forEach { abi ->
            abiSet.add(abi.replace('-', '_'))
        }
        abiSet.addAll(arrayOf("armeabi_v7a", "x86", "x86_64", "arm64_v8a", "riscv64"))
        return abiSet.toList()
    }

    private fun ZipArchiveEntry.getLabel(): String {
        val name = this.getName().removeSuffix(".apk").removePrefix("split_")
        return if (name.startsWith("config.")) {
            name.removePrefix("config.")
        } else name.substringAfterLast(".config.")
    }

    private fun ZipArchiveEntry.getApksLabel(): String {
        val name = this.getName().substringAfterLast('/').removeSuffix(".apk")
        return if (name.startsWith("base-")) {
            name.removePrefix("base-")
        } else name.substringAfterLast("-")
    }

    private fun String.convert(): String? {
        return when (this) {
            "master" -> null
            "master_2" -> "base"
            "tl" -> "fil"
            "iw" -> "he"
            "ji" -> "yi"
            "in" -> "id"
            else -> this
        }
    }

    private fun filterZipEntries(zipFile: ZipFile): List<ZipArchiveEntry> {
        val isApks = zipFile.getEntry("toc.pb") != null
        val apkMap = mutableMapOf<String, MutableList<ZipArchiveEntry>>()
        zipFile.getEntries().asSequence()
            .filter { it.getName().endsWith(".apk") }
            .forEach {
                val label = if (isApks) {
                    it.getApksLabel().convert()
                } else {
                    it.getLabel().convert()
                }
                if (label == null) {
                    return@forEach
                }
                apkMap.getOrPut(label) { mutableListOf() }.add(it)
            }
        for (filter in arrayOf(abiFilter(), densityFilter(), languageFilter())) {
            var found = false
            for (name in filter) {
                if (apkMap.containsKey(name)) {
                    if (!found) {
                        found = true
                    } else if (name != "base") {
                        apkMap.remove(name)
                    }
                }
            }
        }
        return apkMap.values.flatten()
    }

    fun stageZip(
        session: PackageInstaller.Session,
        afd: AssetFileDescriptor,
        callback: Consumer<Int>,
    ) {
        ZipFile.builder()
            .setIgnoreLocalFileHeader(true)
            .setSeekableByteChannel(afd.createInputStream().getChannel())
            .get().use { zipFile ->
                val apks = filterZipEntries(zipFile)
                val total = apks.sumOf { it.getSize() }
                var totalRead = 0L
                val buffer = ByteArray(16 * 1024)
                callback.accept(0)
                apks.forEach { entry ->
                    val fileName = entry.getName().substringAfterLast('/')
                    session.openWrite(fileName, 0, entry.getSize()).use { out ->
                        zipFile.getInputStream(entry).use { instream ->
                            var numRead: Int
                            while (instream.read(buffer).also { numRead = it } != -1) {
                                out.write(buffer, 0, numRead)
                                totalRead += numRead
                                val fraction = totalRead.toFloat() / total.toFloat()
                                callback.accept((fraction * 100.0).toInt())
                            }
                            session.fsync(out)
                        }
                    }
                }
            }
    }

    fun stageApk(
        session: PackageInstaller.Session,
        afd: AssetFileDescriptor,
        callback: Consumer<Int>,
    ) {
        callback.accept(0)
        val total = afd.getLength()
        var totalRead = 0L
        val fileName = if (Build.VERSION.SDK_INT <= VERSION_CODES.Q) {
            "base.apk"
        } else {
            System.currentTimeMillis().toString()
        }
        session.openWrite(fileName, 0, total).use { out ->
            afd.createInputStream().use { instream ->
                val buffer = ByteArray(16 * 1024)
                var numRead: Int
                while (instream.read(buffer).also { numRead = it } != -1) {
                    out.write(buffer, 0, numRead)
                    totalRead += numRead
                    if (total > 0L) {
                        callback.accept((totalRead.toFloat() / total.toFloat() * 100.0).toInt())
                    }
                }
            }
            if (totalRead == 0L) throw IOException("The selected package contains no data")
            if (total >= 0L && totalRead != total) {
                throw IOException(
                    "The selected package stream was incomplete ($totalRead of $total bytes)"
                )
            }
            session.fsync(out)
        }
    }

    fun archivePackage(
        apks: List<File>,
        afd: AssetFileDescriptor,
        callback: Consumer<Int>,
    ) {
        val total = apks.sumOf { it.length() }
        var totalRead = 0L
        callback.accept(0)
        ZipArchiveOutputStream(afd.createOutputStream().getChannel()).use { out ->
            val buffer = ByteArray(16 * 1024)
            for (apk in apks) {
                val entry = ZipArchiveEntry(apk, apk.name)
                entry.setMethod(ZipMethod.XZ.getCode())
                entry.setSize(apk.length())
                out.putArchiveEntry(entry)
                FileInputStream(apk).use { instream ->
                    var numRead: Int
                    val xz = XZCompressorOutputStream(out, 3)
                    val crc = java.util.zip.CRC32()
                    while (instream.read(buffer).also { numRead = it } != -1) {
                        crc.update(buffer, 0, numRead)
                        xz.write(buffer, 0, numRead)
                        totalRead += numRead
                        val fraction = totalRead.toFloat() / total.toFloat()
                        callback.accept((fraction * 100.0).toInt())
                    }
                    xz.finish()
                    entry.setCrc(crc.getValue())
                }
                out.closeArchiveEntry()
            }
        }
    }

}
