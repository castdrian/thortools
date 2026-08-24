package dev.adrian.thortools.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.adrian.thortools.DeviceProfile
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import kotlin.math.abs

fun copyAssetFolderToFilesDir(context: Context, assetFolderPath: String) {
    val assetManager = context.assets
    val assetFiles = runCatching { assetManager.list(assetFolderPath) }.getOrNull() ?: return
    if (assetFiles.isEmpty()) {
        File(context.filesDir, assetFolderPath).mkdirs()
        return
    }
    assetFiles.forEach { assetFileName ->
        val fullAssetPath = if (assetFolderPath.isEmpty()) assetFileName else "$assetFolderPath/$assetFileName"
        try {
            assetManager.open(fullAssetPath).use { input ->
                val outputFile = File(context.filesDir, fullAssetPath)
                if (FileUtils.copyInputStream(input, outputFile.absolutePath)) {
                    outputFile.setReadable(true)
                    outputFile.setExecutable(true)
                }
            }
        } catch (_: FileNotFoundException) {
            copyAssetFolderToFilesDir(context, fullAssetPath)
        } catch (_: IOException) {
        }
    }
}

object RootUtils {
    const val MODULE_DIR = "/data/adb/modules"
    private const val TAG = "RootUtils"
    private const val ASSET_SUBFOLDER = "app"
    private val requiredSupportScripts = listOf(
        "partition_path.sh",
        "boot.backup.sh",
        "boot.patch.sh",
        "boot.flash.sh",
        "boot.restore.sh",
        "init_boot.backup.sh",
        "init_boot.patch.sh",
        "init_boot.flash.sh",
        "init_boot.restore.sh",
    )
    private val animationSettingKeys = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    val isDeviceRooted: Boolean
        get() = checkRootMethod2() || checkRootMethod3()

    private fun checkRootMethod2(): Boolean = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/product/bin/su",
    ).any { path -> File(path).exists() }

    private fun checkRootMethod3(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val result = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() != null }
            process.waitFor()
            result
        } catch (_: Throwable) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun hasPServer(): Boolean = RootExec().pServerAvailable

    fun isPServerResponsive(): Boolean =
        RootExec().executeAsRoot("printf '%s' 1").getOrNull() == "1"

    fun isDeviceRooted(context: Context, rootServiceAvailable: Boolean): Boolean {
        val heuristicRooted = isDeviceRooted
        if (!rootServiceAvailable) return heuristicRooted
        val activeRoot = runRootCommand(
            context,
            "if [ -x /data/adb/magisk/magisk ] || [ -x /data/adb/ksu/bin/ksud ] || [ -x /data/adb/ap/bin/apd ]; then printf '%s' 1; else printf '%s' 0; fi",
        )
        return resolveRootState(rootServiceAvailable, activeRoot == "1", heuristicRooted)
    }

    fun runRootCommand(context: Context, command: String): String? {
        val result = RootExec().executeAsRoot(command)
        return result.getOrNull().also { Log.d(TAG, "root command completed: ${result.isSuccess}") }
    }

    fun runRootAction(context: Context, command: String): Boolean =
        RootExec().executeAsRoot("$command >/dev/null 2>&1; printf '%s' \$?").getOrNull() == "0"

    fun runRootScript(
        context: Context,
        script: String,
        arguments: List<String> = emptyList(),
        clearLog: Boolean = true,
    ): String? {
        if (!script.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        val filesPath = File(context.filesDir, ASSET_SUBFOLDER).absolutePath
        val logPath = getLogFile(context)?.absolutePath ?: return null
        val workingPath = context.getExternalFilesDir(null)?.absolutePath ?: return null
        val scriptPath = "$filesPath/support/subscripts/$script"
        val commandArguments = arguments.joinToString(" ") { shellQuote(it) }
        val logPreparation = if (clearLog) ": > ${shellQuote(logPath)} && " else ""
        val command = "$logPreparation" +
            "THORTOOLS_WORKING_PATH=${shellQuote(workingPath)} THORTOOLS_LOG_PATH=${shellQuote(logPath)} " +
            "sh ${shellQuote(scriptPath)} $commandArguments >> ${shellQuote(logPath)} 2>&1; printf '%s' $?"
        return RootExec().executeAsRoot(command).getOrNull()
    }

    fun areSupportFilesReady(context: Context): Boolean = areSupportFilesReady(
        File(context.filesDir, "$ASSET_SUBFOLDER/support"),
    )

    internal fun areSupportFilesReady(supportDirectory: File): Boolean {
        val scriptsDirectory = File(supportDirectory, "subscripts")
        return requiredSupportScripts.all { name ->
            val file = File(scriptsDirectory, name)
            file.isFile && file.length() > 0L
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun checkFileExistsRoot(context: Context, path: String): Boolean =
        runRootCommand(context, "[ -e ${shellQuote(path)} ] && printf '%s' 1 || printf '%s' 0") == "1"

    fun checkFileNonEmptyRoot(context: Context, path: String): Boolean =
        runRootCommand(context, "[ -s ${shellQuote(path)} ] && printf '%s' 1 || printf '%s' 0") == "1"

    fun sha256FileRoot(context: Context, path: String): String? =
        runRootCommand(
            context,
            "if [ -s ${shellQuote(path)} ]; then sha256sum ${shellQuote(path)}; fi",
        )?.trim()?.substringBefore(' ')?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }?.lowercase()

    fun reboot(context: Context): Boolean =
        RootExec().executeAsRoot("reboot >/dev/null 2>&1 & printf '%s' 0").getOrNull() == "0"

    fun rootCopy(context: Context, from: String, to: String): Boolean =
        File(from).exists() && runRootAction(context, "cp -afv ${shellQuote(from)} ${shellQuote(to)}")

    fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun findPartition(context: Context, partitionName: String, slot: String): String? {
        val normalizedSlot = DeviceProfile.normalizeSlot(slot)
        if (normalizedSlot.isBlank() || !partitionName.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        val name = "$partitionName$normalizedSlot"
        val command = "for base in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name /dev/block/platform/*/*/*/by-name; do path=\"\$base/$name\"; if [ -e \"\$path\" ]; then printf '%s' \"\$path\"; break; fi; done"
        return runRootCommand(context, command)?.trim()?.ifBlank { null }
    }

    fun hasPartition(context: Context, partitionName: String, slot: String): Boolean =
        isPServerResponsive() && findPartition(context, partitionName, slot) != null

    fun installThorToolsMagiskModule(context: Context): Boolean {
        val modulePath = "$MODULE_DIR/thortools"
        val sourcePath = "${context.filesDir}/app/support/magisk/thortools"
        val stagingPath = "$MODULE_DIR/.thortools-staging"
        val previousPath = "$MODULE_DIR/.thortools-previous"
        val command = """
            (
            if [ -e ${shellQuote(previousPath)} ]; then
                if [ -e ${shellQuote(modulePath)} ]; then
                    rm -rf ${shellQuote(previousPath)} || exit 1
                else
                    mv ${shellQuote(previousPath)} ${shellQuote(modulePath)} || exit 1
                fi
            fi
            rm -rf ${shellQuote(stagingPath)} || exit 1
            mkdir -p ${shellQuote(stagingPath)} || exit 1
            cp -fR ${shellQuote("$sourcePath/.")} ${shellQuote("$stagingPath/")} || {
                rm -rf ${shellQuote(stagingPath)}
                exit 1
            }
            [ -s ${shellQuote("$stagingPath/module.prop")} ] || {
                rm -rf ${shellQuote(stagingPath)}
                exit 1
            }
            if [ -e ${shellQuote(modulePath)} ]; then
                mv ${shellQuote(modulePath)} ${shellQuote(previousPath)} || {
                    rm -rf ${shellQuote(stagingPath)}
                    exit 1
                }
            fi
            if mv ${shellQuote(stagingPath)} ${shellQuote(modulePath)}; then
                rm -rf ${shellQuote(previousPath)} || exit 1
                exit 0
            fi
            rm -rf ${shellQuote(modulePath)}
            if [ -e ${shellQuote(previousPath)} ]; then
                mv ${shellQuote(previousPath)} ${shellQuote(modulePath)} || exit 1
            fi
            exit 1
            )
        """.trimIndent()
        return runRootAction(context, command) && isThorToolsMagiskModuleInstalled(context)
    }

    fun isThorToolsMagiskModuleInstalled(context: Context): Boolean =
        runRootCommand(
            context,
            "[ -s ${shellQuote("$MODULE_DIR/thortools/module.prop")} ] && [ -e ${shellQuote("$MODULE_DIR/thortools/system.prop")} ] && printf '%s' 1 || printf '%s' 0",
        ) == "1"

    fun startActivityRoot(context: Context, activity: String): Boolean = runRootAction(context, "am start -n $activity")

    fun readAnimationSpeed(context: Context): Float? {
        val values = animationSettingKeys.map { key ->
            parseAnimationSpeedOutput(runRootCommand(context, "settings get global $key") ?: return null)
                ?: return null
        }
        return resolveAnimationSpeed(values)
    }

    fun readDpi(context: Context): Int? = runRootCommand(context, "wm density")?.let(::parseDpiOutput)

    fun setAnimationSpeed(context: Context, animationSpeed: Float): Boolean {
        if (!animationSpeed.isFinite() || animationSpeed !in 0f..1f) return false
        val changed = animationSettingKeys.all { key ->
            runRootAction(context, "settings put global $key $animationSpeed")
        }
        return changed && readAnimationSpeed(context)?.let { actual ->
            abs(actual - animationSpeed) < ANIMATION_READBACK_TOLERANCE
        } == true
    }

    fun setDpi(context: Context, dpi: Int): Boolean =
        dpi > 0 && runRootAction(context, "wm density $dpi") && readDpi(context) == dpi

    fun resetDpi(context: Context): Boolean = runRootAction(context, "resetprop -p ro.sf.lcd_density")

    internal fun parseAnimationSpeedOutput(output: String): Float? = output
        .trim()
        .takeUnless { it.equals("null", ignoreCase = true) }
        ?.toFloatOrNull()
        ?.takeIf(Float::isFinite)

    internal fun resolveAnimationSpeed(values: List<Float>): Float? {
        val value = values.firstOrNull() ?: return null
        return value.takeIf { values.all { other -> abs(other - value) < ANIMATION_READBACK_TOLERANCE } }
    }

    internal fun parseDpiOutput(output: String): Int? {
        var physical: Int? = null
        var override: Int? = null
        DPI_OUTPUT_PATTERN.findAll(output).forEach { match ->
            val value = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (match.groupValues[1].equals("Override", ignoreCase = true)) {
                override = value
            } else {
                physical = value
            }
        }
        return override ?: physical
    }

    private const val ANIMATION_READBACK_TOLERANCE = 0.001f
    private val DPI_OUTPUT_PATTERN = Regex("(?im)^\\s*(Override|Physical) density:\\s*(\\d+)\\b")
}

internal fun resolveRootState(
    rootServiceAvailable: Boolean,
    serviceRooted: Boolean,
    heuristicRooted: Boolean,
): Boolean = if (rootServiceAvailable) serviceRooted else heuristicRooted
