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
