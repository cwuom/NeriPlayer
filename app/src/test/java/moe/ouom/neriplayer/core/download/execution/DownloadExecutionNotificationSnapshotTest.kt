package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.BatchDownloadOverallProgress
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionNotificationSnapshotTest {
    @Test
    fun `batch progress exposes completed total remaining and current transfer`() {
        val song = sampleSong("第一首")
        val progress = AudioDownloadManager.DownloadProgress(
            songKey = song.sourceStableKey.orEmpty(),
            songId = song.id,
            fileName = "first.mp3",
            bytesRead = 5L,
            totalBytes = 10L,
            speedBytesPerSec = 1L
        )
        val snapshot = deriveDownloadExecutionNotificationSnapshot(
            tasks = listOf(
                DownloadTask(
                    song = song,
                    progress = progress,
                    status = DownloadStatus.DOWNLOADING,
                    attemptId = 1L
                )
            ),
            batchProgress = BatchDownloadOverallProgress(
                totalSongs = 4,
                completedSongs = 1,
                percentage = 37,
                fraction = 0.37f,
                activeSongCount = 1,
                hasPendingSongs = true
            ),
            hasActiveOperations = true
        )

        assertEquals(4, snapshot.totalSongs)
        assertEquals(1, snapshot.completedSongs)
        assertEquals(3, snapshot.remainingSongs)
        assertEquals(37, snapshot.overallPercentage)
        assertEquals("第一首", snapshot.currentSong)
        assertTrue(snapshot.currentTransfer.orEmpty().contains("50%"))
        assertTrue(snapshot.hasWork)
    }

    @Test
    fun `unknown content length keeps the overall bar indeterminate`() {
        val song = sampleSong("未知长度")
        val snapshot = deriveDownloadExecutionNotificationSnapshot(
            tasks = listOf(
                DownloadTask(
                    song = song,
                    progress = AudioDownloadManager.DownloadProgress(
                        songKey = song.sourceStableKey.orEmpty(),
                        songId = song.id,
                        fileName = "unknown.mp3",
                        bytesRead = 128L,
                        totalBytes = 0L,
                        speedBytesPerSec = 0L
                    ),
                    status = DownloadStatus.DOWNLOADING,
                    attemptId = 2L
                )
            ),
            batchProgress = null,
            hasActiveOperations = true
        )

        assertEquals(1, snapshot.totalSongs)
        assertEquals(0, snapshot.completedSongs)
        assertEquals(1, snapshot.remainingSongs)
        assertNull(snapshot.overallPercentage)
        assertTrue(snapshot.hasWork)
    }

    @Test
    fun `batch counts stay internally consistent when source completion is out of range`() {
        val snapshot = deriveDownloadExecutionNotificationSnapshot(
            tasks = emptyList(),
            batchProgress = BatchDownloadOverallProgress(
                totalSongs = 3,
                completedSongs = 9,
                percentage = 100,
                fraction = 1f,
                activeSongCount = 0,
                hasPendingSongs = false
            ),
            hasActiveOperations = false
        )

        assertEquals(3, snapshot.completedSongs)
        assertEquals(0, snapshot.remainingSongs)
        assertFalse(snapshot.hasWork)
    }

    private fun sampleSong(name: String): SongItem {
        return SongItem(
            id = 42L,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            sourceStableKey = "test:$name"
        )
    }
}
