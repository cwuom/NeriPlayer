package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `finalizing transfer remains pending without being counted as a completed song`() {
        val selected = song(1L)
        val presentation = BatchDownloadPresentationState(
            id = 3L,
            memberAttemptIds = mapOf(selected.stableKey() to 7L)
        )
        val finalizing = progress(
            songKey = selected.stableKey(),
            attemptId = 7L,
            bytesRead = 99L
        ).copy(stage = AudioDownloadManager.DownloadStage.FINALIZING)

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(
                presentation,
                listOf(
                    DownloadTask(
                        song = selected,
                        progress = finalizing,
                        status = DownloadStatus.DOWNLOADING,
                        attemptId = 7L
                    )
                )
            )
        )

        assertEquals(0, aggregate.completedSongs)
        assertEquals(100, aggregate.percentage)
        assertEquals(1, aggregate.activeSongCount)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `failed batch item retains its highest observed fraction`() {
        val selected = song(1L)
        val presentation = BatchDownloadPresentationState(
            id = 4L,
            memberAttemptIds = mapOf(selected.stableKey() to 8L),
            terminalStates = mapOf(selected.stableKey() to BatchDownloadTerminalState.FAILED),
            maximumObservedFractions = mapOf(selected.stableKey() to 0.75f)
        )

        val aggregate = requireNotNull(aggregateBatchDownloadProgress(presentation, emptyList()))

        assertEquals(0, aggregate.completedSongs)
        assertEquals(75, aggregate.percentage)
        assertFalse(aggregate.hasPendingSongs)
    }

    @Test
    fun `retrying a failed batch item restores pending state without losing its watermark`() {
        val selected = song(1L)
        val failedPresentation = BatchDownloadPresentationState(
            id = 5L,
            memberAttemptIds = mapOf(selected.stableKey() to 8L),
            terminalStates = mapOf(selected.stableKey() to BatchDownloadTerminalState.FAILED),
            maximumObservedFractions = mapOf(selected.stableKey() to 0.75f)
        )
        val resumedPresentation = resumeBatchDownloadPresentationForRetry(
            presentation = failedPresentation,
            songKey = selected.stableKey(),
            attemptId = 8L
        )
        val retryingTask = DownloadTask(
            song = selected,
            progress = progress(selected.stableKey(), 8L, bytesRead = 20L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 8L
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(resumedPresentation, listOf(retryingTask))
        )

        assertNull(resumedPresentation.terminalStates[selected.stableKey()])
        assertEquals(75, aggregate.percentage)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `batch progress never falls below its retained task watermark`() {
        val selected = song(1L)
        val presentation = BatchDownloadPresentationState(
            id = 6L,
            memberAttemptIds = mapOf(selected.stableKey() to 9L),
            maximumObservedFractions = mapOf(selected.stableKey() to 0.8f)
        )
        val regressiveTask = DownloadTask(
            song = selected,
            progress = progress(selected.stableKey(), 9L, bytesRead = 20L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 9L
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(presentation, listOf(regressiveTask))
        )

        assertEquals(80, aggregate.percentage)
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
    fun `separate playlist batches share one overall progress denominator`() {
        val first = song(1L)
        val second = song(2L)
        val third = song(3L)
        val fourth = song(4L)
        val firstBatch = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = mapOf(first.stableKey() to 1L, second.stableKey() to 2L)
        )
        val secondBatch = BatchDownloadPresentationState(
            id = 2L,
            memberAttemptIds = mapOf(third.stableKey() to 3L, fourth.stableKey() to 4L)
        )
        val tasks = listOf(
            DownloadTask(
                song = first,
                progress = progress(first.stableKey(), 1L, bytesRead = 50L),
                status = DownloadStatus.DOWNLOADING,
                attemptId = 1L
            ),
            DownloadTask(second, null, DownloadStatus.QUEUED, attemptId = 2L),
            DownloadTask(third, null, DownloadStatus.QUEUED, attemptId = 3L),
            DownloadTask(fourth, null, DownloadStatus.QUEUED, attemptId = 4L)
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(listOf(firstBatch, secondBatch), tasks)
        )

        assertEquals(4, aggregate.totalSongs)
        assertEquals(12, aggregate.percentage)
        assertEquals(1, aggregate.activeSongCount)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `overlapping newer batch does not inherit an older completed membership`() {
        val selected = song(1L)
        val completedBatch = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = mapOf(selected.stableKey() to 1L),
            terminalStates = mapOf(selected.stableKey() to BatchDownloadTerminalState.COMPLETED),
            maximumObservedFractions = mapOf(selected.stableKey() to 1f)
        )
        val retryBatch = BatchDownloadPresentationState(
            id = 2L,
            memberAttemptIds = mapOf(selected.stableKey() to 2L)
        )
        val retryTask = DownloadTask(
            song = selected,
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 2L
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(
                presentations = listOf(completedBatch, retryBatch),
                tasks = listOf(retryTask)
            )
        )

        assertEquals(1, aggregate.totalSongs)
        assertEquals(0, aggregate.completedSongs)
        assertEquals(0, aggregate.percentage)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `overlapping batches retain the shared attempt progress watermark`() {
        val selected = song(1L)
        val firstBatch = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = mapOf(selected.stableKey() to 9L),
            maximumObservedFractions = mapOf(selected.stableKey() to 0.8f)
        )
        val secondBatch = BatchDownloadPresentationState(
            id = 2L,
            memberAttemptIds = mapOf(selected.stableKey() to 9L)
        )
        val regressiveTask = DownloadTask(
            song = selected,
            progress = progress(selected.stableKey(), 9L, bytesRead = 20L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 9L
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(
                presentations = listOf(firstBatch, secondBatch),
                tasks = listOf(regressiveTask)
            )
        )

        assertEquals(80, aggregate.percentage)
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

    @Test
    fun `transfer presentation includes percent sizes and byte based speed`() {
        val mebibyte = 1024L * 1024L
        val text = formatDownloadTransferProgress(
            AudioDownloadManager.DownloadProgress(
                songKey = "song-key",
                songId = 1L,
                fileName = "song.mp3",
                bytesRead = 3L * mebibyte,
                totalBytes = 8L * mebibyte,
                speedBytesPerSec = mebibyte + mebibyte / 2L
            )
        )

        assertTrue(text.startsWith("37% · 3"))
        assertTrue(text.contains(" / 8"))
        assertTrue(text.endsWith("MB/s"))
    }

    @Test
    fun `transfer presentation keeps an unknown total honest`() {
        val mebibyte = 1024L * 1024L
        val text = formatDownloadTransferProgress(
            AudioDownloadManager.DownloadProgress(
                songKey = "song-key",
                songId = 1L,
                fileName = "song.mp3",
                bytesRead = 3L * mebibyte,
                totalBytes = 0L,
                speedBytesPerSec = mebibyte
            )
        )

        assertFalse(text.contains('%'))
        assertFalse(text.contains(" / "))
        assertTrue(text.contains("MB"))
        assertTrue(text.endsWith("MB/s"))
    }

    @Test
    fun `retained transfer presentation hides stale speed and normalizes invalid bytes`() {
        val text = formatDownloadTransferProgress(
            AudioDownloadManager.DownloadProgress(
                songKey = "song-key",
                songId = 1L,
                fileName = "song.mp3",
                bytesRead = -1L,
                totalBytes = -1L,
                speedBytesPerSec = 1024L * 1024L,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
            ),
            showSpeed = false
        )

        assertEquals("0 B", text)
        assertFalse(text.contains("/s"))
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
