package dev.adrian.thortools.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import dev.adrian.thortools.DeviceProfile
import dev.adrian.thortools.DeviceProperties
import java.io.BufferedReader
import java.io.InputStreamReader

class RuntimeExecResult(
    var stdout: String = "",
    var stderr: String = "",
    var exitCode: Int? = null,
) {
    val success: Boolean
        get() = exitCode == 0
}

object SystemUtils {
    fun getDeviceProperties(propertyReader: (String) -> String = { getProp(it) }): DeviceProperties {
        fun firstValue(vararg names: String): String = names
            .asSequence()
            .map(propertyReader)
            .firstOrNull(String::isNotBlank)
            .orEmpty()

        val slot = listOf("ro.boot.slot_suffix", "ro.boot.slot")
            .asSequence()
            .map { propertyName -> runCatching { propertyReader(propertyName) }.getOrNull().orEmpty() }
            .map(DeviceProfile::normalizeSlot)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        return DeviceProperties(
            manufacturer = firstValue("ro.product.manufacturer", "ro.product.vendor.manufacturer", "ro.product.odm.manufacturer", "ro.product.product.manufacturer", "ro.product.system.manufacturer"),
            brand = firstValue("ro.product.brand", "ro.product.vendor.brand", "ro.product.odm.brand", "ro.product.product.brand", "ro.product.system.brand"),
            model = firstValue("ro.product.model", "ro.product.vendor.model", "ro.product.odm.model", "ro.product.product.model", "ro.product.system.model"),
            device = firstValue("ro.product.device", "ro.product.vendor.device", "ro.product.odm.device", "ro.product.product.device", "ro.product.system.device", "ro.build.product"),
            product = firstValue("ro.product.name", "ro.product.vendor.name", "ro.product.odm.name", "ro.product.product.name", "ro.product.system.name"),
            systemDevice = firstValue("ro.product.system.device"),
            systemName = firstValue("ro.product.system.name"),
            buildProduct = firstValue("ro.build.product"),
            board = firstValue("ro.product.board", "ro.product.vendor.board", "ro.product.odm.board", "ro.product.product.board", "ro.board.platform"),
            hardware = firstValue("ro.hardware", "ro.boot.hardware", "ro.hardware.chipname"),
            soc = firstValue("ro.soc.model", "ro.soc.manufacturer", "ro.board.platform", "ro.boot.hardware"),
            platform = firstValue("ro.fota.platform", "ro.product.cpu.abi", "ro.product.cpu.abilist"),
            firmware = firstValue("ro.fota.version", "ro.build.version.incremental", "ro.build.version.release"),
            buildId = firstValue("ro.build.id", "ro.vendor.build.id"),
            buildDisplayId = firstValue("ro.build.display.id", "ro.vendor.build.display.id"),
            buildDate = firstValue("ro.build.date", "ro.vendor.build.date"),
            buildFingerprint = firstValue("ro.build.fingerprint", "ro.vendor.build.fingerprint", "ro.odm.build.fingerprint"),
            serial = firstValue("ro.serialno", "ro.boot.serialno"),
            slot = slot,
            flashLocked = firstValue("ro.boot.flash.locked"),
            bootloaderDeviceState = firstValue("ro.boot.vbmeta.device_state"),
            verifiedBootState = firstValue("ro.boot.verifiedbootstate"),
        )
    }

    fun getKernelVersion(context: Context): String =
        RootUtils.runRootCommand(context, "cat /proc/version") ?: runCommand(arrayOf("cat", "/proc/version")).stdout

    fun getPropBuildDate(): String = getProp("ro.build.date")
    fun getPropBuildDisplayId(): String = getProp("ro.build.display.id")
    fun getPropBuildId(): String = getProp("ro.build.id")
    fun getPropDeviceModel(): String = getDeviceProperties().model
    fun getPropFotaPlatform(): String = getProp("ro.fota.platform")
    fun getPropLcdDensity(): Int = getProp("ro.sf.lcd_density").toIntOrNull() ?: 0
    fun getPropFirmwareVersion(): String = getDeviceProperties().firmware
    fun getPropSerialNumber(): String = getProp("ro.serialno")
    fun getPropSlot(): String = getDeviceProperties().slot
    fun getPropVolumeSteps(): Int = getProp("ro.config.media_vol_steps").toIntOrNull() ?: 15

    fun getProp(propName: String): String = runCommand(arrayOf("getprop", propName)).stdout

    fun getSystemBatteryCapacity(context: Context): Int =
        (RootUtils.runRootCommand(context, "cat /sys/class/power_supply/battery/charge_full")?.toIntOrNull() ?: 0) / 1000

    fun getSystemBatteryChargeCounter(context: Context): Int =
        (RootUtils.runRootCommand(context, "cat /sys/class/power_supply/battery/charge_counter")?.toIntOrNull() ?: 0) / 1000

    fun getSystemBatteryCapacityFull(context: Context): Int =
        (RootUtils.runRootCommand(context, "cat /sys/class/power_supply/battery/charge_full_design")?.toIntOrNull() ?: 0) / 1000

    fun getSystemBatteryHealthPercent(context: Context): Int {
        val current = getSystemBatteryCapacity(context)
        val maximum = getSystemBatteryCapacityFull(context)
        return if (maximum > 0) (current * 100 / maximum).coerceIn(0, 100) else 0
    }

    fun getSystemBatteryHealthLabel(context: Context): String =
        RootUtils.runRootCommand(context, "cat /sys/class/power_supply/battery/health") ?: "unknown"

    fun getBatteryPercent(context: Context): Int? {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    }

    fun getBootMarker(context: Context): String =
        runCommand(arrayOf("cat", "/proc/sys/kernel/random/boot_id")).stdout
            .trim()
            .takeIf(String::isNotBlank)
            ?: Settings.Global.getString(context.contentResolver, Settings.Global.BOOT_COUNT)
                ?.takeIf(String::isNotBlank)
            ?: (System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString()

    fun runCommand(command: Array<String>): RuntimeExecResult {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(command)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()
            RuntimeExecResult(stdout, stderr, exitCode)
        } catch (_: Throwable) {
            RuntimeExecResult()
        } finally {
            process?.destroy()
        }
    }
}
