package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressPresentationTest {

    @Test
    fun `progress page renders only songs currently transferring`() {
        val tasks = DownloadStatus.entries.mapIndexed { index, status ->
            DownloadTask(
                song = song(index.toLong() + 1L),
                progress = null,
                status = status,
                attemptId = index.toLong() + 1L
            )
        }

        assertEquals(
            listOf(DownloadStatus.DOWNLOADING),
            visibleDownloadProgressTasks(tasks).map(DownloadTask::status)
        )
    }

    @Test
    fun `batch overall progress keeps queued songs in its stable denominator`() {
        val first = song(1L)
        val second = song(2L)
        val third = song(3L)
        val fourth = song(4L)
        val presentation = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = linkedMapOf(
                first.stableKey() to 1L,
                second.stableKey() to 2L,
                third.stableKey() to 3L,
                fourth.stableKey() to 4L
            ),
            terminalStates = mapOf(first.stableKey() to BatchDownloadTerminalState.COMPLETED)
        )
        val tasks = listOf(
            DownloadTask(
                song = second,
                progress = progress(second.stableKey(), 2L, bytesRead = 50L),
                status = DownloadStatus.DOWNLOADING,
                attemptId = 2L
            ),
            DownloadTask(
                song = third,
                progress = progress(third.stableKey(), 3L, bytesRead = 25L),
                status = DownloadStatus.DOWNLOADING,
                attemptId = 3L
            ),
            DownloadTask(
                song = fourth,
                progress = null,
                status = DownloadStatus.QUEUED,
                attemptId = 4L
            )
        )

        val aggregate = requireNotNull(aggregateBatchDownloadProgress(presentation, tasks))

        assertEquals(4, aggregate.totalSongs)
        assertEquals(1, aggregate.completedSongs)
        assertEquals(43, aggregate.percentage)
        assertEquals(2, aggregate.activeSongCount)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `queue only batch shows zero percent instead of no progress`() {
        val first = song(1L)
        val second = song(2L)
        val presentation = BatchDownloadPresentationState(
            id = 2L,
            memberAttemptIds = mapOf(first.stableKey() to 1L, second.stableKey() to 2L)
        )
        val tasks = listOf(
            DownloadTask(first, null, DownloadStatus.QUEUED, attemptId = 1L),
            DownloadTask(second, null, DownloadStatus.QUEUED, attemptId = 2L)
        )

        val aggregate = requireNotNull(aggregateBatchDownloadProgress(presentation, tasks))

        assertEquals(0, aggregate.percentage)
        assertEquals(0, aggregate.activeSongCount)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `stale task attempt cannot move a new batch overall progress`() {
        val selected = song(1L)
        val presentation = BatchDownloadPresentationState(
            id = 3L,
            memberAttemptIds = mapOf(selected.stableKey() to 9L)
        )
        val staleTask = DownloadTask(
            song = selected,
            progress = progress(selected.stableKey(), 8L, bytesRead = 100L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 8L
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(presentation, listOf(staleTask))
        )

        assertEquals(0, aggregate.percentage)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `early handoff only selects unscheduled requests up to the host window`() {
        val requests = (1L..4L).map { id ->
            QueuedDownloadRequest(song(id), attemptId = id, operationId = "operation-$id")
        }

        val selected = selectBatchRequestsForEarlyHandoff(
            pendingRequests = requests,
            scheduledSongKeys = setOf(requests.first().song.stableKey()),
            maximumHandoffs = 3
        )

        assertEquals(listOf(2L, 3L), selected.map(QueuedDownloadRequest::attemptId))
    }

    @Test
    fun `progress page ignores a stale attempt before choosing active progress`() {
        val staleTask = DownloadTask(
            song = song(1L),
            progress = progress(songKey = song(1L).stableKey(), attemptId = 3L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 4L
        )
        val activeTask = DownloadTask(
            song = song(2L),
            progress = progress(songKey = song(2L).stableKey(), attemptId = 5L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 5L
        )

        assertEquals(activeTask, activeDownloadTaskWithProgress(listOf(staleTask, activeTask)))
        assertNull(activeDownloadTaskWithProgress(listOf(staleTask)))
    }

    @Test
    fun `restored progress uses durable bytes and rejects impossible checkpoints`() {
        assertEquals(
            RecoveredDownloadProgress(bytesRead = 42L, totalBytes = 100L),
            resolveRecoveredDownloadProgress(
                workingFileBytes = 42L,
                checkpointTotalBytes = 100L
            )
        )
        assertNull(
            resolveRecoveredDownloadProgress(
                workingFileBytes = 0L,
                checkpointTotalBytes = 100L
            )
        )
        assertNull(
            resolveRecoveredDownloadProgress(
                workingFileBytes = 101L,
                checkpointTotalBytes = 100L
            )
        )
    }

    private fun progress(
        songKey: String,
        attemptId: Long,
        bytesRead: Long = 42L
    ): AudioDownloadManager.DownloadProgress {
        return AudioDownloadManager.DownloadProgress(
            songKey = songKey,
            songId = 1L,
            fileName = "song.mp3",
            bytesRead = bytesRead,
            totalBytes = 100L,
            speedBytesPerSec = 10L,
            attemptId = attemptId
        )
    }

    private fun song(id: Long): SongItem {
        return SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "https://example.com/$id"
        )
    }
}
