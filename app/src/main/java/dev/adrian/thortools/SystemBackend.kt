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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class ThorDisplayDiagnostics(
    val upper: ThorDisplayPanel = ThorDisplayPanel(),
    val lower: ThorDisplayPanel = ThorDisplayPanel(),
) {
    val dualDisplayReady: Boolean
        get() = upper.present &&
            lower.present &&
            upper.displayId != lower.displayId &&
            DeviceProfile.isThorUpperDisplay(upper.widthPixels, upper.heightPixels, upper.rotation) &&
            DeviceProfile.isThorLowerDisplay(lower.widthPixels, lower.heightPixels, lower.rotation)
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
    val stockBackupSlots: Set<String> = emptySet(),
    val patchedBackupSlots: Set<String> = emptySet(),
    val operation: OperationState,
    val displayDiagnostics: ThorDisplayDiagnostics = ThorDisplayDiagnostics(),
) {
    val recoveryPartition: String
        get() = when {
            initBootAvailable -> "init_boot"
            bootAvailable -> "boot"
            else -> "Unavailable"
        }

    val stockBackupCoverageReady: Boolean
        get() = availableBootSlots.isNotEmpty() && availableBootSlots.all(stockBackupSlots::contains)

    val capabilityRows: List<Pair<String, Boolean>>
        get() = listOf(
            "Thor device" to profile.isThor,
            "Root service" to supportsThorCapability(ThorCapability.ROOT_SERVICE),
            "Root access" to supportsThorCapability(ThorCapability.ROOTED),
            "Magisk" to supportsThorCapability(ThorCapability.MAGISK),
            "Active slot" to supportsThorCapability(ThorCapability.ACTIVE_SLOT),
            "init_boot partition" to supportsThorCapability(ThorCapability.INIT_BOOT_PARTITION),
            "boot partition" to supportsThorCapability(ThorCapability.BOOT_PARTITION),
            "Battery state" to supportsThorCapability(ThorCapability.BATTERY_STATE),
            "Backup destination" to supportsThorCapability(ThorCapability.BACKUP_DESTINATION),
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
            operation = operation,
        )
    }
}

data class OperationResult(
    val success: Boolean,
    val message: String,
)

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

    fun validate(snapshot: ThorSnapshot, operation: ThorOperation): String? {
        if (operation.requiresThor && !snapshot.profile.isThor) return "Only an AYN Thor can be modified"
        if (operation != ThorOperation.REFRESH && snapshot.operation.status == OperationStatus.RUNNING) {
            return "Another Thor operation is already in progress"
        }
        if (operation != ThorOperation.REFRESH && snapshot.operation.status == OperationStatus.INTERRUPTED) {
            return "Acknowledge the Thor recovery record before starting another operation"
        }
        if (operation.requiresRootService && !snapshot.rootServiceAvailable) {
            return "The Thor privileged root service is unavailable"
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
                if (!snapshot.stockBackupCoverageReady) return "Create verified stock backups for every available Thor slot before patching"
            }
            ThorOperation.FLASH -> {
                if (snapshot.rooted) return "The Thor already reports root access; restore stock before flashing again"
                if (!snapshot.magiskInstalled) return "Install Magisk before flashing a root patch"
                if (!snapshot.stockRestoreAvailable) return "Keep a verified ${snapshot.recoveryPartition} stock backup before flashing a root patch"
                if (!snapshot.stockBackupCoverageReady) return "Create verified stock backups for every available Thor slot before flashing"
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

class RealSystemBackend(private val context: Context) : SystemBackend {
    override fun snapshot(operation: OperationState): ThorSnapshot {
        val properties = SystemUtils.getDeviceProperties()
        val rootService = RootUtils.hasPServer()
        val rooted = RootUtils.isDeviceRooted(context, rootService)
        val magisk = MagiskUtil.hasMagiskPackage(context)
        val battery = SystemUtils.getBatteryPercent(context)
        val prefs = AppSettings.getSharedPrefs(context)
        val initBoot = rootService && RootUtils.hasPartition(context, "init_boot", properties.slot)
        val boot = rootService && RootUtils.hasPartition(context, "boot", properties.slot)
        val backupDestination = FileUtils.isBackupDestinationWritable(context)
        val availableBootSlots = PatchUtils.availableBootSlots(context)
        val capabilities = buildSet {
            if (rootService) add(ThorCapability.ROOT_SERVICE)
            if (rooted) add(ThorCapability.ROOTED)
            if (magisk) add(ThorCapability.MAGISK)
            if (properties.slot == "_a" || properties.slot == "_b") add(ThorCapability.ACTIVE_SLOT)
            if (initBoot) add(ThorCapability.INIT_BOOT_PARTITION)
            if (boot) add(ThorCapability.BOOT_PARTITION)
            if (battery != null) add(ThorCapability.BATTERY_STATE)
            if (backupDestination) add(ThorCapability.BACKUP_DESTINATION)
        }
        return ThorSnapshot(
            profile = DeviceProfile.detect(properties).copy(capabilities = capabilities),
            batteryPercent = battery ?: 0,
            lcdDensity = AppSettings.getDpi(prefs, SystemUtils.getPropLcdDensity()),
            volumeSteps = AppSettings.getVolumeSteps(prefs, SystemUtils.getPropVolumeSteps()),
            animationSpeed = AppSettings.getAnimationSpeed(prefs),
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
            stockBackupSlots = PatchUtils.stockBackupSlots(context),
            patchedBackupSlots = PatchUtils.patchedBackupSlots(context),
            operation = operation,
            displayDiagnostics = readDisplayDiagnostics(),
        )
    }

    private fun readDisplayDiagnostics(): ThorDisplayDiagnostics {
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return ThorDisplayDiagnostics()
        val displays = runCatching { displayManager.displays.toList() }.getOrElse { return ThorDisplayDiagnostics() }
        val upper = displays.firstOrNull { display ->
            display.displayId == Display.DEFAULT_DISPLAY && display.isThorUpperDisplay()
        } ?: displays.firstOrNull { display -> display.isThorUpperDisplay() }
        val lower = displays.firstOrNull { display -> display.isThorLowerDisplay() }
        return ThorDisplayDiagnostics(
            upper = upper?.toThorDisplayPanel() ?: ThorDisplayPanel(),
            lower = lower?.toThorDisplayPanel() ?: ThorDisplayPanel(),
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

    override fun perform(operation: ThorOperation, argument: String?): OperationResult {
        val current = snapshot()
        ThorOperationGuard.validate(current, operation)?.let { return OperationResult(false, it) }
        return when (operation) {
            ThorOperation.REFRESH -> OperationResult(true, "System state refreshed")
            ThorOperation.INSTALL_MAGISK -> {
                val queued = MagiskUtil.enqueueLatestDownload(context)
                OperationResult(queued, if (queued) "Magisk download started in the Download folder" else "Could not start the Magisk download")
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
            } else if (PatchUtils.flashBoot(context, current.activeSlot)) {
                OperationResult(true, "Root patch flashed; reboot required")
            } else {
                OperationResult(false, "No verified current-build active-slot patch is available")
            }
            ThorOperation.RESTORE -> if (!current.stockRestoreAvailable) {
                OperationResult(false, "A stock active-slot backup is required")
            } else if (PatchUtils.restoreBoot(context, current.activeSlot)) {
                OperationResult(true, "Stock image restored; reboot required")
            } else {
                OperationResult(false, "No verified current-build stock image is available")
            }
            ThorOperation.CLEAR_CACHE -> {
                val cleared = PatchUtils.clearBootCache(context)
                OperationResult(cleared, if (cleared) "Patched images cleared; stock backups retained" else "Some patched images could not be removed")
            }
            ThorOperation.REBOOT -> {
                val rebooted = RootUtils.reboot(context)
                OperationResult(rebooted, if (rebooted) "Reboot requested" else "The Thor reboot command failed")
            }
            ThorOperation.SET_DPI -> {
                val value = argument?.toIntOrNull() ?: return OperationResult(false, "Invalid DPI")
                if (value !in AppSettings.DPI_MIN..AppSettings.DPI_MAX) return OperationResult(false, "DPI must be between ${AppSettings.DPI_MIN} and ${AppSettings.DPI_MAX}")
                val changed = RootUtils.setDpi(context, value)
                if (changed) AppSettings.setDpi(AppSettings.getSharedPrefs(context), value)
                OperationResult(changed, if (changed) "DPI set to $value" else "The Thor DPI command failed")
            }
            ThorOperation.SET_ANIMATION -> {
                val value = argument?.toFloatOrNull() ?: return OperationResult(false, "Invalid animation speed")
                if (!value.isFinite() || value !in 0f..1f) return OperationResult(false, "Animation speed must be between 0x and 1x")
                val changed = RootUtils.setAnimationSpeed(context, value)
                if (changed) AppSettings.setAnimationSpeed(AppSettings.getSharedPrefs(context), value)
                OperationResult(changed, if (changed) "Animation speed set to ${value}x" else "The Thor animation command failed")
            }
            ThorOperation.SET_VOLUME_STEPS -> {
                val value = argument?.toIntOrNull() ?: return OperationResult(false, "Invalid volume step count")
                if (value !in AppSettings.VOLUME_STEPS_MIN..AppSettings.VOLUME_STEPS_MAX) return OperationResult(false, "Volume steps must be between ${AppSettings.VOLUME_STEPS_MIN} and ${AppSettings.VOLUME_STEPS_MAX}")
                AppSettings.setVolumeSteps(AppSettings.getSharedPrefs(context), value)
                val saved = AppSettings.save(context)
                OperationResult(saved, if (saved) "Volume steps set to $value; reboot required" else "Could not save the volume-step setting")
            }
            ThorOperation.SET_BOOT_ANIMATION -> {
                val enabled = when (argument) {
                    "true" -> true
                    "false" -> false
                    else -> return OperationResult(false, "Invalid boot-animation setting")
                }
                AppSettings.setSkipBootAnimation(AppSettings.getSharedPrefs(context), enabled)
                val saved = AppSettings.save(context)
                OperationResult(saved, if (saved) {
                    if (enabled) "Boot animation disabled; reboot required" else "Boot animation enabled; reboot required"
                } else "Could not save the boot-animation setting")
            }
        }
    }
}

class ThorSession(
    private val context: Context,
    private val backend: SystemBackend = SystemBackendFactory.create(context),
) {
    private val initialOperation = operationFromJournal()

    var snapshot by mutableStateOf(ThorSnapshot.loading(initialOperation))
        private set

    private fun operationFromJournal(): OperationState {
        val prefs = AppSettings.getSharedPrefs(context)
        val name = prefs.getString(AppSettings.JOURNAL_OPERATION_KEY, null) ?: return OperationState()
        val message = prefs.getString(AppSettings.JOURNAL_MESSAGE_KEY, "Interrupted") ?: "Interrupted"
        return OperationState(
            operation = runCatching { ThorOperation.valueOf(name) }.getOrNull(),
            status = OperationStatus.INTERRUPTED,
            message = "Previous operation stopped before completion: $message",
        )
    }

    suspend fun load() {
        snapshot = runCatching {
            withContext(Dispatchers.IO) { backend.snapshot(initialOperation) }
        }.getOrElse {
            ThorSnapshot.loading(initialOperation.copy(status = OperationStatus.FAILURE, message = "Could not read Thor system state"))
        }
    }

    suspend fun refresh() {
        if (snapshot.operation.status == OperationStatus.RUNNING) return
        val refreshedOperation = if (hasRecoveryJournal()) {
            snapshot.operation
        } else {
            snapshot.operation.copy(status = OperationStatus.IDLE, message = "Ready")
        }
        snapshot = runCatching {
            withContext(Dispatchers.IO) { backend.snapshot(refreshedOperation) }
        }.getOrElse {
            snapshot.copy(operation = refreshedOperation)
        }
    }

    fun acknowledgeInterruptedOperation(): Boolean {
        if (snapshot.operation.status != OperationStatus.INTERRUPTED) return false
        val acknowledged = clearJournal()
        snapshot = if (acknowledged) {
            snapshot.copy(
                operation = OperationState(
                    operation = snapshot.operation.operation,
                    status = OperationStatus.IDLE,
                    message = "Recovery record acknowledged; review the current Thor state before retrying",
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

    fun run(scope: CoroutineScope, operation: ThorOperation, argument: String? = null) {
        if (snapshot.operation.status == OperationStatus.RUNNING || hasRecoveryJournal()) return
        val running = OperationState(operation, OperationStatus.RUNNING, "${operation.name.lowercase().replace('_', ' ')} in progress")
        if (!persistJournal(running)) {
            snapshot = snapshot.copy(
                operation = OperationState(operation, OperationStatus.FAILURE, "Could not save the operation recovery record"),
            )
            return
        }
        snapshot = snapshot.copy(operation = running)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { backend.perform(operation, argument) }
            } catch (error: CancellationException) {
                if (snapshot.operation == running) {
                    snapshot = snapshot.copy(
                        operation = running.copy(
                            status = OperationStatus.INTERRUPTED,
                            message = "Operation interrupted; verify the Thor state before retrying",
                        ),
                    )
                }
                throw error
            } catch (error: Throwable) {
                OperationResult(false, error.message?.takeIf { it.isNotBlank() } ?: "The operation failed")
            }
            val finished = OperationState(
                operation,
                if (result.success) OperationStatus.SUCCESS else OperationStatus.FAILURE,
                result.message,
            )
            val journalCleared = clearJournal()
            val reported = if (journalCleared) {
                finished
            } else {
                OperationState(
                    operation = operation,
                    status = OperationStatus.INTERRUPTED,
                    message = "Operation finished, but its recovery record could not be cleared; verify the Thor state before acknowledging",
                )
            }
            snapshot = runCatching {
                withContext(Dispatchers.IO) { backend.snapshot(reported) }
            }.getOrElse {
                snapshot.copy(operation = reported)
            }
        }
    }

    private fun persistJournal(state: OperationState): Boolean {
        return AppSettings.getSharedPrefs(context).edit()
            .putString(AppSettings.JOURNAL_OPERATION_KEY, state.operation?.name)
            .putString(AppSettings.JOURNAL_MESSAGE_KEY, state.message)
            .commit()
    }

    private fun clearJournal(): Boolean {
        return AppSettings.getSharedPrefs(context).edit()
            .remove(AppSettings.JOURNAL_OPERATION_KEY)
            .remove(AppSettings.JOURNAL_MESSAGE_KEY)
            .commit()
    }

    private fun hasRecoveryJournal(): Boolean =
        AppSettings.getSharedPrefs(context).contains(AppSettings.JOURNAL_OPERATION_KEY)
}
