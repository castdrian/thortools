package dev.adrian.thortools

import dev.adrian.thortools.utils.RecoveryImageRecord
import dev.adrian.thortools.utils.RecoveryImageStatus
import dev.adrian.thortools.utils.RecoveryManifestStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryManifestTest {
    private val record = RecoveryImageRecord(
        fileName = "boot_a.img",
        slot = "_a",
        partition = "boot",
        patched = false,
        size = 8,
        sha256 = "hash",
        buildIdentity = "fingerprint",
    )

    @Test
    fun matchesOnlyTheRecordedRecoveryContext() {
        assertTrue(record.matches("_a", "boot", false, "fingerprint"))
        assertFalse(record.matches("_b", "boot", false, "fingerprint"))
        assertFalse(record.matches("_a", "init_boot", false, "fingerprint"))
        assertFalse(record.matches("_a", "boot", true, "fingerprint"))
        assertFalse(record.matches("_a", "boot", false, "other-fingerprint"))
        assertFalse(record.matches("_a", "boot", false, ""))
    }

    @Test
    fun selectsTheNewestCurrentBuildRecordOverStaleDuplicates() {
        val selected = RecoveryManifestStore.selectRecord(
            records = listOf(
                record.copy(sha256 = "stale", buildIdentity = "old-fingerprint"),
                record.copy(sha256 = "first-current"),
                record.copy(sha256 = "newest-current"),
            ),
            slot = "_a",
            partition = "boot",
            patched = false,
            buildIdentity = "fingerprint",
        )

        assertEquals("newest-current", selected?.sha256)
    }

    @Test
    fun ignoresRecordsFromOtherPartitionsAndBuilds() {
        val selected = RecoveryManifestStore.selectRecord(
            records = listOf(
                record.copy(partition = "init_boot"),
                record.copy(buildIdentity = "old-fingerprint"),
            ),
            slot = "_a",
            partition = "boot",
            patched = false,
            buildIdentity = "fingerprint",
        )

        assertEquals(null, selected)
    }

    @Test
    fun distinguishesRestoreAvailabilityFromCompleteStockRedundancy() {
        val status = RecoveryImageStatus(
            record = record,
            localPath = "/recovery/boot_a.img",
            downloadPath = "/sdcard/Download/boot_a.img",
            localCopyVerified = false,
            downloadCopyVerified = true,
            currentBuild = true,
        )

        assertTrue(status.stockRestoreSourceAvailable)
        assertFalse(status.stockBackupPairComplete)
        assertFalse(status.patchedImageReady)
    }

    @Test
    fun requiresTheAppCopyForPatchedImageReadiness() {
        val status = RecoveryImageStatus(
            record = record.copy(patched = true, sourceSha256 = "stock-hash"),
            localPath = "/recovery/boot_patched_a.img",
            downloadPath = "/sdcard/Download/boot_patched_a.img",
            localCopyVerified = false,
            downloadCopyVerified = true,
            currentBuild = true,
        )

        assertFalse(status.stockRestoreSourceAvailable)
        assertFalse(status.stockBackupPairComplete)
        assertFalse(status.patchedImageReady)
        assertTrue(
            status.copy(localCopyVerified = true).patchedImageReady,
        )
    }

    @Test
    fun requiresTheRecordedStockSourceForPatchedImageReadiness() {
        val status = RecoveryImageStatus(
            record = record.copy(patched = true, sourceSha256 = "stock-hash"),
            localPath = "/recovery/boot_patched_a.img",
            downloadPath = "/sdcard/Download/boot_patched_a.img",
            localCopyVerified = true,
            downloadCopyVerified = false,
            currentBuild = true,
            sourceStockVerified = false,
        )

        assertFalse(status.patchedImageReady)
        assertTrue(status.copy(sourceStockVerified = true).patchedImageReady)
    }
}
