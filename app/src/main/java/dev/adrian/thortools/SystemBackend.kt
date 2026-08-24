package dev.adrian.thortools

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.adrian.thortools.utils.MagiskUtil
import dev.adrian.thortools.utils.PatchUtils
import dev.adrian.thortools.utils.RootUtils
import dev.adrian.thortools.utils.SystemUtils
import dev.adrian.thortools.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

enum class ThorOperation {
    REFRESH,
    INSTALL_MAGISK,
    BACKUP,
    PATCH,
    FLASH,
    RESTORE,
    CLEAR_CACHE,
    REBOOT,
    SET_DPI,
    SET_ANIMATION,
    SET_VOLUME_STEPS,
    SET_BOOT_ANIMATION,
}

private val ThorOperation.requiresThor: Boolean
    get() = this != ThorOperation.REFRESH

private val ThorOperation.requiresRootService: Boolean
    get() = this !in setOf(ThorOperation.REFRESH, ThorOperation.INSTALL_MAGISK, ThorOperation.CLEAR_CACHE)

private val ThorOperation.requiresRebootAfterWrite: Boolean
    get() = this in setOf(
        ThorOperation.FLASH,
        ThorOperation.RESTORE,
        ThorOperation.SET_VOLUME_STEPS,
        ThorOperation.SET_BOOT_ANIMATION,
        ThorOperation.REBOOT,
    )

private val ThorOperation.cancellationSafe: Boolean
    get() = this in setOf(
        ThorOperation.INSTALL_MAGISK,
        ThorOperation.CLEAR_CACHE,
        ThorOperation.SET_DPI,
        ThorOperation.SET_ANIMATION,
        ThorOperation.SET_VOLUME_STEPS,
        ThorOperation.SET_BOOT_ANIMATION,
    )

enum class OperationStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILURE,
    INTERRUPTED,
}

data class OperationState(
    val operation: ThorOperation? = null,
    val status: OperationStatus = OperationStatus.IDLE,
    val message: String = "Ready",
    val rebootRequired: Boolean = false,
)

data class ThorDisplayPanel(
    val displayId: Int = -1,
    val widthPixels: Int = 0,
    val heightPixels: Int = 0,
    val refreshRateHz: Float = 0f,
    val rotation: Int = DeviceProfile.THOR_DISPLAY_ROTATION,
) {
    val present: Boolean
        get() = displayId >= 0

    val geometryLabel: String
        get() = if (present) "$widthPixels x $heightPixels" else "Unavailable"

    val refreshRateLabel: String
        get() = if (present && refreshRateHz > 0f) "${refreshRateHz.roundToInt()} Hz" else "Unavailable"

    val orientationLabel: String
        get() = when (rotation) {
            0 -> "Upright"
            1 -> "Rotated 90 deg"
            2 -> "Upside down"
            3 -> "Rotated 270 deg"
            else -> "Unknown"
    }
}

enum class ThorDisplayMode {
    DUAL,
    UPPER_ONLY,
    LOWER_ONLY,
    UNAVAILABLE;

    val label: String
        get() = when (this) {
            DUAL -> "Dual-display workflow ready"
            UPPER_ONLY -> "Upper display only"
            LOWER_ONLY -> "Lower display only"
            UNAVAILABLE -> "No Thor display detected"
        }
}

data class ThorDisplayDiagnostics(
    val upper: ThorDisplayPanel = ThorDisplayPanel(),
    val lower: ThorDisplayPanel = ThorDisplayPanel(),
    val defaultDisplayId: Int = -1,
) {
    val dualDisplayReady: Boolean
        get() = upper.present &&
            lower.present &&
            upper.displayId != lower.displayId &&
            DeviceProfile.isThorUpperDisplay(upper.widthPixels, upper.heightPixels, upper.rotation) &&
            DeviceProfile.isThorLowerDisplay(lower.widthPixels, lower.heightPixels, lower.rotation)

    val mode: ThorDisplayMode
        get() = when {
            dualDisplayReady -> ThorDisplayMode.DUAL
            lower.present && defaultDisplayId == lower.displayId -> ThorDisplayMode.LOWER_ONLY
            upper.present && defaultDisplayId == upper.displayId -> ThorDisplayMode.UPPER_ONLY
            upper.present -> ThorDisplayMode.UPPER_ONLY
            lower.present -> ThorDisplayMode.LOWER_ONLY
            else -> ThorDisplayMode.UNAVAILABLE
        }
}

data class ThorSnapshot(
    val profile: ThorDeviceProfile,
    val batteryPercent: Int,
    val lcdDensity: Int,
    val volumeSteps: Int,
    val animationSpeed: Float,
    val activeSlot: String,
    val kernelVersion: String,
    val rootServiceAvailable: Boolean,
    val rooted: Boolean,
    val magiskInstalled: Boolean,
    val initBootAvailable: Boolean,
    val bootAvailable: Boolean,
    val backupDestinationWritable: Boolean,
    val backupAvailable: Boolean,
    val stockRestoreAvailable: Boolean = false,
    val patchedBackupAvailable: Boolean,
    val patchedCacheAvailable: Boolean = false,
    val availableBootSlots: Set<String> = emptySet(),
    val availablePartitionsBySlot: Map<String, Set<String>> = emptyMap(),
    val stockBackupSlots: Set<String> = emptySet(),
    val stockRecoverySlots: Set<String> = emptySet(),
    val patchedBackupSlots: Set<String> = emptySet(),
    val operation: OperationState,
    val displayDiagnostics: ThorDisplayDiagnostics = ThorDisplayDiagnostics(),
    val stateReadHealthy: Boolean = true,
    val moduleSyncState: ThorModuleSyncState = ThorModuleSyncState.NOT_CONFIGURED,
    val bootOverrideState: ThorBootOverrideState = ThorBootOverrideState.NOT_CONFIGURED,
) {
    val recoveryPartition: String
        get() = when {
            initBootAvailable -> "init_boot"
            bootAvailable -> "boot"
            else -> "Unavailable"
        }

    val stockBackupCoverageReady: Boolean
        get() = availableBootSlots.isNotEmpty() && availableBootSlots.all(stockBackupSlots::contains)

    val stockRecoveryCoverageReady: Boolean
        get() = availableBootSlots.isNotEmpty() && availableBootSlots.all { slot ->
            slot in stockBackupSlots || slot in stockRecoverySlots
        }

    val partitionLayoutLabel: String
        get() = availablePartitionsBySlot.entries
            .sortedBy { it.key }
            .joinToString("; ") { (slot, partitions) ->
                "$slot: ${partitions.sorted().joinToString(" + ")}"
            }
            .ifBlank { "Unavailable" }

    val capabilityRows: List<Pair<String, Boolean>>
        get() = listOf(
            "Thor device" to profile.isThor,
            "System state" to stateReadHealthy,
            "Root service" to supportsThorCapability(ThorCapability.ROOT_SERVICE),
            "Root access" to supportsThorCapability(ThorCapability.ROOTED),
            "Magisk" to supportsThorCapability(ThorCapability.MAGISK),
            "Active slot" to supportsThorCapability(ThorCapability.ACTIVE_SLOT),
            "init_boot partition" to supportsThorCapability(ThorCapability.INIT_BOOT_PARTITION),
            "boot partition" to supportsThorCapability(ThorCapability.BOOT_PARTITION),
            "Battery state" to supportsThorCapability(ThorCapability.BATTERY_STATE),
            "Backup destination" to supportsThorCapability(ThorCapability.BACKUP_DESTINATION),
            "Support files" to supportsThorCapability(ThorCapability.SUPPORT_FILES),
        )

    private fun supportsThorCapability(capability: ThorCapability): Boolean =
        profile.isThor && profile.supports(capability)

    companion object {
        fun loading(operation: OperationState): ThorSnapshot = ThorSnapshot(
            profile = DeviceProfile.detect(DeviceProperties()),
            batteryPercent = 0,
            lcdDensity = 0,
            volumeSteps = AppSettings.VOLUME_STEPS_DEFAULT,
            animationSpeed = AppSettings.ANIMATION_SPEED_DEFAULT,
            activeSlot = "unknown",
            kernelVersion = "",
            rootServiceAvailable = false,
            rooted = false,
            magiskInstalled = false,
            initBootAvailable = false,
            bootAvailable = false,
            backupDestinationWritable = false,
            backupAvailable = false,
            stockRestoreAvailable = false,
            patchedBackupAvailable = false,
            patchedCacheAvailable = false,
            availableBootSlots = emptySet(),
            availablePartitionsBySlot = emptyMap(),
            stockBackupSlots = emptySet(),
            stockRecoverySlots = emptySet(),
            patchedBackupSlots = emptySet(),
            operation = operation,
            bootOverrideState = ThorBootOverrideState.NOT_CONFIGURED,
        )
    }
}

data class OperationResult(
    val success: Boolean,
    val message: String,
    val rebootRequired: Boolean = false,
)

private data class ModuleSettingRollback(
    val preferencesRestored: Boolean,
    val moduleStateRestored: Boolean,
)

private fun rollbackModuleSetting(context: Context, restore: () -> Boolean): ModuleSettingRollback {
    val preferencesRestored = restore()
    val moduleStateRestored = preferencesRestored && AppSettings.save(context)
    return ModuleSettingRollback(preferencesRestored, moduleStateRestored)
}

private fun pendingRebootState(context: Context): OperationState? {
    val prefs = AppSettings.getSharedPrefs(context)
    val name = prefs.getString(AppSettings.PENDING_REBOOT_OPERATION_KEY, null) ?: return null
    val marker = runCatching {
        prefs.getString(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY, null)
    }.getOrNull() ?: runCatching {
        prefs.getLong(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY, Long.MIN_VALUE).toString()
    }.getOrNull()
    if (marker != SystemUtils.getBootMarker(context)) {
        clearPendingReboot(context)
        return null
    }
    return OperationState(
        operation = runCatching { ThorOperation.valueOf(name) }.getOrNull(),
        status = prefs.getString(AppSettings.PENDING_REBOOT_STATUS_KEY, OperationStatus.SUCCESS.name)
            ?.let { value -> runCatching { OperationStatus.valueOf(value) }.getOrNull() }
            ?: OperationStatus.SUCCESS,
        message = prefs.getString(AppSettings.PENDING_REBOOT_MESSAGE_KEY, "Reboot required") ?: "Reboot required",
        rebootRequired = true,
    )
}

private fun hasPendingReboot(context: Context): Boolean = pendingRebootState(context) != null

private fun persistPendingReboot(context: Context, state: OperationState, bootMarker: String): Boolean =
    AppSettings.getSharedPrefs(context).edit()
        .putString(AppSettings.PENDING_REBOOT_OPERATION_KEY, state.operation?.name)
        .putString(AppSettings.PENDING_REBOOT_MESSAGE_KEY, state.message)
        .putString(AppSettings.PENDING_REBOOT_STATUS_KEY, state.status.name)
        .putString(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY, bootMarker)
        .commit()

private fun clearPendingReboot(context: Context) {
    AppSettings.getSharedPrefs(context).edit()
        .remove(AppSettings.PENDING_REBOOT_OPERATION_KEY)
        .remove(AppSettings.PENDING_REBOOT_MESSAGE_KEY)
        .remove(AppSettings.PENDING_REBOOT_STATUS_KEY)
        .remove(AppSettings.PENDING_REBOOT_BOOT_MARKER_KEY)
        .commit()
}

interface SystemBackend {
    fun snapshot(operation: OperationState = OperationState()): ThorSnapshot
    fun perform(operation: ThorOperation, argument: String? = null): OperationResult
}

object ThorOperationGuard {
    private val imageOperations = setOf(
        ThorOperation.BACKUP,
        ThorOperation.PATCH,
        ThorOperation.FLASH,
        ThorOperation.RESTORE,
    )
    private val supportFileOperations = imageOperations + setOf(
        ThorOperation.SET_VOLUME_STEPS,
        ThorOperation.SET_BOOT_ANIMATION,
    )

    fun canCancel(operation: ThorOperation?): Boolean = operation?.cancellationSafe == true

    fun validate(snapshot: ThorSnapshot, operation: ThorOperation): String? {
        if (operation.requiresThor && !snapshot.profile.isThor) return "Only an AYN Thor can be modified"
        if (operation != ThorOperation.REFRESH && !snapshot.stateReadHealthy) {
            return "Thor system state is unavailable; refresh before changing the Thor"
        }
        if (operation != ThorOperation.REFRESH && snapshot.operation.status == OperationStatus.RUNNING) {
            return "Another Thor operation is already in progress"
        }
        if (operation != ThorOperation.REFRESH && snapshot.operation.status == OperationStatus.INTERRUPTED) {
            return "Acknowledge the Thor recovery record before starting another operation"
        }
        if (snapshot.operation.rebootRequired && operation !in setOf(ThorOperation.REFRESH, ThorOperation.REBOOT)) {
            return "Reboot the Thor before starting another operation"
        }
        if (snapshot.operation.rebootRequired &&
            operation == ThorOperation.REBOOT &&
            snapshot.operation.operation == ThorOperation.REBOOT &&
            snapshot.operation.status == OperationStatus.SUCCESS
        ) {
            return "The Thor reboot has already been requested; wait for it to restart"
        }
        if (operation.requiresRootService && !snapshot.rootServiceAvailable) {
            return "The Thor privileged root service is unavailable"
        }
        if (operation in supportFileOperations && !snapshot.profile.supports(ThorCapability.SUPPORT_FILES)) {
            return "ThorTools support files are unavailable; restart the app before changing the Thor"
        }
        if (operation in imageOperations && !snapshot.profile.supports(ThorCapability.BATTERY_STATE)) {
            return "The Thor battery state is unavailable"
        }
        if (operation in imageOperations && snapshot.batteryPercent < 35) {
            return "Charge the Thor to at least 35% before image operations"
        }
        if (operation in imageOperations && snapshot.activeSlot !in setOf("_a", "_b")) {
            return "The active Thor slot could not be determined"
        }
        if (operation in imageOperations && snapshot.profile.properties.buildIdentity.isBlank()) {
            return "The Thor build identity is unavailable"
        }
        if (operation in imageOperations && !snapshot.initBootAvailable && !snapshot.bootAvailable) {
            return "No supported Thor boot partition was found"
        }
        if (operation in imageOperations && !snapshot.backupDestinationWritable) {
            return "The Thor backup destination is not writable"
        }
        when (operation) {
            ThorOperation.BACKUP -> {
                if (snapshot.rooted) return "Capture stock backups before root is active"
            }
            ThorOperation.PATCH -> {
                if (snapshot.rooted) return "The Thor is already rooted; restore stock before preparing another patch"
                if (!snapshot.magiskInstalled) return "Install Magisk before preparing a root patch"
                if (!snapshot.backupAvailable) return "Create a verified ${snapshot.recoveryPartition} stock backup before patching"
                if (!snapshot.stockRecoveryCoverageReady) return "Create verified stock backups for every available Thor slot before patching"
            }
            ThorOperation.FLASH -> {
                if (snapshot.rooted) return "The Thor already reports root access; restore stock before flashing again"
                if (!snapshot.magiskInstalled) return "Install Magisk before flashing a root patch"
                if (!snapshot.stockRestoreAvailable) return "Keep a verified ${snapshot.recoveryPartition} stock backup before flashing a root patch"
                if (!snapshot.stockRecoveryCoverageReady) return "Create verified stock backups for every available Thor slot before flashing"
                if (!snapshot.patchedBackupAvailable) return "Prepare a Magisk-patched active-slot image first"
            }
            ThorOperation.RESTORE -> {
                if (!snapshot.stockRestoreAvailable) return "Create a verified ${snapshot.recoveryPartition} stock backup before restoring"
            }
            ThorOperation.SET_VOLUME_STEPS, ThorOperation.SET_BOOT_ANIMATION -> {
                if (!snapshot.magiskInstalled || !snapshot.rooted) return "Root the Thor with Magisk before changing module settings"
            }
            else -> Unit
        }
        return null
    }
}

internal fun readThorDisplayDiagnostics(context: Context): ThorDisplayDiagnostics {
    val displayManager = context.getSystemService(DisplayManager::class.java) ?: return ThorDisplayDiagnostics()
    val displays = runCatching { displayManager.displays.toList() }.getOrElse { return ThorDisplayDiagnostics() }
    val defaultDisplayId = displays.firstOrNull { display -> display.displayId == Display.DEFAULT_DISPLAY }?.displayId ?: -1
    val upper = displays.firstOrNull { display ->
        display.displayId == Display.DEFAULT_DISPLAY && display.isThorUpperDisplay()
    } ?: displays.firstOrNull { display -> display.isThorUpperDisplay() }
    val lower = displays.firstOrNull { display -> display.isThorLowerDisplay() }
    return ThorDisplayDiagnostics(
        upper = upper?.toThorDisplayPanel() ?: ThorDisplayPanel(),
        lower = lower?.toThorDisplayPanel() ?: ThorDisplayPanel(),
        defaultDisplayId = defaultDisplayId,
    )
}

private fun Display.isThorUpperDisplay(): Boolean =
    displayGeometryOrNull()?.let { (width, height, displayRotation) ->
        DeviceProfile.isThorUpperDisplay(width, height, displayRotation)
    } == true

private fun Display.isThorLowerDisplay(): Boolean =
    displayGeometryOrNull()?.let { (width, height, displayRotation) ->
        DeviceProfile.isThorLowerDisplay(width, height, displayRotation)
    } == true

private fun Display.toThorDisplayPanel(): ThorDisplayPanel? {
    val currentMode = modeOrNull() ?: return null
    val displayRotation = rotationOrNull() ?: return null
    return ThorDisplayPanel(
        displayId = displayId,
        widthPixels = currentMode.physicalWidth,
        heightPixels = currentMode.physicalHeight,
        refreshRateHz = currentMode.refreshRate,
        rotation = displayRotation,
    )
}

private fun Display.modeOrNull(): Display.Mode? = runCatching { mode }.getOrNull()

private fun Display.rotationOrNull(): Int? = runCatching { rotation }.getOrNull()

private fun Display.displayGeometryOrNull(): Triple<Int, Int, Int>? {
    val currentMode = modeOrNull() ?: return null
    val displayRotation = rotationOrNull() ?: return null
    return Triple(currentMode.physicalWidth, currentMode.physicalHeight, displayRotation)
}

class RealSystemBackend(private val context: Context) : SystemBackend {
    override fun snapshot(operation: OperationState): ThorSnapshot {
        val properties = SystemUtils.getDeviceProperties()
        val rootService = RootUtils.isPServerResponsive()
        val rooted = RootUtils.isDeviceRooted(context, rootService)
        val magisk = MagiskUtil.hasMagiskPackage(context)
        val battery = SystemUtils.getBatteryPercent(context)
        val prefs = AppSettings.getSharedPrefs(context)
        val liveDpi = rootService.takeIf { it }?.let { RootUtils.readDpi(context) }
        val liveAnimationSpeed = rootService.takeIf { it }?.let { RootUtils.readAnimationSpeed(context) }
        val availablePartitionsBySlot = if (rootService) {
            PatchUtils.availablePartitionsBySlot(context)
        } else {
            emptyMap()
        }
        val activePartitions = availablePartitionsBySlot[properties.slot].orEmpty()
        val initBoot = "init_boot" in activePartitions
        val boot = "boot" in activePartitions
        val backupDestination = FileUtils.isBackupDestinationWritable(context)
        val supportFiles = RootUtils.areSupportFilesReady(context)
        val availableBootSlots = availablePartitionsBySlot.keys
        val moduleInstalled = rootService.takeIf { it }?.let { RootUtils.isThorToolsMagiskModuleInstalled(context) }
        val moduleSyncState = AppSettings.getModuleSyncState(prefs, moduleInstalled, rootService)
        val capabilities = buildSet {
            if (rootService) add(ThorCapability.ROOT_SERVICE)
            if (rooted) add(ThorCapability.ROOTED)
            if (magisk) add(ThorCapability.MAGISK)
            if (properties.slot == "_a" || properties.slot == "_b") add(ThorCapability.ACTIVE_SLOT)
            if (initBoot) add(ThorCapability.INIT_BOOT_PARTITION)
            if (boot) add(ThorCapability.BOOT_PARTITION)
            if (battery != null) add(ThorCapability.BATTERY_STATE)
            if (backupDestination) add(ThorCapability.BACKUP_DESTINATION)
            if (supportFiles) add(ThorCapability.SUPPORT_FILES)
        }
        return ThorSnapshot(
            profile = DeviceProfile.detect(properties).copy(capabilities = capabilities),
            batteryPercent = battery ?: 0,
            lcdDensity = liveDpi ?: AppSettings.getDpi(prefs, SystemUtils.getPropLcdDensity()),
            volumeSteps = AppSettings.getVolumeSteps(prefs, SystemUtils.getPropVolumeSteps()),
            animationSpeed = liveAnimationSpeed ?: AppSettings.getAnimationSpeed(prefs),
            activeSlot = properties.slot.ifBlank { "unknown" },
            kernelVersion = SystemUtils.getKernelVersion(context),
            rootServiceAvailable = rootService,
            rooted = rooted,
            magiskInstalled = magisk,
            initBootAvailable = initBoot,
            bootAvailable = boot,
            backupDestinationWritable = backupDestination,
            backupAvailable = PatchUtils.checkActiveSlotBackupExists(context),
            stockRestoreAvailable = PatchUtils.checkActiveSlotRestoreExists(context),
            patchedBackupAvailable = PatchUtils.checkBootMagiskExists(context),
            patchedCacheAvailable = PatchUtils.hasPatchedCache(context),
            availableBootSlots = availableBootSlots,
            availablePartitionsBySlot = availablePartitionsBySlot,
            stockBackupSlots = PatchUtils.stockBackupSlots(context),
            stockRecoverySlots = PatchUtils.stockRecoverySlots(context),
            patchedBackupSlots = PatchUtils.patchedBackupSlots(context),
            operation = operation,
            displayDiagnostics = readThorDisplayDiagnostics(context),
            moduleSyncState = moduleSyncState,
            bootOverrideState = AppSettings.getBootOverrideState(prefs, rootService),
        )
    }

    override fun perform(operation: ThorOperation, argument: String?): OperationResult {
        val current = snapshot(pendingRebootState(context) ?: OperationState())
        ThorOperationGuard.validate(current, operation)?.let { return OperationResult(false, it) }
        return when (operation) {
            ThorOperation.REFRESH -> OperationResult(true, "System state refreshed")
            ThorOperation.INSTALL_MAGISK -> {
                val result = MagiskUtil.requestLatestDownloadOrInstall(context)
                OperationResult(result.success, result.message)
            }
            ThorOperation.BACKUP -> if (PatchUtils.backupBoot(context, current.activeSlot)) {
                OperationResult(true, "Available Thor boot partitions were backed up")
            } else {
                OperationResult(false, "Thor stock backup was not completed; verify every available slot and its Download copy")
            }
            ThorOperation.PATCH -> if (!current.magiskInstalled || !current.backupAvailable) {
                OperationResult(false, "Install Magisk and create a stock backup before patching")
            } else {
                val patched = PatchUtils.patchBoot(context, current.activeSlot)
                if (patched.isNotBlank()) OperationResult(true, "Created $patched")
                else OperationResult(false, "A verified current-build stock image is required before patching")
            }
            ThorOperation.FLASH -> if (!current.magiskInstalled || !current.patchedBackupAvailable) {
                OperationResult(false, "A Magisk-patched active-slot image is required")
            } else {
                val result = PatchUtils.flashBoot(context, current.activeSlot)
                if (result.success) {
                    OperationResult(true, "Root patch flashed; reboot required", rebootRequired = true)
                } else if (result.attempted) {
                    OperationResult(false, "The root patch write did not complete; reboot the Thor before retrying or restoring", rebootRequired = true)
                } else {
                    OperationResult(false, "No verified current-build active-slot patch is available")
                }
            }
            ThorOperation.RESTORE -> if (!current.stockRestoreAvailable) {
                OperationResult(false, "A stock active-slot backup is required")
            } else {
                val result = PatchUtils.restoreBoot(context, current.activeSlot)
                if (result.success) {
                    OperationResult(true, "Stock image restored; reboot required", rebootRequired = true)
                } else if (result.attempted) {
                    OperationResult(false, "The stock restore write did not complete; reboot the Thor before retrying", rebootRequired = true)
                } else {
                    OperationResult(false, "No verified current-build stock image is available")
                }
            }
            ThorOperation.CLEAR_CACHE -> {
                val cleared = PatchUtils.clearBootCache(context)
                OperationResult(cleared, if (cleared) "Patched images cleared; stock backups retained" else "Some patched images could not be removed")
            }
            ThorOperation.REBOOT -> {
                val rebooted = RootUtils.reboot(context)
                OperationResult(
                    rebooted,
                    if (rebooted) "Reboot requested; wait for the Thor to restart" else "The Thor reboot command failed",
                    rebootRequired = rebooted,
                )
            }
            ThorOperation.SET_DPI -> {
                val value = argument?.toIntOrNull() ?: return OperationResult(false, "Invalid DPI")
                if (value !in AppSettings.DPI_MIN..AppSettings.DPI_MAX) return OperationResult(false, "DPI must be between ${AppSettings.DPI_MIN} and ${AppSettings.DPI_MAX}")
                val prefs = AppSettings.getSharedPrefs(context)
                val previous = current.lcdDensity.takeIf { it in AppSettings.DPI_MIN..AppSettings.DPI_MAX }
                val changed = RootUtils.setDpi(context, value)
                if (!changed) {
                    OperationResult(false, "The Thor DPI command failed")
                } else if (AppSettings.setDpi(prefs, value)) {
                    AppSettings.setBootOverrideState(prefs, ThorBootOverrideState.APPLIED)
                    OperationResult(true, "DPI set to $value")
                } else {
                    val restored = previous == value || previous?.let { RootUtils.setDpi(context, it) } == true
                    OperationResult(
                        false,
                        if (restored) {
                            "DPI changed but could not be saved; previous density restored"
                        } else {
                            "DPI changed but could not be saved; verify the Thor density before reboot"
                        },
                    )
                }
            }
            ThorOperation.SET_ANIMATION -> {
                val value = argument?.toFloatOrNull() ?: return OperationResult(false, "Invalid animation speed")
                if (!value.isFinite() || value !in 0f..1f) return OperationResult(false, "Animation speed must be between 0x and 1x")
                val previous = current.animationSpeed.takeIf { it.isFinite() && it in 0f..1f } ?: AppSettings.ANIMATION_SPEED_DEFAULT
                val prefs = AppSettings.getSharedPrefs(context)
                val changed = RootUtils.setAnimationSpeed(context, value)
                if (!changed) {
                    val restored = RootUtils.setAnimationSpeed(context, previous)
                    OperationResult(
                        false,
                        if (restored) {
                            "The Thor animation command failed; previous speed restored"
                        } else {
                            "The Thor animation command failed; verify animation settings before reboot"
                        },
                    )
                } else if (AppSettings.setAnimationSpeed(prefs, value)) {
                    AppSettings.setBootOverrideState(prefs, ThorBootOverrideState.APPLIED)
                    OperationResult(true, "Animation speed set to ${value}x")
                } else {
                    val restored = RootUtils.setAnimationSpeed(context, previous)
                    OperationResult(
                        false,
                        if (restored) {
                            "Animation speed changed but could not be saved; previous speed restored"
                        } else {
                            "Animation speed changed but could not be saved; verify animation settings before reboot"
                        },
                    )
                }
            }
            ThorOperation.SET_VOLUME_STEPS -> {
                val value = argument?.toIntOrNull() ?: return OperationResult(false, "Invalid volume step count")
                if (value !in AppSettings.VOLUME_STEPS_MIN..AppSettings.VOLUME_STEPS_MAX) return OperationResult(false, "Volume steps must be between ${AppSettings.VOLUME_STEPS_MIN} and ${AppSettings.VOLUME_STEPS_MAX}")
                val prefs = AppSettings.getSharedPrefs(context)
                val wasConfigured = AppSettings.hasVolumeStepsOverride(prefs)
                val previousValue = AppSettings.getVolumeSteps(prefs)
                val saved = AppSettings.setVolumeSteps(prefs, value) && AppSettings.save(context)
                if (saved) {
                    OperationResult(true, "Volume steps set to $value; reboot required", rebootRequired = true)
                } else {
                    val rollback = rollbackModuleSetting(context) {
                        AppSettings.restoreVolumeSteps(prefs, wasConfigured, previousValue)
                    }
                    OperationResult(false, when {
                        rollback.moduleStateRestored -> "Could not save the volume-step setting; previous setting restored"
                        rollback.preferencesRestored -> "Could not save the volume-step setting; previous preference restored but module state could not be synchronized"
                        else -> "Could not save or restore the volume-step setting"
                    })
                }
            }
            ThorOperation.SET_BOOT_ANIMATION -> {
                val enabled = when (argument) {
                    "true" -> true
                    "false" -> false
                    else -> return OperationResult(false, "Invalid boot-animation setting")
                }
                val prefs = AppSettings.getSharedPrefs(context)
                val wasConfigured = prefs.contains(AppSettings.SKIP_BOOT_ANIMATION_KEY)
                val previousValue = AppSettings.getSkipBootAnimation(prefs)
                val saved = AppSettings.setSkipBootAnimation(prefs, enabled) && AppSettings.save(context)
                if (saved) {
                    OperationResult(
                        true,
                        if (enabled) "Boot animation disabled; reboot required" else "Boot animation enabled; reboot required",
                        rebootRequired = true,
                    )
                } else {
                    val rollback = rollbackModuleSetting(context) {
                        AppSettings.restoreSkipBootAnimation(prefs, wasConfigured, previousValue)
                    }
                    OperationResult(false, when {
                        rollback.moduleStateRestored -> "Could not save the boot-animation setting; previous setting restored"
                        rollback.preferencesRestored -> "Could not save the boot-animation setting; previous preference restored but module state could not be synchronized"
                        else -> "Could not save or restore the boot-animation setting"
                    })
                }
            }
        }
    }
}

class ThorSession(
    private val context: Context,
    private val backend: SystemBackend = SystemBackendFactory.create(context),
) {
    private val initialOperation = operationFromJournal()
    private var operationJob: Job? = null
    private var stateLoaded = false

    var snapshot by mutableStateOf(ThorSnapshot.loading(initialOperation))
        private set

    private fun operationFromJournal(): OperationState {
        val prefs = AppSettings.getSharedPrefs(context)
        val journalName = prefs.getString(AppSettings.JOURNAL_OPERATION_KEY, null)
        if (journalName != null) {
            val message = prefs.getString(AppSettings.JOURNAL_MESSAGE_KEY, "Interrupted") ?: "Interrupted"
            val journalBootMarker = prefs.getString(AppSettings.JOURNAL_BOOT_MARKER_KEY, null)
            val rebootOccurred = journalBootMarker
                ?.takeIf(String::isNotBlank)
                ?.let { it != SystemUtils.getBootMarker(context) }
                ?: false
            return OperationState(
                operation = runCatching { ThorOperation.valueOf(journalName) }.getOrNull(),
                status = OperationStatus.INTERRUPTED,
                message = if (rebootOccurred) {
                    "Previous operation was recorded before the last reboot; verify the current Thor state: $message"
                } else {
                    "Previous operation stopped before completion: $message"
                },
                rebootRequired = (prefs.getBoolean(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY, false) && !rebootOccurred) || hasPendingReboot(context),
            )
        }
        return pendingRebootState(context) ?: OperationState()
    }

    suspend fun load() {
        snapshot = runCatching {
            withContext(Dispatchers.IO) { backend.snapshot(initialOperation) }
        }.getOrElse {
            failedSnapshot(initialOperation)
        }
        stateLoaded = true
    }

    suspend fun refresh() {
        if (snapshot.operation.status == OperationStatus.RUNNING) return
        val pending = pendingRebootState(context)
        val refreshedOperation = if (hasRecoveryJournal()) {
            snapshot.operation
        } else if (pending != null) {
            pending
        } else {
            snapshot.operation.copy(status = OperationStatus.IDLE, message = "Ready", rebootRequired = false)
        }
        snapshot = runCatching {
            withContext(Dispatchers.IO) { backend.snapshot(refreshedOperation) }
        }.getOrElse {
            failedSnapshot(refreshedOperation)
        }
        stateLoaded = true
    }

    private fun failedSnapshot(operation: OperationState): ThorSnapshot {
        val interrupted = operation.status == OperationStatus.INTERRUPTED
        val failedOperation = operation.copy(
            operation = operation.operation ?: ThorOperation.REFRESH,
            status = if (interrupted) OperationStatus.INTERRUPTED else OperationStatus.FAILURE,
            message = if (interrupted) {
                "Recovery record remains; current Thor state could not be read. Refresh before acknowledging."
            } else {
                "Could not read current Thor state; refresh before changing the Thor."
            },
        )
        val failureProfile = if (snapshot.profile.properties != DeviceProperties()) {
            snapshot.profile
        } else {
            runCatching { DeviceProfile.detect(SystemUtils.getDeviceProperties()) }
                .getOrDefault(snapshot.profile)
        }
        return ThorSnapshot.loading(failedOperation).copy(
            profile = failureProfile.copy(capabilities = emptySet()),
            stateReadHealthy = false,
        )
    }

    fun canAcknowledgeInterruptedOperation(): Boolean =
        snapshot.operation.status == OperationStatus.INTERRUPTED && stateLoaded && snapshot.stateReadHealthy

    fun acknowledgeInterruptedOperation(): Boolean {
        if (!canAcknowledgeInterruptedOperation()) return false
        val rebootRequired = snapshot.operation.rebootRequired || hasPendingReboot(context)
        val rebootLockSaved = !rebootRequired || hasPendingReboot(context) || persistPendingReboot(
            context,
            snapshot.operation.copy(status = OperationStatus.SUCCESS),
            SystemUtils.getBootMarker(context),
        )
        val acknowledged = rebootLockSaved && clearJournal()
        snapshot = if (acknowledged) {
            snapshot.copy(
                operation = OperationState(
                    operation = snapshot.operation.operation,
                    status = OperationStatus.IDLE,
                    message = "Recovery record acknowledged; review the current Thor state before retrying",
                    rebootRequired = rebootRequired,
                ),
            )
        } else if (!rebootLockSaved) {
            snapshot.copy(
                operation = snapshot.operation.copy(
                    status = OperationStatus.INTERRUPTED,
                    message = "Could not save the reboot recovery lock; reboot the Thor before retrying",
                    rebootRequired = true,
                ),
            )
        } else {
            snapshot.copy(
                operation = snapshot.operation.copy(
                    status = OperationStatus.INTERRUPTED,
                    message = "Could not clear the recovery record; try acknowledging it again before retrying",
                ),
            )
        }
        return acknowledged
    }

    fun canCancelCurrentOperation(): Boolean =
        snapshot.operation.status == OperationStatus.RUNNING &&
            ThorOperationGuard.canCancel(snapshot.operation.operation) &&
            operationJob?.isActive == true

    fun cancelCurrentOperation(): Boolean {
        if (!canCancelCurrentOperation()) return false
        operationJob?.cancel(CancellationException("Operation cancelled by user"))
        return true
    }

    fun run(scope: CoroutineScope, operation: ThorOperation, argument: String? = null) {
        if (snapshot.operation.status == OperationStatus.RUNNING || hasRecoveryJournal()) return
        ThorOperationGuard.validate(snapshot, operation)?.let { message ->
            snapshot = snapshot.copy(
                operation = OperationState(operation, OperationStatus.FAILURE, message, snapshot.operation.rebootRequired),
            )
            return
        }
        val running = OperationState(
            operation = operation,
            status = OperationStatus.RUNNING,
            message = "${operation.name.lowercase().replace('_', ' ')} in progress",
            rebootRequired = operation.requiresRebootAfterWrite,
        )
        val operationBootMarker = SystemUtils.getBootMarker(context)
        if (!persistJournal(running, operationBootMarker)) {
            snapshot = snapshot.copy(
                operation = OperationState(operation, OperationStatus.FAILURE, "Could not save the operation recovery record"),
            )
            return
        }
        snapshot = snapshot.copy(operation = running)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    withContext(Dispatchers.IO) { backend.perform(operation, argument) }
                } catch (error: CancellationException) {
                    if (snapshot.operation == running) {
                        val interrupted = running.copy(
                            status = OperationStatus.INTERRUPTED,
                            message = if (error.message == "Operation cancelled by user") {
                                "Operation cancelled; verify the Thor state before retrying"
                            } else if (operation.requiresRebootAfterWrite) {
                                "Operation interrupted; reboot the Thor before retrying or restoring"
                            } else {
                                "Operation interrupted; verify the Thor state before retrying"
                            },
                            rebootRequired = operation.requiresRebootAfterWrite,
                        )
                        persistJournal(interrupted, operationBootMarker)
                        if (interrupted.rebootRequired) persistPendingReboot(context, interrupted, operationBootMarker)
                        snapshot = snapshot.copy(operation = interrupted)
                    }
                    throw error
                } catch (error: Throwable) {
                    OperationResult(
                        success = false,
                        message = error.message?.takeIf { it.isNotBlank() } ?: "The operation failed",
                        rebootRequired = operation.requiresRebootAfterWrite,
                    )
                }
                val finished = OperationState(
                    operation,
                    if (result.success) OperationStatus.SUCCESS else OperationStatus.FAILURE,
                    result.message,
                    rebootRequired = result.rebootRequired || hasPendingReboot(context),
                )
                if (result.rebootRequired) persistJournal(finished, operationBootMarker)
                val rebootLockSaved = !result.rebootRequired || persistPendingReboot(context, finished, operationBootMarker)
                val journalCleared = rebootLockSaved && clearJournal()
                val reported = if (!rebootLockSaved) {
                    val interrupted = OperationState(
                        operation = operation,
                        status = OperationStatus.INTERRUPTED,
                        message = "Operation finished, but its reboot lock could not be saved; reboot the Thor before acknowledging",
                        rebootRequired = true,
                    )
                    persistJournal(interrupted, operationBootMarker)
                    interrupted
                } else if (journalCleared) {
                    finished
                } else {
                    OperationState(
                        operation = operation,
                        status = OperationStatus.INTERRUPTED,
                        message = "Operation finished, but its recovery record could not be cleared; verify the Thor state before acknowledging",
                        rebootRequired = finished.rebootRequired,
                    )
                }
                snapshot = runCatching {
                    withContext(Dispatchers.IO) { backend.snapshot(reported) }
                }.getOrElse { failedSnapshot(reported) }
            } finally {
                if (operationJob === coroutineContext[Job]) operationJob = null
            }
        }
        operationJob = job
        if (!job.start() && job.isCancelled && snapshot.operation == running) {
            val interrupted = running.copy(
                status = OperationStatus.INTERRUPTED,
                message = if (operation.requiresRebootAfterWrite) {
                    "Operation interrupted; reboot the Thor before retrying or restoring"
                } else {
                    "Operation interrupted; verify the Thor state before retrying"
                },
                rebootRequired = operation.requiresRebootAfterWrite,
            )
            persistJournal(interrupted, operationBootMarker)
            if (interrupted.rebootRequired) persistPendingReboot(context, interrupted, operationBootMarker)
            snapshot = snapshot.copy(operation = interrupted)
            operationJob = null
        }
    }

    private fun persistJournal(state: OperationState, bootMarker: String): Boolean {
        return AppSettings.getSharedPrefs(context).edit()
            .putString(AppSettings.JOURNAL_OPERATION_KEY, state.operation?.name)
            .putString(AppSettings.JOURNAL_MESSAGE_KEY, state.message)
            .putBoolean(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY, state.rebootRequired)
            .putString(AppSettings.JOURNAL_BOOT_MARKER_KEY, bootMarker)
            .commit()
    }

    private fun clearJournal(): Boolean {
        return AppSettings.getSharedPrefs(context).edit()
            .remove(AppSettings.JOURNAL_OPERATION_KEY)
            .remove(AppSettings.JOURNAL_MESSAGE_KEY)
            .remove(AppSettings.JOURNAL_REBOOT_REQUIRED_KEY)
            .remove(AppSettings.JOURNAL_BOOT_MARKER_KEY)
            .commit()
    }

    private fun hasRecoveryJournal(): Boolean =
        AppSettings.getSharedPrefs(context).contains(AppSettings.JOURNAL_OPERATION_KEY)

}
