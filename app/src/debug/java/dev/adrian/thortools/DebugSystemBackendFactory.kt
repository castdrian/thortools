package dev.adrian.thortools

import android.content.Context
import dev.adrian.thortools.utils.FileUtils
import dev.adrian.thortools.utils.RecoveryImageInput
import dev.adrian.thortools.utils.RecoveryManifestStore
import java.io.File

object DebugSystemBackendFactory {
    @JvmStatic
    fun create(context: Context): SystemBackend = FakeSystemBackend(context)
}

private class FakeSystemBackend(private val context: Context) : SystemBackend {
    private val preferences = AppSettings.getSharedPrefs(context)
    private var state = ThorSnapshot(
        profile = DeviceProfile.detect(
            DeviceProperties(
                manufacturer = "AYN",
                brand = "AYN",
                model = "AYN Thor Pro",
                device = "kalama",
                product = "kalama",
                systemDevice = "kalama",
                systemName = "kalama",
                buildProduct = "kalama",
                board = "kalama",
                hardware = "qcom",
                soc = "qcs8550",
                platform = "sm8550",
                firmware = "Android 13 / ThorTools AVD",
                buildId = "THOR_DEBUG",
                buildDisplayId = "ThorTools AVD",
                buildDate = "2026-08-19",
                buildFingerprint = "AYN/Thor/ThorTools:13/THOR_DEBUG/10001:userdebug/test-keys",
                serial = "thor-avd",
                slot = "_a",
            ),
        ).copy(
            capabilities = setOf(
                ThorCapability.ROOT_SERVICE,
                ThorCapability.MAGISK,
                ThorCapability.ACTIVE_SLOT,
                ThorCapability.INIT_BOOT_PARTITION,
                ThorCapability.BOOT_PARTITION,
                ThorCapability.BATTERY_STATE,
                ThorCapability.BACKUP_DESTINATION,
                ThorCapability.SUPPORT_FILES,
            ),
        ),
        batteryPercent = 88,
        lcdDensity = AppSettings.getDpi(preferences, 320),
        volumeSteps = AppSettings.getVolumeSteps(preferences, 20),
        animationSpeed = AppSettings.getAnimationSpeed(preferences, 1f),
        activeSlot = "_a",
        kernelVersion = "ThorTools deterministic emulator backend",
        rootServiceAvailable = true,
        rooted = false,
        magiskInstalled = true,
        initBootAvailable = true,
        bootAvailable = true,
        backupDestinationWritable = true,
        backupAvailable = false,
        stockRestoreAvailable = false,
        patchedBackupAvailable = false,
        patchedCacheAvailable = false,
        availableBootSlots = setOf("_a", "_b"),
        availablePartitionsBySlot = mapOf(
            "_a" to setOf("boot", "init_boot"),
            "_b" to setOf("boot", "init_boot"),
        ),
        stockBackupSlots = emptySet(),
        stockRecoverySlots = emptySet(),
        patchedBackupSlots = emptySet(),
        operation = OperationState(),
        moduleSyncState = AppSettings.getModuleSyncState(preferences),
        bootOverrideState = AppSettings.getBootOverrideState(preferences),
        displayDiagnostics = readThorDisplayDiagnostics(context),
    )

    override fun snapshot(operation: OperationState): ThorSnapshot {
        state = stateWithPersistedImages()
        return state.copy(operation = operation)
    }

    override fun perform(operation: ThorOperation, argument: String?): OperationResult {
        state = stateWithPersistedImages()
        ThorOperationGuard.validate(state, operation)?.let { return OperationResult(false, it) }
        var rebootRequired = false
        when (operation) {
            ThorOperation.BACKUP -> {
                writeImages("boot", "init_boot")
                state = state.copy(
                    backupAvailable = true,
                    stockRestoreAvailable = true,
                    stockBackupSlots = setOf("_a", "_b"),
                    stockRecoverySlots = setOf("_a", "_b"),
                )
            }
            ThorOperation.PATCH -> {
                writeImages("boot_patched", "init_boot_patched")
                state = state.copy(
                    patchedBackupAvailable = true,
                    patchedCacheAvailable = true,
                    patchedBackupSlots = setOf("_a", "_b"),
                )
            }
            ThorOperation.FLASH -> {
                rebootRequired = true
                state = state.copy(
                    rooted = true,
                    profile = state.profile.copy(capabilities = state.profile.capabilities + ThorCapability.ROOTED),
                )
            }
            ThorOperation.RESTORE -> {
                rebootRequired = true
                state = state.copy(
                    rooted = false,
                    profile = state.profile.copy(capabilities = state.profile.capabilities - ThorCapability.ROOTED),
                )
            }
            ThorOperation.CLEAR_CACHE -> {
                val patchedNames = RecoveryManifestStore.records(context)
                    .filter { it.patched }
                    .mapTo(mutableSetOf()) { it.fileName }
                listOf("boot_patched", "init_boot_patched").forEach { prefix ->
                    listOf("_a", "_b").forEach { slot ->
                        File(context.getExternalFilesDir(null), "$prefix$slot.img").delete()
                    }
                }
                RecoveryManifestStore.removePatchedRecords(context, patchedNames)
                state = state.copy(
                    patchedBackupAvailable = false,
                    patchedCacheAvailable = false,
                    patchedBackupSlots = emptySet(),
                )
            }
            ThorOperation.SET_DPI -> {
                val value = argument?.toIntOrNull()
                    ?: return OperationResult(false, "Invalid DPI")
                if (value !in AppSettings.DPI_MIN..AppSettings.DPI_MAX) {
                    return OperationResult(false, "DPI must be between ${AppSettings.DPI_MIN} and ${AppSettings.DPI_MAX}")
                }
                AppSettings.setDpi(preferences, value)
                AppSettings.setBootOverrideState(preferences, ThorBootOverrideState.APPLIED)
                state = state.copy(lcdDensity = AppSettings.getDpi(preferences, value))
            }
            ThorOperation.SET_ANIMATION -> {
                val value = argument?.toFloatOrNull()
                    ?: return OperationResult(false, "Invalid animation speed")
                if (!value.isFinite() || value !in 0f..1f) {
                    return OperationResult(false, "Animation speed must be between 0x and 1x")
                }
                AppSettings.setAnimationSpeed(preferences, value)
                AppSettings.setBootOverrideState(preferences, ThorBootOverrideState.APPLIED)
                state = state.copy(animationSpeed = AppSettings.getAnimationSpeed(preferences, value))
            }
            ThorOperation.SET_VOLUME_STEPS -> {
                val value = argument?.toIntOrNull()
                    ?: return OperationResult(false, "Invalid volume step count")
                if (value !in AppSettings.VOLUME_STEPS_MIN..AppSettings.VOLUME_STEPS_MAX) {
                    return OperationResult(false, "Volume steps must be between ${AppSettings.VOLUME_STEPS_MIN} and ${AppSettings.VOLUME_STEPS_MAX}")
                }
                if (!AppSettings.setVolumeSteps(preferences, value)) {
                    return OperationResult(false, "Could not save the volume-step setting")
                }
                AppSettings.setModuleSyncState(preferences, ThorModuleSyncState.SYNCED)
                state = state.copy(volumeSteps = AppSettings.getVolumeSteps(preferences, value))
            }
            ThorOperation.SET_BOOT_ANIMATION -> {
                val value = when (argument) {
                    "true" -> true
                    "false" -> false
                    else -> return OperationResult(false, "Invalid boot-animation setting")
                }
                if (!AppSettings.setSkipBootAnimation(preferences, value)) {
                    return OperationResult(false, "Could not save the boot-animation setting")
                }
                AppSettings.setModuleSyncState(preferences, ThorModuleSyncState.SYNCED)
            }
            ThorOperation.INSTALL_MAGISK,
            ThorOperation.REBOOT,
            ThorOperation.REFRESH,
            -> Unit
        }
        if (operation in setOf(ThorOperation.SET_VOLUME_STEPS, ThorOperation.SET_BOOT_ANIMATION, ThorOperation.REBOOT)) rebootRequired = true
        return OperationResult(true, "Debug backend completed ${operation.name.lowercase().replace('_', ' ')}", rebootRequired)
    }

    private fun writeImages(vararg prefixes: String) {
        val directory = context.getExternalFilesDir(null) ?: return
        directory.mkdirs()
        prefixes.forEach { prefix ->
            listOf("_a", "_b").forEach { slot ->
                val file = File(directory, "$prefix$slot.img")
                file.writeText("ThorTools debug image $prefix$slot")
                if (!prefix.endsWith("_patched")) {
                    val downloadFile = File(FileUtils.getPathDownload("/${file.name}"))
                    downloadFile.parentFile?.mkdirs()
                    downloadFile.writeText(file.readText())
                }
            }
        }
        val buildIdentity = state.profile.properties.buildIdentity
        val records = prefixes.flatMap { prefix ->
            val patched = prefix.endsWith("_patched")
            val partition = prefix.removeSuffix("_patched")
            listOf("_a", "_b").map { slot ->
                val file = File(directory, "$prefix$slot.img")
                val sourceHash = if (patched) {
                    RecoveryManifestStore.hashFile(File(directory, "$partition$slot.img").path)
                        ?: RecoveryManifestStore.hashFile(File(FileUtils.getPathDownload("/$partition$slot.img")).path)
                        .orEmpty()
                } else {
                    ""
                }
                RecoveryImageInput(
                    fileName = file.name,
                    slot = slot,
                    partition = partition,
                    patched = patched,
                    path = file.path,
                    buildIdentity = buildIdentity,
                    sourceSha256 = sourceHash,
                )
            }
        }
        RecoveryManifestStore.recordLocalImages(context, records)
    }

    private fun stateWithPersistedImages(): ThorSnapshot {
        val stockSlots = persistedStockBackupSlots()
        val stockRecoverySlots = persistedStockRecoverySlots()
        val patchedSlots = persistedSlots(patched = true)
        return state.copy(
            lcdDensity = AppSettings.getDpi(preferences, 320),
            volumeSteps = AppSettings.getVolumeSteps(preferences, 20),
            animationSpeed = AppSettings.getAnimationSpeed(preferences, 1f),
            moduleSyncState = AppSettings.getModuleSyncState(preferences),
            bootOverrideState = AppSettings.getBootOverrideState(preferences),
            displayDiagnostics = readThorDisplayDiagnostics(context),
            backupAvailable = state.activeSlot in stockRecoverySlots,
            stockRestoreAvailable = state.activeSlot in stockRecoverySlots,
            patchedBackupAvailable = state.activeSlot in patchedSlots,
            patchedCacheAvailable = patchedSlots.isNotEmpty(),
            stockBackupSlots = stockSlots,
            stockRecoverySlots = stockRecoverySlots,
            patchedBackupSlots = patchedSlots,
        )
    }

    private fun persistedStockRecoverySlots(): Set<String> {
        val directory = context.getExternalFilesDir(null) ?: return emptySet()
        val buildIdentity = state.profile.properties.buildIdentity
        return setOf("_a", "_b").filterTo(mutableSetOf()) { slot ->
            RecoveryManifestStore.hasVerifiedStockImage(
                context = context,
                slot = slot,
                partition = "init_boot",
                localPath = File(directory, "init_boot$slot.img").path,
                downloadPath = FileUtils.getPathDownload("/init_boot$slot.img"),
                buildIdentity = buildIdentity,
            )
        }
    }

    private fun persistedStockBackupSlots(): Set<String> {
        val directory = context.getExternalFilesDir(null) ?: return emptySet()
        val buildIdentity = state.profile.properties.buildIdentity
        return setOf("_a", "_b").filterTo(mutableSetOf()) { slot ->
            RecoveryManifestStore.hasVerifiedStockCopies(
                context = context,
                slot = slot,
                partition = "init_boot",
                localPath = File(directory, "init_boot$slot.img").path,
                downloadPath = FileUtils.getPathDownload("/init_boot$slot.img"),
                buildIdentity = buildIdentity,
            )
        }
    }

    private fun persistedSlots(patched: Boolean): Set<String> {
        val directory = context.getExternalFilesDir(null) ?: return emptySet()
        val buildIdentity = state.profile.properties.buildIdentity
        return RecoveryManifestStore.records(context)
            .filter { record ->
                record.patched == patched &&
                    record.buildIdentity == buildIdentity &&
                    record.slot in setOf("_a", "_b")
            }
            .filter { record ->
                val path = File(directory, File(record.fileName).name).path
                File(path).isFile && File(path).length() == record.size && RecoveryManifestStore.hashFile(path) == record.sha256
            }
            .mapTo(mutableSetOf()) { it.slot }
    }
}
