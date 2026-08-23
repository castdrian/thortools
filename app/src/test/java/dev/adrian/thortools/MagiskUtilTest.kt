package dev.adrian.thortools

import android.app.DownloadManager
import dev.adrian.thortools.utils.MagiskDownloadState
import dev.adrian.thortools.utils.MagiskUtil
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
