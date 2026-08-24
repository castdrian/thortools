package dev.adrian.thortools

import dev.adrian.thortools.utils.PatchUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchUtilsTest {
    @Test
    fun requiresEveryAvailableSlotToBeBackedUp() {
        assertTrue(PatchUtils.hasCompleteSlotCoverage(setOf("_a", "_b"), setOf("_a", "_b")))
        assertFalse(PatchUtils.hasCompleteSlotCoverage(setOf("_a", "_b"), setOf("_a")))
        assertTrue(PatchUtils.hasCompleteSlotCoverage(setOf("_a"), setOf("_a", "_b")))
        assertFalse(PatchUtils.hasCompleteSlotCoverage(emptySet(), setOf("_a", "_b")))
    }

    @Test
    fun reportsCompleteCoverageOnlyForEveryAvailableSlot() {
        assertTrue(
            ThorSnapshot.loading(OperationState()).copy(
                availableBootSlots = setOf("_a", "_b"),
                stockBackupSlots = setOf("_a", "_b"),
            ).stockBackupCoverageReady,
        )
        assertFalse(
            ThorSnapshot.loading(OperationState()).copy(
                availableBootSlots = setOf("_a", "_b"),
                stockBackupSlots = setOf("_a"),
            ).stockBackupCoverageReady,
        )
        assertFalse(
            ThorSnapshot.loading(OperationState()).copy(
                availableBootSlots = emptySet(),
                stockBackupSlots = setOf("_a", "_b"),
            ).stockBackupCoverageReady,
        )
    }

    @Test
    fun acceptsVerifiedRecoveryCoverageWhenTheAppCopyIsMissing() {
        val snapshot = ThorSnapshot.loading(OperationState()).copy(
            availableBootSlots = setOf("_a", "_b"),
            stockRecoverySlots = setOf("_a", "_b"),
        )
        assertTrue(
            snapshot.stockRecoveryCoverageReady,
        )
        assertFalse(snapshot.stockBackupCoverageReady)
    }

    @Test
    fun formatsThePerSlotPartitionLayoutForSafetyPrompts() {
        val snapshot = ThorSnapshot.loading(OperationState()).copy(
            availablePartitionsBySlot = mapOf(
                "_b" to setOf("boot"),
                "_a" to setOf("init_boot", "boot"),
            ),
        )

        assertEquals("_a: boot + init_boot; _b: boot", snapshot.partitionLayoutLabel)
        assertEquals("Unavailable", ThorSnapshot.loading(OperationState()).partitionLayoutLabel)
    }

    @Test
    fun selectPartitionUsesInitBootWhenBothPartitionsExist() {
        assertEquals("init_boot", PatchUtils.selectPartition(initBootAvailable = true, bootAvailable = true))
        assertEquals("init_boot", PatchUtils.selectPartition(initBootAvailable = true, bootAvailable = false))
        assertEquals("boot", PatchUtils.selectPartition(initBootAvailable = false, bootAvailable = true))
        assertNull(PatchUtils.selectPartition(initBootAvailable = false, bootAvailable = false))
    }

    @Test
    fun acceptsOnlyAStableNormalizedSlot() {
        assertEquals("_a", PatchUtils.normalizeStableSlot("a", "_a"))
        assertEquals("_b", PatchUtils.normalizeStableSlot("_b", "b"))
        assertNull(PatchUtils.normalizeStableSlot("_a", "_b"))
        assertNull(PatchUtils.normalizeStableSlot("unknown", "_a"))
    }
}
