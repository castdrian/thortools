package dev.adrian.thortools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.adrian.thortools.utils.RootUtils
import dev.adrian.thortools.utils.SystemUtils
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        thread(name = "ThorToolsBoot") {
            try {
                if (!DeviceProfile.detect(SystemUtils.getDeviceProperties()).isThor) return@thread
                if (!awaitRootService({ RootUtils.isPServerResponsive() }, ::sleepForRootService)) {
                    val prefs = AppSettings.getSharedPrefs(context)
                    if (AppSettings.hasModuleSettings(prefs)) AppSettings.setModuleSyncState(prefs, ThorModuleSyncState.FAILED)
                    if (AppSettings.hasBootOverrideSettings(prefs)) AppSettings.setBootOverrideState(prefs, ThorBootOverrideState.FAILED)
                    return@thread
                }
                val prefs = AppSettings.getSharedPrefs(context)
                val hasBootOverrides = AppSettings.hasBootOverrideSettings(prefs)
                if (hasBootOverrides) {
                    val applied = applyBootOverrides(
                        hasDpiOverride = AppSettings.hasDpiOverride(prefs),
                        dpi = runCatching { AppSettings.getDpi(prefs) }.getOrDefault(Int.MIN_VALUE),
                        hasAnimationSpeedOverride = AppSettings.hasAnimationSpeedOverride(prefs),
                        animationSpeed = runCatching { AppSettings.getAnimationSpeed(prefs) }.getOrDefault(Float.NaN),
                        setDpi = { value -> RootUtils.setDpi(context, value) },
                        setAnimationSpeed = { value -> RootUtils.setAnimationSpeed(context, value) },
                    )
                    AppSettings.setBootOverrideState(
                        prefs,
                        if (applied) ThorBootOverrideState.APPLIED else ThorBootOverrideState.FAILED,
                    )
                } else {
                    AppSettings.setBootOverrideState(prefs, ThorBootOverrideState.NOT_CONFIGURED)
                }
                if (AppSettings.hasModuleSettings(prefs) || RootUtils.isThorToolsMagiskModuleInstalled(context)) {
                    AppSettings.save(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal fun applyBootOverrides(
    hasDpiOverride: Boolean,
    dpi: Int,
    hasAnimationSpeedOverride: Boolean,
    animationSpeed: Float,
    setDpi: (Int) -> Boolean,
    setAnimationSpeed: (Float) -> Boolean,
): Boolean {
    val dpiApplied = !hasDpiOverride || (dpi in AppSettings.DPI_MIN..AppSettings.DPI_MAX && setDpi(dpi))
    val animationApplied = !hasAnimationSpeedOverride ||
        (animationSpeed.isFinite() && animationSpeed in 0f..1f && setAnimationSpeed(animationSpeed))
    return dpiApplied && animationApplied
}

internal fun awaitRootService(
    isAvailable: () -> Boolean,
    wait: (Long) -> Boolean,
): Boolean {
    repeat(12) { attempt ->
        if (isAvailable()) return true
        if (attempt < 11 && !wait(400L)) return false
    }
    return false
}

private fun sleepForRootService(delayMillis: Long): Boolean = try {
    Thread.sleep(delayMillis)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}
