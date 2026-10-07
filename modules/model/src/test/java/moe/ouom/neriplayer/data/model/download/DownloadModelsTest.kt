package moe.ouom.neriplayer.data.model.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModelsTest {
    private fun progress(
        bytesRead: Long,
        totalBytes: Long,
        stage: DownloadStage = DownloadStage.TRANSFERRING
    ) = DownloadProgress(
        songKey = "netease:1",
        songId = 1L,
        fileName = "song.flac",
        bytesRead = bytesRead,
        totalBytes = totalBytes,
        speedBytesPerSec = 0L,
        stage = stage
    )

    @Test
    fun `single song percentage reserves one hundred for finished transfers`() {
        assertEquals(100, progress(0L, 0L, DownloadStage.FINALIZING).percentage)
        assertEquals(-1, progress(10L, 0L).percentage)
        assertEquals(100, progress(2_000L, 1_000L).percentage)
        assertEquals(99, progress(999_999L, 1_000_000L).percentage)
        assertEquals(25, progress(50L, 200L).percentage)
    }

    @Test
    fun `batch percentage prefers the aggregate fraction`() {
        assertEquals(0, BatchDownloadProgress(0, 0, "", null, aggregateProgressFraction = 0.5f).percentage)
        assertEquals(50, BatchDownloadProgress(4, 1, "song", null, aggregateProgressFraction = 0.5f).percentage)
        assertEquals(99, BatchDownloadProgress(4, 3, "song", null, aggregateProgressFraction = 1.5f).percentage)
        assertEquals(100, BatchDownloadProgress(4, 4, "song", null, aggregateProgressFraction = 0.2f).percentage)
    }

    @Test
    fun `batch percentage without an aggregate adds the current song share`() {
        assertEquals(37, BatchDownloadProgress(4, 1, "song", progress(50L, 100L)).percentage)
        assertEquals(25, BatchDownloadProgress(4, 1, "song", progress(50L, 0L)).percentage)
        assertEquals(25, BatchDownloadProgress(4, 1, "song", null).percentage)
        assertEquals(100, BatchDownloadProgress(4, 4, "song", null).percentage)
    }

    @Test
    fun `manager entry stays reachable for pending, active or failed work`() {
        assertFalse(DownloadTaskSummary().hasDownloadManagerEntry)
        assertTrue(DownloadTaskSummary(pendingTaskCount = 1).hasDownloadManagerEntry)
        assertTrue(DownloadTaskSummary(hasActiveOperations = true).hasDownloadManagerEntry)
        assertTrue(DownloadTaskSummary(failedTaskCount = 2).hasDownloadManagerEntry)
    }

    @Test
    fun `persisted embedding states parse ignoring case and padding`() {
        assertEquals(
            DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            DownloadedAudioEmbeddingState.fromPersisted(" embedded_verified ")
        )
        assertNull(DownloadedAudioEmbeddingState.fromPersisted(null))
        assertNull(DownloadedAudioEmbeddingState.fromPersisted("  "))
        assertNull(DownloadedAudioEmbeddingState.fromPersisted("embedded"))
    }

    @Test
    fun `deletion identity prefers a non blank media uri over the file path`() {
        val song = DownloadedSong(
            id = 1L, name = "name", artist = "artist", album = "album",
            filePath = "/music/name.flac", fileSize = 1L, downloadTime = 0L
        )

        assertEquals("content://media/1", song.copy(mediaUri = "content://media/1").deletionIdentity())
        assertEquals("/music/name.flac", song.copy(mediaUri = " ").deletionIdentity())
        assertEquals("/music/name.flac", song.deletionIdentity())
    }
}
