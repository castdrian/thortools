package dev.adrian.thortools

import android.app.DownloadManager
import dev.adrian.thortools.utils.MagiskDownloadState
import dev.adrian.thortools.utils.MagiskUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MagiskUtilTest {
    @Test
    fun classifiesMagiskDownloadStates() {
        assertEquals(MagiskDownloadState.PENDING, MagiskUtil.classifyDownloadStatus(DownloadManager.STATUS_PENDING))
        assertEquals(MagiskDownloadState.PENDING, MagiskUtil.classifyDownloadStatus(DownloadManager.STATUS_RUNNING))
        assertEquals(MagiskDownloadState.PENDING, MagiskUtil.classifyDownloadStatus(DownloadManager.STATUS_PAUSED))
        assertEquals(MagiskDownloadState.READY, MagiskUtil.classifyDownloadStatus(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals(MagiskDownloadState.FAILED, MagiskUtil.classifyDownloadStatus(DownloadManager.STATUS_FAILED))
        assertEquals(MagiskDownloadState.NONE, MagiskUtil.classifyDownloadStatus(-1))
    }

    @Test
    fun requeuesMissingOrFailedMagiskDownloadsWithoutInterruptingPendingOnes() {
        assertTrue(MagiskUtil.shouldEnqueueDownload(MagiskDownloadState.NONE, false))
        assertTrue(MagiskUtil.shouldEnqueueDownload(MagiskDownloadState.FAILED, false))
        assertTrue(MagiskUtil.shouldEnqueueDownload(MagiskDownloadState.READY, false))
        assertFalse(MagiskUtil.shouldEnqueueDownload(MagiskDownloadState.READY, true))
        assertFalse(MagiskUtil.shouldEnqueueDownload(MagiskDownloadState.PENDING, false))
    }

    @Test
    fun acceptsOnlyTheMagiskPackageForInstallation() {
        assertTrue(MagiskUtil.isExpectedMagiskPackage(MagiskUtil.MAGISK_PACKAGE_NAME))
        assertFalse(MagiskUtil.isExpectedMagiskPackage("com.example.unrelated"))
        assertFalse(MagiskUtil.isExpectedMagiskPackage(null))
    }

    @Test
    fun validatesTheDownloadedArchiveBeforeOpeningTheInstaller() {
        val source = File("src/main/java/dev/adrian/thortools/utils/MagiskUtil.kt").readText()
        assertTrue(source.contains("getPackageArchiveInfo"))
        assertTrue(source.contains("FileProvider.getUriForFile"))
        assertTrue(source.contains("The downloaded file is not a valid Magisk APK"))
    }
}
