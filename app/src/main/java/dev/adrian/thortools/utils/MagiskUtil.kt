package dev.adrian.thortools.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.adrian.thortools.AppSettings
import java.io.File

enum class MagiskDownloadState {
    NONE,
    PENDING,
    READY,
    FAILED,
}

data class MagiskActionResult(
    val success: Boolean,
    val message: String,
)

object MagiskUtil {
    const val MAGISK_DIR = "/data/adb/magisk"
    const val MAGISK_PACKAGE_NAME = "com.topjohnwu.magisk"
    private const val MAGISK_DOWNLOAD_URL = "https://github.com/topjohnwu/Magisk/releases/latest/download/Magisk.apk"
    private const val MAGISK_DOWNLOAD_FILE_NAME = "Magisk-ThorTools.apk"
    private val MAGISK_UTIL_BINARIES = listOf("busybox", "init-ld", "magisk", "magiskboot", "magiskinit", "magiskpolicy")
    private val MAGISK_UTIL_SUPPORT_FILES = listOf("boot_patch.sh", "util_functions.sh", "stub.apk")
    private val MAGISK_REQUIRED_FILES = MAGISK_UTIL_BINARIES + MAGISK_UTIL_SUPPORT_FILES

    private fun getMagiskAppPath(context: Context): String = runCatching {
        context.packageManager.getApplicationInfo(MAGISK_PACKAGE_NAME, 0).publicSourceDir
    }.getOrDefault("")

    fun requestLatestDownloadOrInstall(context: Context): MagiskActionResult {
        val state = downloadState(context)
        if (shouldEnqueueDownload(state, hasCompletedDownloadUri(context))) {
            return enqueueLatestDownload(context)
        }
        return when (state) {
            MagiskDownloadState.READY -> openDownloadedApk(context)
            MagiskDownloadState.PENDING -> MagiskActionResult(true, "Magisk is still downloading; tap again when the download completes")
            MagiskDownloadState.NONE,
            MagiskDownloadState.FAILED,
            -> enqueueLatestDownload(context)
        }
    }

    fun downloadState(context: Context): MagiskDownloadState {
        val downloadId = AppSettings.getSharedPrefs(context)
            .getLong(AppSettings.MAGISK_DOWNLOAD_ID_KEY, -1L)
            .takeIf { it > 0L }
            ?: return MagiskDownloadState.NONE
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return MagiskDownloadState.NONE
        return runCatching {
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (!cursor.moveToFirst()) return@use MagiskDownloadState.NONE
                classifyDownloadStatus(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
            }
        }.getOrDefault(MagiskDownloadState.NONE)
    }

    internal fun classifyDownloadStatus(status: Int): MagiskDownloadState = when (status) {
        DownloadManager.STATUS_PENDING,
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED,
        -> MagiskDownloadState.PENDING
        DownloadManager.STATUS_SUCCESSFUL -> MagiskDownloadState.READY
        DownloadManager.STATUS_FAILED -> MagiskDownloadState.FAILED
        else -> MagiskDownloadState.NONE
    }

    internal fun shouldEnqueueDownload(state: MagiskDownloadState, completedUriAvailable: Boolean): Boolean = when (state) {
        MagiskDownloadState.NONE,
        MagiskDownloadState.FAILED,
        -> true
        MagiskDownloadState.READY -> !completedUriAvailable
        MagiskDownloadState.PENDING -> false
    }

    private fun enqueueLatestDownload(context: Context): MagiskActionResult = runCatching {
        clearTrackedDownload(context)
        val request = DownloadManager.Request(Uri.parse(MAGISK_DOWNLOAD_URL))
            .setTitle("Magisk")
            .setDescription("Downloading the latest Magisk release")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, MAGISK_DOWNLOAD_FILE_NAME)
        val downloadId = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        AppSettings.getSharedPrefs(context).edit().putLong(AppSettings.MAGISK_DOWNLOAD_ID_KEY, downloadId).commit()
        MagiskActionResult(true, "Magisk download started; tap again when it completes to open the installer")
    }.getOrElse {
        MagiskActionResult(false, "Could not start the Magisk download")
    }

    private fun hasCompletedDownloadUri(context: Context): Boolean {
        val downloadId = trackedDownloadId(context) ?: return false
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        return runCatching { manager.getUriForDownloadedFile(downloadId) != null }.getOrDefault(false)
    }

    private fun trackedDownloadId(context: Context): Long? = AppSettings.getSharedPrefs(context)
        .getLong(AppSettings.MAGISK_DOWNLOAD_ID_KEY, -1L)
        .takeIf { it > 0L }

    private fun clearTrackedDownload(context: Context) {
        val downloadId = trackedDownloadId(context)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadId != null) runCatching { manager?.remove(downloadId) }
        AppSettings.getSharedPrefs(context).edit().remove(AppSettings.MAGISK_DOWNLOAD_ID_KEY).commit()
    }

    private fun openDownloadedApk(context: Context): MagiskActionResult {
        val downloadId = AppSettings.getSharedPrefs(context)
            .getLong(AppSettings.MAGISK_DOWNLOAD_ID_KEY, -1L)
            .takeIf { it > 0L }
            ?: return MagiskActionResult(false, "The completed Magisk download is no longer available; start it again")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return MagiskActionResult(false, "Android Download Manager is unavailable")
        val uri = runCatching { manager.getUriForDownloadedFile(downloadId) }.getOrNull()
            ?: return MagiskActionResult(false, "The completed Magisk download could not be opened; start it again")
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
            return MagiskActionResult(false, "Allow ThorTools to install Magisk in Android settings, then tap again")
        }
        val stagedApk = stageDownloadedApk(context, uri) ?: run {
            clearTrackedDownload(context)
            return MagiskActionResult(false, "The downloaded file is not a valid Magisk APK; start the download again")
        }
        val stagedUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", stagedApk)
        }.getOrNull() ?: return MagiskActionResult(false, "ThorTools could not prepare the Magisk installer")
        return if (runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(stagedUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)) {
            MagiskActionResult(true, "Opened the completed Magisk installer")
        } else {
            MagiskActionResult(false, "The completed Magisk download could not be opened; start it again")
        }
    }

    private fun stageDownloadedApk(context: Context, sourceUri: Uri): File? {
        val stagedApk = File(context.cacheDir, MAGISK_DOWNLOAD_FILE_NAME)
        val copied = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileUtils.copyInputStream(input, stagedApk.absolutePath)
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            stagedApk.delete()
            return null
        }
        val packageName = runCatching {
            context.packageManager.getPackageArchiveInfo(stagedApk.absolutePath, 0)?.packageName
        }.getOrNull()
        if (!isExpectedMagiskPackage(packageName)) {
            stagedApk.delete()
            return null
        }
        return stagedApk
    }

    internal fun isExpectedMagiskPackage(packageName: String?): Boolean = packageName == MAGISK_PACKAGE_NAME

    fun getMagiskPath(context: Context): String {
        if (hasRootMagiskUtils(context)) return MAGISK_DIR
        if (!hasMagiskPackage(context)) return ""
        val localDirectory = File(FileUtils.getPathAppFiles(context, "/magisk"))
        if (!hasLocalMagiskUtils(localDirectory)) {
            RootUtils.runRootCommand(context, "rm -rf \"${localDirectory.absolutePath}\"")
            installLocalMagiskUtils(context)
        }
        return localDirectory.absolutePath.takeIf { hasLocalMagiskUtils(localDirectory) } ?: ""
    }

    fun hasMagiskPackage(context: Context): Boolean = RootUtils.isPackageInstalled(context, MAGISK_PACKAGE_NAME)

    fun installLocalMagiskUtils(context: Context): Boolean {
        val destination = File(FileUtils.getPathAppFiles(context, "/magisk"))
        val sourceApk = File(getMagiskAppPath(context))
        if (!sourceApk.isFile) return false
        RootUtils.runRootCommand(context, "rm -rf \"${destination.absolutePath}\"")
        destination.mkdirs()
        val command = "unzip -o -q \"${sourceApk.absolutePath}\" -d \"${destination.absolutePath}/base\""
        if (!RootUtils.runRootAction(context, command)) {
            RootUtils.runRootCommand(context, "rm -rf \"${destination.absolutePath}\"")
            return false
        }
        val sourceRoot = listOf(
            "${destination.absolutePath}/base/lib/arm64-v8a",
            "${destination.absolutePath}/base/lib/arm64",
        ).firstOrNull { File(it).isDirectory } ?: run {
            RootUtils.runRootCommand(context, "rm -rf \"${destination.absolutePath}\"")
            return false
        }
        val binaries = listOf(
            "libbusybox.so" to "busybox",
            "libinit-ld.so" to "init-ld",
            "libmagisk.so" to "magisk",
            "libmagiskboot.so" to "magiskboot",
            "libmagiskinit.so" to "magiskinit",
            "libmagiskpolicy.so" to "magiskpolicy",
        )
        if (!binaries.all { (source, target) ->
                RootUtils.runRootAction(context, "cp -f \"$sourceRoot/$source\" \"${destination.absolutePath}/$target\"")
            }) {
            RootUtils.runRootCommand(context, "rm -rf \"${destination.absolutePath}\"")
            return false
        }
        val supportFiles = listOf(
            "assets/boot_patch.sh" to "boot_patch.sh",
            "assets/util_functions.sh" to "util_functions.sh",
            "assets/stub.apk" to "stub.apk",
        )
        if (!supportFiles.all { (source, target) ->
                RootUtils.runRootAction(context, "cp -f \"${destination.absolutePath}/base/$source\" \"${destination.absolutePath}/$target\"")
            }) {
            RootUtils.runRootCommand(context, "rm -rf \"${destination.absolutePath}\"")
            return false
        }
        if (!RootUtils.runRootAction(context, "chmod a+x \"${destination.absolutePath}\"/*")) {
            RootUtils.runRootCommand(context, "rm -rf \"${destination.absolutePath}\"")
            return false
        }
        return hasLocalMagiskUtils(destination)
    }

    private fun hasRootMagiskUtils(context: Context): Boolean =
        MAGISK_REQUIRED_FILES.all { name ->
            RootUtils.checkFileNonEmptyRoot(context, "$MAGISK_DIR/$name")
        }

    private fun hasLocalMagiskUtils(directory: File): Boolean =
        MAGISK_REQUIRED_FILES.all { name ->
            val file = File(directory, name)
            file.isFile && file.length() > 0L
        }
}
