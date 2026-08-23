package dev.adrian.thortools.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.io.IOException

object FileUtils {
    private const val TAG = "FileUtils"

    fun checkFileExists(path: String): Boolean {
        val file = File(path)
        return file.exists()
    }

    fun copyAsset(context: Context, assetFile: String, dstFile: String): Boolean {
        return try {
            val apkFile = File(dstFile)
            context.assets.open(assetFile).use { input ->
                if (!copyInputStream(input, apkFile.absolutePath)) return false
            }
            apkFile.isFile
        } catch (e: IOException) {
            Log.e(TAG, "Error copying asset to Download folder", e)
            false
        }
    }

    fun copyFile(src: String, dst: String) {
        val srcFile = File(src)
        if (!srcFile.exists())
            return

        val dstFile = File(dst)
        srcFile.copyTo(dstFile, true)
    }

    fun deleteFile(path: String): Boolean {
        if (!checkFileExists(path))
            return false

        val file = File(path)
        return file.delete()
    }

    fun saveFile(path: String, content: String): Boolean {
        return writeFile(path) { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
    }

    fun copyInputStream(input: InputStream, path: String): Boolean =
        writeFile(path) { output -> input.copyTo(output) }

    private fun writeFile(path: String, writer: (FileOutputStream) -> Unit): Boolean {
        val file = File(path)
        val parent = file.absoluteFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temporary).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            temporary.renameTo(file) && file.isFile
        } catch (_: Exception) {
            false
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun getPathDownload(relativePath: String? = null): String {
        return "/storage/emulated/0/Download${relativePath ?: ""}"
    }

    fun getPathBackup(context: Context, relativePath: String? = null): String {
        return appendPath(context.getExternalFilesDir(null), relativePath)
    }

    fun isBackupDestinationWritable(context: Context): Boolean {
        val directory = context.getExternalFilesDir(null) ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        if (!directory.isDirectory || !directory.canWrite()) return false
        val downloadPath = getPathDownload()
        val quotedDownloadPath = downloadPath.replace("'", "'\\''")
        return RootUtils.runRootCommand(
            context,
            "mkdir -p '$quotedDownloadPath' && [ -d '$quotedDownloadPath' ] && [ -w '$quotedDownloadPath' ] && printf '%s' 1 || printf '%s' 0",
        ) == "1"
    }

    fun getPathWorking(context: Context, relativePath: String? = null): String {
        return appendPath(context.getExternalFilesDir(null), relativePath)
    }

    fun getPathAppFiles(context: Context, relativePath: String? = null): String {
        return appendPath(File(context.filesDir, "app"), relativePath)
    }

    fun getPathSupportFiles(context: Context, relativePath: String? = null): String {
        return appendPath(File(context.filesDir, "app/support"), relativePath)
    }

    private fun appendPath(base: File?, relativePath: String?): String {
        if (base == null) return ""
        val relative = relativePath?.trimStart('/') ?: return base.absolutePath
        return File(base, relative).absolutePath
    }
}
