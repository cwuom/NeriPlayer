package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloadManagerTest {

    @Test
    fun `batch download parallelism is capped at six workers`() {
        assertEquals(6, AudioDownloadManager.DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        assertEquals(1, AudioDownloadManager.clampBatchDownloadParallelism(0))
        assertEquals(4, AudioDownloadManager.clampBatchDownloadParallelism(4))
        assertEquals(6, AudioDownloadManager.clampBatchDownloadParallelism(9))
    }

    @Test
    fun `shouldFetchRemoteLyricForDownload only fetches when local override is absent`() {
        assertEquals(true, AudioDownloadManager.shouldFetchRemoteLyricForDownload(null))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload(""))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload("   "))
        assertEquals(
            false,
            AudioDownloadManager.shouldFetchRemoteLyricForDownload("[00:00.00]local lyric")
        )
    }

    @Test
    fun `resolveLocalLyricForDownload keeps explicit lyrics and preserves cleared state separately`() {
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(null))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(""))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload("   "))
        assertEquals(
            "[00:00.00]translated",
            AudioDownloadManager.resolveLocalLyricForDownload("[00:00.00]translated")
        )
    }
}
