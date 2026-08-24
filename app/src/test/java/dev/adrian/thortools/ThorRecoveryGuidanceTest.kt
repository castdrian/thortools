package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Test

class ThorRecoveryGuidanceTest {
    @Test
    fun explainsDiagnosticsForNonThorDevices() {
        assertEquals(
            "Diagnostics only: no mutating action is available on this device.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(profile = DeviceProfile.detect(DeviceProperties(model = "AYN Loki")))),
        )
    }

    @Test
    fun prioritizesRunningAndInterruptedOperations() {
        assertEquals(
            "Operation in progress. Keep the Thor connected and wait for the result.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(operation = OperationState(ThorOperation.FLASH, OperationStatus.RUNNING, "flashing"))),
        )
        assertEquals(
            "Recovery required: acknowledge the recovery record, verify the active slot and image hashes, then retry when the dashboard is ready.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(operation = OperationState(ThorOperation.FLASH, OperationStatus.INTERRUPTED, "interrupted"))),
        )
        assertEquals(
            "Thor system state could not be read. Refresh before acknowledging the recovery record.",
            ThorRecoveryGuidance.forSnapshot(
                snapshot().copy(
                    stateReadHealthy = false,
                    operation = OperationState(ThorOperation.FLASH, OperationStatus.INTERRUPTED, "interrupted"),
                ),
            ),
        )
    }

    @Test
    fun requiresARebootAfterAWrite() {
        assertEquals(
            "Reboot the Thor before starting another operation. Wait for the dashboard to read the new boot state.",
            ThorRecoveryGuidance.forSnapshot(
                snapshot().copy(
                    operation = OperationState(
                        operation = ThorOperation.FLASH,
                        status = OperationStatus.SUCCESS,
                        message = "Root patch flashed; reboot required",
                        rebootRequired = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun explainsOperationFailures() {
        assertEquals(
            "The last operation failed. Review its message and the capability checks before retrying.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(operation = OperationState(ThorOperation.BACKUP, OperationStatus.FAILURE, "failed"))),
        )
    }

    @Test
    fun explainsModuleSynchronizationStates() {
        assertEquals(
            "ThorTools could not synchronize its Magisk module. Verify root access, then retry the module setting before rebooting.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(moduleSyncState = ThorModuleSyncState.FAILED)),
        )
        assertEquals(
            "ThorTools module synchronization is pending. Keep the Thor rooted and wait for the dashboard to report it synced.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(moduleSyncState = ThorModuleSyncState.PENDING)),
        )
    }

    @Test
    fun explainsBootOverrideSynchronizationStates() {
        assertEquals(
            "ThorTools could not reapply its saved DPI or animation override after boot. Review the current values, then retry the setting.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(bootOverrideState = ThorBootOverrideState.FAILED)),
        )
        assertEquals(
            "ThorTools has a saved DPI or animation override that has not been confirmed after boot. Refresh after the privileged service is available.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(bootOverrideState = ThorBootOverrideState.PENDING)),
        )
    }

    @Test
    fun explainsRuntimeBlockers() {
        assertEquals(
            "Thor system state could not be read. Refresh before changing the device.",
            ThorRecoveryGuidance.forSnapshot(snapshot().copy(stateReadHealthy = false)),
        )
        assertEquals(
            "Diagnostics only until the Thor privileged root service is available.",
            ThorRecoveryGuidance.forSnapshot(snapshot(rootServiceAvailable = false)),
        )
        assertEquals(
            "Recovery operations are blocked until the active Thor slot can be read.",
            ThorRecoveryGuidance.forSnapshot(snapshot(activeSlot = "unknown")),
        )
        assertEquals(
            "Recovery operations are blocked until a supported Thor boot partition can be read.",
            ThorRecoveryGuidance.forSnapshot(snapshot(initBootAvailable = false, bootAvailable = false)),
        )
        assertEquals(
            "Image operations are blocked until the Thor battery state can be read.",
            ThorRecoveryGuidance.forSnapshot(snapshot(batteryAvailable = false)),
        )
        assertEquals(
            "Charge the Thor to at least 35% before starting an image operation.",
            ThorRecoveryGuidance.forSnapshot(snapshot(batteryPercent = 20)),
        )
        assertEquals(
            "Image operations are blocked until the Thor recovery folder is writable.",
            ThorRecoveryGuidance.forSnapshot(snapshot(backupDestinationWritable = false)),
        )
        assertEquals(
            "ThorTools support files are unavailable. Restart ThorTools before changing the Thor.",
            ThorRecoveryGuidance.forSnapshot(
                snapshot().copy(
                    profile = snapshot().profile.copy(capabilities = setOf(ThorCapability.BATTERY_STATE)),
                ),
            ),
        )
    }

    @Test
    fun explainsRootAndImageRecoveryStates() {
        assertEquals(
            "The Thor is rooted without a verified stock restore source. Do not update until a stock image is secured.",
            ThorRecoveryGuidance.forSnapshot(snapshot(rooted = true, stockRestoreAvailable = false)),
        )
        assertEquals(
            "Keep the verified stock image before OTA or service work, and restore stock before updating.",
            ThorRecoveryGuidance.forSnapshot(snapshot(rooted = true, stockRestoreAvailable = true)),
        )
        assertEquals(
            "A verified active-slot patch is ready. Review the hashes and flash only the active slot.",
            ThorRecoveryGuidance.forSnapshot(snapshot(backupAvailable = true, patchedBackupAvailable = true)),
        )
        assertEquals(
            "Create verified stock backups for every available slot before preparing a root patch.",
            ThorRecoveryGuidance.forSnapshot(
                snapshot(
                    backupAvailable = true,
                    patchedBackupAvailable = true,
                    stockBackupSlots = setOf("_a"),
                ),
            ),
        )
        assertEquals(
            "Create verified stock backups for every available slot before preparing a root patch.",
            ThorRecoveryGuidance.forSnapshot(snapshot()),
        )
        assertEquals(
            "Install Magisk after the stock backup is verified, then return to EZ Root.",
            ThorRecoveryGuidance.forSnapshot(snapshot(backupAvailable = true, magiskInstalled = false)),
        )
    }

    private fun snapshot(
        rootServiceAvailable: Boolean = true,
        batteryPercent: Int = 80,
        activeSlot: String = "_a",
        backupDestinationWritable: Boolean = true,
        initBootAvailable: Boolean = true,
        bootAvailable: Boolean = true,
        rooted: Boolean = false,
        magiskInstalled: Boolean = true,
        backupAvailable: Boolean = false,
        stockRestoreAvailable: Boolean = backupAvailable,
        patchedBackupAvailable: Boolean = false,
        batteryAvailable: Boolean = true,
        availableBootSlots: Set<String> = setOf("_a", "_b"),
        stockBackupSlots: Set<String> = if (backupAvailable) availableBootSlots else emptySet(),
    ): ThorSnapshot {
        return ThorSnapshot(
            profile = DeviceProfile.detect(DeviceProperties(model = "AYN Thor")).copy(
                capabilities = buildSet {
                    add(ThorCapability.SUPPORT_FILES)
                    if (batteryAvailable) add(ThorCapability.BATTERY_STATE)
                },
            ),
            batteryPercent = batteryPercent,
            lcdDensity = 320,
            volumeSteps = 15,
            animationSpeed = 1f,
            activeSlot = activeSlot,
            kernelVersion = "test",
            rootServiceAvailable = rootServiceAvailable,
            rooted = rooted,
            magiskInstalled = magiskInstalled,
            initBootAvailable = initBootAvailable,
            bootAvailable = bootAvailable,
            backupDestinationWritable = backupDestinationWritable,
            backupAvailable = backupAvailable,
            stockRestoreAvailable = stockRestoreAvailable,
            patchedBackupAvailable = patchedBackupAvailable,
            availableBootSlots = availableBootSlots,
            stockBackupSlots = stockBackupSlots,
            operation = OperationState(),
        )
    }
}
