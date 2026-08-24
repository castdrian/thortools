package dev.adrian.thortools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorOperationGuardTest {
    @Test
    fun acceptsReadyThorImageOperation() {
        assertNull(ThorOperationGuard.validate(snapshot(), ThorOperation.BACKUP))
    }

    @Test
    fun allowsStockBackupBeforeMagiskIsInstalled() {
        assertNull(ThorOperationGuard.validate(snapshot(magiskInstalled = false), ThorOperation.BACKUP))
    }

    @Test
    fun rejectsMutationsOutsideThor() {
        val nonThor = snapshot().copy(profile = DeviceProfile.detect(DeviceProperties(model = "AYN Loki")))
        assertNull(ThorOperationGuard.validate(nonThor, ThorOperation.REFRESH))
        assertEquals(
            "Only an AYN Thor can be modified",
            ThorOperationGuard.validate(nonThor, ThorOperation.INSTALL_MAGISK),
        )
    }

    @Test
    fun rejectsMutationsWithoutRootService() {
        assertEquals(
            "The Thor privileged root service is unavailable",
            ThorOperationGuard.validate(snapshot(rootServiceAvailable = false), ThorOperation.SET_DPI),
        )
    }

    @Test
    fun blocksRecoveryScriptsWhenSupportFilesAreMissing() {
        val withoutSupportFiles = snapshot().copy(
            profile = snapshot().profile.copy(
                capabilities = setOf(ThorCapability.BATTERY_STATE),
            ),
        )
        assertEquals(
            "ThorTools support files are unavailable; restart the app before changing the Thor",
            ThorOperationGuard.validate(withoutSupportFiles, ThorOperation.BACKUP),
        )
        assertNull(ThorOperationGuard.validate(withoutSupportFiles, ThorOperation.SET_DPI))
    }

    @Test
    fun rejectsMutationsWhenTheLatestSystemReadFailed() {
        assertEquals(
            "Thor system state is unavailable; refresh before changing the Thor",
            ThorOperationGuard.validate(snapshot(stateReadHealthy = false), ThorOperation.SET_DPI),
        )
        assertNull(ThorOperationGuard.validate(snapshot(stateReadHealthy = false), ThorOperation.REFRESH))
    }

    @Test
    fun blocksMutationsWhileAnOperationNeedsAttention() {
        assertEquals(
            "Another Thor operation is already in progress",
            ThorOperationGuard.validate(
                snapshot().copy(operation = OperationState(ThorOperation.FLASH, OperationStatus.RUNNING, "flashing")),
                ThorOperation.SET_DPI,
            ),
        )
        assertEquals(
            "Acknowledge the Thor recovery record before starting another operation",
            ThorOperationGuard.validate(
                snapshot().copy(operation = OperationState(ThorOperation.FLASH, OperationStatus.INTERRUPTED, "interrupted")),
                ThorOperation.SET_DPI,
            ),
        )
        assertNull(
            ThorOperationGuard.validate(
                snapshot().copy(operation = OperationState(ThorOperation.FLASH, OperationStatus.RUNNING, "flashing")),
                ThorOperation.REFRESH,
            ),
        )
    }

    @Test
    fun rejectsUnsafeImageConditions() {
        assertEquals(
            "Charge the Thor to at least 35% before image operations",
            ThorOperationGuard.validate(snapshot(batteryPercent = 34), ThorOperation.BACKUP),
        )
        assertEquals(
            "The Thor battery state is unavailable",
            ThorOperationGuard.validate(snapshot(batteryAvailable = false), ThorOperation.BACKUP),
        )
        assertEquals(
            "The active Thor slot could not be determined",
            ThorOperationGuard.validate(snapshot(activeSlot = "unknown"), ThorOperation.BACKUP),
        )
        assertEquals(
            "The Thor backup destination is not writable",
            ThorOperationGuard.validate(snapshot(backupDestinationWritable = false), ThorOperation.BACKUP),
        )
        assertEquals(
            "No supported Thor boot partition was found",
            ThorOperationGuard.validate(snapshot(initBootAvailable = false, bootAvailable = false), ThorOperation.BACKUP),
        )
    }

    @Test
    fun rejectsImageOperationsWithoutBuildIdentity() {
        val snapshot = snapshot().copy(
            profile = DeviceProfile.detect(DeviceProperties(model = "AYN Thor")).copy(
                capabilities = setOf(ThorCapability.BATTERY_STATE, ThorCapability.SUPPORT_FILES),
            ),
        )
        assertEquals(
            "The Thor build identity is unavailable",
            ThorOperationGuard.validate(snapshot, ThorOperation.BACKUP),
        )
    }

    @Test
    fun protectsStockBackupAndPatchPrerequisites() {
        assertEquals(
            "Capture stock backups before root is active",
            ThorOperationGuard.validate(snapshot(rooted = true), ThorOperation.BACKUP),
        )
        assertEquals(
            "Create a verified init_boot stock backup before patching",
            ThorOperationGuard.validate(snapshot(magiskInstalled = true), ThorOperation.PATCH),
        )
        assertEquals(
            "Install Magisk before preparing a root patch",
            ThorOperationGuard.validate(snapshot(magiskInstalled = false, backupAvailable = true), ThorOperation.PATCH),
        )
        assertEquals(
            "Keep a verified init_boot stock backup before flashing a root patch",
            ThorOperationGuard.validate(snapshot(magiskInstalled = true, patchedBackupAvailable = true), ThorOperation.FLASH),
        )
    }

    @Test
    fun requiresCompleteStockCoverageBeforePatchOrFlash() {
        assertEquals(
            "Create verified stock backups for every available Thor slot before patching",
            ThorOperationGuard.validate(
                snapshot(magiskInstalled = true, backupAvailable = true, stockBackupSlots = setOf("_a")),
                ThorOperation.PATCH,
            ),
        )
        assertEquals(
            "Create verified stock backups for every available Thor slot before flashing",
            ThorOperationGuard.validate(
                snapshot(
                    magiskInstalled = true,
                    backupAvailable = true,
                    stockRestoreAvailable = true,
                    patchedBackupAvailable = true,
                    stockBackupSlots = setOf("_a"),
                ),
                ThorOperation.FLASH,
            ),
        )
    }

    @Test
    fun allowsPatchAndFlashWithVerifiedDownloadRecoverySources() {
        val downloadOnly = snapshot(
            backupAvailable = true,
            stockRestoreAvailable = true,
            patchedBackupAvailable = true,
            stockRecoverySlots = setOf("_a", "_b"),
        )
        assertNull(ThorOperationGuard.validate(downloadOnly, ThorOperation.PATCH))
        assertNull(ThorOperationGuard.validate(downloadOnly, ThorOperation.FLASH))
    }

    @Test
    fun keepsPatchedCacheCleanupAvailableWithoutRootService() {
        assertNull(
            ThorOperationGuard.validate(
                snapshot(rootServiceAvailable = false),
                ThorOperation.CLEAR_CACHE,
            ),
        )
    }

    @Test
    fun blocksMutationsUntilTheThorHasRebootedAfterAWrite() {
        val pending = snapshot().copy(
            operation = OperationState(
                operation = ThorOperation.FLASH,
                status = OperationStatus.SUCCESS,
                message = "Root patch flashed; reboot required",
                rebootRequired = true,
            ),
        )
        assertEquals(
            "Reboot the Thor before starting another operation",
            ThorOperationGuard.validate(pending, ThorOperation.SET_DPI),
        )
        assertNull(ThorOperationGuard.validate(pending, ThorOperation.REBOOT))
        assertNull(ThorOperationGuard.validate(pending, ThorOperation.REFRESH))
        val requested = pending.copy(
            operation = pending.operation.copy(operation = ThorOperation.REBOOT),
        )
        assertEquals(
            "The Thor reboot has already been requested; wait for it to restart",
            ThorOperationGuard.validate(requested, ThorOperation.REBOOT),
        )
        assertNull(
            ThorOperationGuard.validate(
                requested.copy(operation = requested.operation.copy(status = OperationStatus.FAILURE)),
                ThorOperation.REBOOT,
            ),
        )
    }

    @Test
    fun keepsRestoreAvailableAfterRootFlash() {
        assertNull(
            ThorOperationGuard.validate(
                snapshot(rooted = true, backupAvailable = true, stockRestoreAvailable = true),
                ThorOperation.RESTORE,
            ),
        )
    }

    @Test
    fun allowsRestoreFromIndependentStockCopy() {
        assertNull(
            ThorOperationGuard.validate(
                snapshot(rooted = true, stockRestoreAvailable = true),
                ThorOperation.RESTORE,
            ),
        )
    }

    @Test
    fun rejectsRestoreWithoutAnyStockCopy() {
        assertEquals(
            "Create a verified init_boot stock backup before restoring",
            ThorOperationGuard.validate(snapshot(rooted = true), ThorOperation.RESTORE),
        )
    }

    @Test
    fun rejectsRestoreWithoutABootPartition() {
        assertEquals(
            "No supported Thor boot partition was found",
            ThorOperationGuard.validate(
                snapshot(rooted = true, stockRestoreAvailable = true, initBootAvailable = false, bootAvailable = false),
                ThorOperation.RESTORE,
            ),
        )
    }

    @Test
    fun reportsInitBootAsTheRecoveryTargetWhenBothPartitionsExist() {
        assertEquals("init_boot", snapshot().recoveryPartition)
        assertEquals("boot", snapshot(initBootAvailable = false).recoveryPartition)
        assertEquals("Unavailable", snapshot(initBootAvailable = false, bootAvailable = false).recoveryPartition)
    }

    @Test
    fun protectsMagiskModuleSettings() {
        assertEquals(
            "Root the Thor with Magisk before changing module settings",
            ThorOperationGuard.validate(snapshot(rooted = false), ThorOperation.SET_VOLUME_STEPS),
        )
    }

    @Test
    fun onlyNonPartitionOperationsCanBeCancelled() {
        assertTrue(ThorOperationGuard.canCancel(ThorOperation.SET_DPI))
        assertTrue(ThorOperationGuard.canCancel(ThorOperation.SET_ANIMATION))
        assertTrue(ThorOperationGuard.canCancel(ThorOperation.SET_VOLUME_STEPS))
        assertTrue(ThorOperationGuard.canCancel(ThorOperation.SET_BOOT_ANIMATION))
        assertTrue(ThorOperationGuard.canCancel(ThorOperation.CLEAR_CACHE))
        assertFalse(ThorOperationGuard.canCancel(ThorOperation.BACKUP))
        assertFalse(ThorOperationGuard.canCancel(ThorOperation.PATCH))
        assertFalse(ThorOperationGuard.canCancel(ThorOperation.FLASH))
        assertFalse(ThorOperationGuard.canCancel(ThorOperation.RESTORE))
        assertFalse(ThorOperationGuard.canCancel(ThorOperation.REBOOT))
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
        stateReadHealthy: Boolean = true,
        availableBootSlots: Set<String> = setOf("_a", "_b"),
        stockBackupSlots: Set<String> = if (backupAvailable) availableBootSlots else emptySet(),
        stockRecoverySlots: Set<String> = emptySet(),
    ): ThorSnapshot {
        return ThorSnapshot(
            profile = DeviceProfile.detect(DeviceProperties(model = "AYN Thor", buildFingerprint = "test-build")).copy(
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
            patchedCacheAvailable = patchedBackupAvailable,
            availableBootSlots = availableBootSlots,
            stockBackupSlots = stockBackupSlots,
            stockRecoverySlots = stockRecoverySlots,
            operation = OperationState(),
            stateReadHealthy = stateReadHealthy,
        )
    }
}
