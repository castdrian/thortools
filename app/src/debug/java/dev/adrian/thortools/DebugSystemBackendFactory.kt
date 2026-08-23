package dev.adrian.thortools

import android.content.Context
import dev.adrian.thortools.utils.RecoveryImageInput
import dev.adrian.thortools.utils.RecoveryManifestStore
import java.io.File

object DebugSystemBackendFactory {
    @JvmStatic
    fun create(context: Context): SystemBackend = FakeSystemBackend(context)
}

private class FakeSystemBackend(private val context: Context) : SystemBackend {
    private var state = ThorSnapshot(
        profile = DeviceProfile.detect(
            DeviceProperties(
                manufacturer = "AYN",
                model = "AYN Thor Pro",
                device = "thor_pro",
                product = "thor",
                platform = "sm8650",
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
            ),
        ),
        batteryPercent = 88,
        lcdDensity = 320,
        volumeSteps = 20,
        animationSpeed = 1f,
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
        stockBackupSlots = emptySet(),
        patchedBackupSlots = emptySet(),
        operation = OperationState(),
        displayDiagnostics = ThorDisplayDiagnostics(
            upper = ThorDisplayPanel(
                displayId = 0,
                widthPixels = DeviceProfile.UPPER_WIDTH_PIXELS,
                heightPixels = DeviceProfile.UPPER_HEIGHT_PIXELS,
                refreshRateHz = 120f,
            ),
            lower = ThorDisplayPanel(
                displayId = 1,
                widthPixels = DeviceProfile.LOWER_WIDTH_PIXELS,
                heightPixels = DeviceProfile.LOWER_HEIGHT_PIXELS,
                refreshRateHz = 60f,
            ),
        ),
    )

    override fun snapshot(operation: OperationState): ThorSnapshot {
        state = stateWithPersistedImages()
        return state.copy(operation = operation)
    }

    override fun perform(operation: ThorOperation, argument: String?): OperationResult {
        state = stateWithPersistedImages()
        ThorOperationGuard.validate(state, operation)?.let { return OperationResult(false, it) }
        when (operation) {
            ThorOperation.BACKUP -> {
                writeImages("boot", "init_boot")
                state = state.copy(
                    backupAvailable = true,
                    stockRestoreAvailable = true,
                    stockBackupSlots = setOf("_a", "_b"),
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
            ThorOperation.FLASH -> state = state.copy(
                rooted = true,
                profile = state.profile.copy(capabilities = state.profile.capabilities + ThorCapability.ROOTED),
            )
            ThorOperation.RESTORE -> state = state.copy(
                rooted = false,
                profile = state.profile.copy(capabilities = state.profile.capabilities - ThorCapability.ROOTED),
            )
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
            ThorOperation.SET_DPI -> state = state.copy(lcdDensity = argument?.toIntOrNull() ?: state.lcdDensity)
            ThorOperation.SET_ANIMATION -> state = state.copy(animationSpeed = argument?.toFloatOrNull() ?: state.animationSpeed)
            ThorOperation.SET_VOLUME_STEPS,
            ThorOperation.SET_BOOT_ANIMATION,
            ThorOperation.INSTALL_MAGISK,
            ThorOperation.REBOOT,
            ThorOperation.REFRESH,
            -> Unit
        }
        return OperationResult(true, "Debug backend completed ${operation.name.lowercase().replace('_', ' ')}")
    }

    private fun writeImages(vararg prefixes: String) {
        val directory = context.getExternalFilesDir(null) ?: return
        directory.mkdirs()
        prefixes.forEach { prefix ->
            listOf("_a", "_b").forEach { slot ->
                File(directory, "$prefix$slot.img").writeText("ThorTools debug image $prefix$slot")
            }
        }
        val buildIdentity = state.profile.properties.buildIdentity
        val records = prefixes.flatMap { prefix ->
            val patched = prefix.endsWith("_patched")
            val partition = prefix.removeSuffix("_patched")
            listOf("_a", "_b").map { slot ->
                val file = File(directory, "$prefix$slot.img")
                val sourceHash = if (patched) {
                    RecoveryManifestStore.hashFile(File(directory, "$partition$slot.img").path).orEmpty()
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
        val stockSlots = persistedSlots(patched = false)
        val patchedSlots = persistedSlots(patched = true)
        return state.copy(
            backupAvailable = state.activeSlot in stockSlots,
            stockRestoreAvailable = state.activeSlot in stockSlots,
            patchedBackupAvailable = state.activeSlot in patchedSlots,
            patchedCacheAvailable = patchedSlots.isNotEmpty(),
            stockBackupSlots = stockSlots,
            patchedBackupSlots = patchedSlots,
        )
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
