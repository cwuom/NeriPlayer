package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.execution.DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressPresentationTest {

    @Test
    fun `p0 batch keeps fixed total and completed watermark after task rows disappear`() {
        val songs = (1L..800L).map(::song)
        val completedKeys = songs.take(300).mapTo(linkedSetOf(), SongItem::stableKey)
        val presentation = BatchDownloadPresentationState(
            id = 100L,
            memberAttemptIds = songs.associate { it.stableKey() to null },
            terminalStates = completedKeys.associateWith {
                BatchDownloadTerminalState.COMPLETED
            },
            initiallyCompletedSongKeys = completedKeys
        )

        val beforePrune = aggregateBatchDownloadProgress(
            presentation,
            songs.drop(300).map { song ->
                DownloadTask(song, null, DownloadStatus.QUEUED)
            }
        )
        val afterPrune = aggregateBatchDownloadProgress(presentation, emptyList())

        assertEquals(800, beforePrune?.totalSongs)
        assertEquals(300, beforePrune?.completedSongs)
        assertEquals(800, afterPrune?.totalSongs)
        assertEquals(300, afterPrune?.completedSongs)
    }

    @Test
    fun `p0 incomplete snapshot does not clear initially completed members`() {
        val completed = (1L..300L).map(::song)
        val pending = (301L..800L).map(::song)
        val completedKeys = completed.mapTo(linkedSetOf(), SongItem::stableKey)
        val presentation = BatchDownloadPresentationState(
            id = 101L,
            memberAttemptIds = (completed + pending).associate { it.stableKey() to null },
            terminalStates = completedKeys.associateWith {
                BatchDownloadTerminalState.COMPLETED
            },
            initiallyCompletedSongKeys = completedKeys
        )

        // An incomplete directory snapshot is represented by no task rows here;
        // confirmed terminal members must remain part of the durable presentation.
        val aggregate = requireNotNull(aggregateBatchDownloadProgress(presentation, emptyList()))

        assertEquals(800, aggregate.totalSongs)
        assertEquals(300, aggregate.completedSongs)
    }

    @Test
    fun `p0 old attempt cannot change the new attempt progress`() {
        val selected = song(801L)
        val presentation = BatchDownloadPresentationState(
            id = 102L,
            memberAttemptIds = mapOf(selected.stableKey() to 2L)
        )
        val oldAttempt = DownloadTask(
            selected,
            progress(selected.stableKey(), 1L, bytesRead = 100L),
            DownloadStatus.DOWNLOADING,
            attemptId = 1L
        )

        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(presentation, listOf(oldAttempt))
        )

        assertEquals(0, aggregate.completedSongs)
        assertEquals(0, aggregate.percentage)
    }

    @Test
    fun `p0 repeated stable key contributes one member`() {
        val first = song(802L, name = "first")
        val duplicate = first.copy(name = "same identity, different fields")
        val firstBatch = BatchDownloadPresentationState(
            id = 103L,
            memberAttemptIds = mapOf(first.stableKey() to 1L)
        )
        val secondBatch = BatchDownloadPresentationState(
            id = 104L,
            memberAttemptIds = mapOf(duplicate.stableKey() to 2L)
        )

        val merged = requireNotNull(
            mergeBatchDownloadPresentations(listOf(firstBatch, secondBatch), emptyList())
        )
        val aggregate = requireNotNull(
            aggregateBatchDownloadProgress(listOf(firstBatch, secondBatch), emptyList())
        )

        assertEquals(1, merged.memberAttemptIds.size)
        assertEquals(1, aggregate.totalSongs)
    }

    @Test
    fun `progress page hides queued and waiting songs before work starts`() {
        val queued = DownloadTask(
            song = song(1L),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 1L
        )
        val waiting = DownloadTask(
            song = song(2L),
            progress = null,
            status = DownloadStatus.WAITING_NETWORK,
            attemptId = 2L
        )
        val active = DownloadTask(
            song = song(3L),
            progress = progress(song(3L).stableKey(), 3L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 3L
        )

        assertEquals(
            listOf(active),
            visibleDownloadProgressTasks(listOf(queued, waiting, active))
        )
    }

    @Test
    fun `failed songs remain visible for retry without becoming pending tasks`() {
        val failed = DownloadTask(
            song = song(4L),
            progress = null,
            status = DownloadStatus.FAILED,
            attemptId = 4L
        )
        val active = DownloadTask(
            song = song(5L),
            progress = progress(song(5L).stableKey(), 5L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 5L
        )

        assertEquals(listOf(failed), visibleFailedDownloadTasks(listOf(active, failed)))
        assertEquals(1, countFailedDownloadTasks(listOf(active, failed)))
        assertEquals(1, countPendingDownloadTasks(listOf(active, failed)))
    }

    @Test
    fun `terminal incomplete batch is replaced by failure presentation`() {
        val failedBatch = BatchDownloadOverallProgress(
            totalSongs = 10,
            completedSongs = 1,
            percentage = 10,
            fraction = 0.1f,
            activeSongCount = 0,
            hasPendingSongs = false
        )
        val completedBatch = failedBatch.copy(
            completedSongs = 10,
            percentage = 100,
            fraction = 1f
        )

        assertNull(batchDownloadProgressForDisplay(failedBatch))
        assertEquals(completedBatch, batchDownloadProgressForDisplay(completedBatch))
    }

    @Test
    fun `visible tasks keep real transfer ahead of waits and queue`() {
        val waitingSong = song(2L)
        val queuedBeforeActive = DownloadTask(
            song = song(1L),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 1L
        )
        val waitingWithProgress = DownloadTask(
            song = waitingSong,
            progress = progress(waitingSong.stableKey(), 2L, bytesRead = 20L),
            status = DownloadStatus.WAITING_NETWORK,
            attemptId = 2L
        )
        val active = DownloadTask(
            song = song(3L),
            progress = progress(song(3L).stableKey(), 3L, bytesRead = 0L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 3L
        )
        val resolving = DownloadTask(
            song = song(5L),
            progress = progress(
                songKey = song(5L).stableKey(),
                attemptId = 5L,
                bytesRead = 0L
            ).copy(stage = AudioDownloadManager.DownloadStage.RESOLVING_SOURCE),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 5L
        )
        val queuedAfterActive = DownloadTask(
            song = song(4L),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 4L
        )

        val visible = visibleDownloadProgressTasks(
            listOf(queuedBeforeActive, waitingWithProgress, active, resolving, queuedAfterActive)
        )

        assertEquals(
            listOf(active, resolving, waitingWithProgress),
            visible
        )
    }

    @Test
    fun `downloading without a stage does not outrank real transfer`() {
        val transfer = DownloadTask(
            song = song(1L),
            progress = progress(song(1L).stableKey(), 1L, bytesRead = 1L),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 1L
        )
        val unhydrated = DownloadTask(
            song = song(2L),
            progress = null,
            status = DownloadStatus.DOWNLOADING,
            attemptId = 2L
        )

        assertEquals(
            listOf(transfer),
            visibleDownloadProgressTasks(listOf(unhydrated, transfer))
        )
        assertTrue(hasDownloadTaskStartedWork(transfer))
        assertFalse(hasDownloadTaskStartedWork(unhydrated))
    }

    @Test
    fun `every downloading task stays ahead of queued and network waiting tasks`() {
        val unhydrated = DownloadTask(
            song = song(1L),
            progress = null,
            status = DownloadStatus.DOWNLOADING,
            attemptId = 1L
        )
        val sourceResolving = DownloadTask(
            song = song(2L),
            progress = progress(song(2L).stableKey(), 2L, bytesRead = 0L).copy(
                stage = AudioDownloadManager.DownloadStage.RESOLVING_SOURCE
            ),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 2L
        )
        val networkWaiting = DownloadTask(
            song = song(3L),
            progress = null,
            status = DownloadStatus.WAITING_NETWORK,
            attemptId = 3L
        )
        val queued = DownloadTask(
            song = song(4L),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 4L
        )

        assertEquals(
            listOf(sourceResolving),
            visibleDownloadProgressTasks(
                listOf(networkWaiting, queued, sourceResolving, unhydrated)
            )
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
        assertEquals(41, aggregate.percentage)
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
    fun `new batch shows immediate zero progress before its task store hydration completes`() {
        val first = song(1L)
        val second = song(2L)
        val third = song(3L)
        val presentation = BatchDownloadPresentationState(
            id = 22L,
            memberAttemptIds = mapOf(
                first.stableKey() to null,
                second.stableKey() to null,
                third.stableKey() to null
            )
        )

        val aggregate = requireNotNull(aggregateBatchDownloadProgress(presentation, emptyList()))

        assertEquals(3, aggregate.totalSongs)
        assertEquals(0, aggregate.completedSongs)
        assertEquals(0, aggregate.percentage)
        assertEquals(0, aggregate.activeSongCount)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `batch progress counts catalog completed members before task hydration`() {
        val first = song(1L)
        val second = song(2L)
        val third = song(3L)
        val presentation = BatchDownloadPresentationState(
            id = 23L,
            memberAttemptIds = mapOf(
                first.stableKey() to null,
                second.stableKey() to null,
                third.stableKey() to null
            ),
            terminalStates = mapOf(
                first.stableKey() to BatchDownloadTerminalState.COMPLETED,
                second.stableKey() to BatchDownloadTerminalState.COMPLETED
            ),
            maximumObservedFractions = mapOf(
                first.stableKey() to 1f,
                second.stableKey() to 1f
            ),
            initiallyCompletedSongKeys = setOf(
                first.stableKey(),
                second.stableKey()
            )
        )

        val aggregate = requireNotNull(aggregateBatchDownloadProgress(presentation, emptyList()))

        assertEquals(3, aggregate.totalSongs)
        assertEquals(2, aggregate.completedSongs)
        assertEquals(66, aggregate.percentage)
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
        assertEquals(99, aggregate.percentage)
        assertEquals(1, aggregate.activeSongCount)
        assertTrue(aggregate.hasPendingSongs)
    }

    @Test
    fun `batch progress reserves a visible tail for durable finalization`() {
        val selected = song(1L)
        val base = progress(
            songKey = selected.stableKey(),
            attemptId = 7L,
            bytesRead = 100L
        )

        assertEquals(0.90f, downloadProgressFraction(base), 0.0001f)
        assertEquals(
            0.92f,
            downloadProgressFraction(
                base.copy(stage = AudioDownloadManager.DownloadStage.VERIFYING_AUDIO)
            ),
            0.0001f
        )
        assertEquals(
            0.94f,
            downloadProgressFraction(
                base.copy(stage = AudioDownloadManager.DownloadStage.COMMITTING_CORE)
            ),
            0.0001f
        )
        assertEquals(
            0.97f,
            downloadProgressFraction(
                base.copy(stage = AudioDownloadManager.DownloadStage.ASSETS_ENRICHING)
            ),
            0.0001f
        )
        assertEquals(
            0.99f,
            downloadProgressFraction(
                base.copy(stage = AudioDownloadManager.DownloadStage.FINALIZING)
            ),
            0.0001f
        )
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
        assertEquals(11, aggregate.percentage)
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
            maximumObservedFractions = mapOf(selected.stableKey() to 1f),
            initiallyCompletedSongKeys = setOf(selected.stableKey())
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
        assertEquals(
            RecoveredDownloadProgress(bytesRead = 0L, totalBytes = 100L),
            resolveRecoveredDownloadProgress(
                workingFileBytes = 0L,
                checkpointTotalBytes = 100L,
                checkpointBytesWritten = 0L
            )
        )
        assertEquals(
            RecoveredDownloadProgress(bytesRead = 42L, totalBytes = 0L),
            resolveRecoveredDownloadProgress(
                workingFileBytes = 0L,
                checkpointTotalBytes = null,
                checkpointBytesWritten = 42L
            )
        )
        assertEquals(
            RecoveredDownloadProgress(bytesRead = 20L, totalBytes = 100L),
            resolveRecoveredDownloadProgress(
                workingFileBytes = 20L,
                checkpointTotalBytes = 100L,
                checkpointBytesWritten = 42L
            )
        )
        assertEquals(
            RecoveredDownloadProgress(bytesRead = 0L, totalBytes = 100L),
            resolveRecoveredDownloadProgress(
                workingFileBytes = 20L,
                checkpointTotalBytes = 100L,
                checkpointBytesWritten = 0L
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
    fun `restarted queue exposes the durable reason before bytes are known`() {
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST
            ),
            recoveredDownloadTaskPresentation(
                operationState = "QUEUED",
                stopRequestedByUser = false,
                batchStateBits = DownloadBatchState.OPEN
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.WAITING_NETWORK,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
            ),
            recoveredDownloadTaskPresentation(
                operationState = "QUEUED",
                stopRequestedByUser = false,
                batchStateBits = DownloadBatchState.OPEN or DownloadBatchState.NETWORK_WAIT
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP
            ),
            recoveredDownloadTaskPresentation(
                operationState = "WAITING_STORAGE_MUTATION",
                stopRequestedByUser = false,
                batchStateBits = null
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST
            ),
            recoveredDownloadTaskPresentation(
                operationState = "ASSETS_ENRICHING",
                stopRequestedByUser = false,
                batchStateBits = null
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST
            ),
            recoveredDownloadTaskPresentation(
                operationState = "DEGRADED_COMPLETE",
                stopRequestedByUser = false,
                batchStateBits = null
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_HOST
            ),
            recoveredDownloadTaskPresentation(
                operationState = "DEGRADED_COMPLETE",
                stopRequestedByUser = false,
                batchStateBits = null,
                nextRetryAtMs = 2_000L,
                nowMs = 1_000L
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
            ),
            recoveredDownloadTaskPresentation(
                operationState = "RETRYABLE",
                stopRequestedByUser = false,
                batchStateBits = null,
                nextRetryAtMs = 2_000L,
                nowMs = 1_000L
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
            ),
            recoveredDownloadTaskPresentation(
                operationState = "RETRYABLE",
                stopRequestedByUser = false,
                batchStateBits = null,
                nextRetryAtMs = 500L,
                nowMs = 1_000L
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.WAITING_NETWORK,
                stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
            ),
            recoveredDownloadTaskPresentation(
                operationState = "RETRYABLE",
                stopRequestedByUser = false,
                batchStateBits = null,
                lastErrorCode = "NETWORK_POLICY_WAITING"
            )
        )
        assertEquals(
            RecoveredDownloadTaskPresentation(
                status = DownloadStatus.QUEUED,
                stage = AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP
            ),
            recoveredDownloadTaskPresentation(
                operationState = "RETRYABLE",
                stopRequestedByUser = false,
                batchStateBits = null,
                lastErrorCode = DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
            )
        )
        assertNull(
            recoveredDownloadTaskPresentation(
                operationState = "STOPPED",
                stopRequestedByUser = true,
                batchStateBits = null
            )
        )
    }

    @Test
    fun `pending working progress prefers operation and falls back to stable song key`() {
        val snapshot = buildPendingWorkingProgressSnapshot(
            listOf(
                PendingWorkingProgressRecord(
                    operationId = "op-a",
                    stableKey = "song-a",
                    bytesWritten = 40L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-a",
                    stableKey = "song-a",
                    bytesWritten = 70L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-b",
                    stableKey = "song-a",
                    bytesWritten = 90L
                ),
                PendingWorkingProgressRecord(
                    operationId = null,
                    stableKey = "song-a",
                    bytesWritten = 80L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-a",
                    stableKey = "song-b",
                    bytesWritten = 100L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-conflict",
                    stableKey = "song-c",
                    bytesWritten = 33L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-conflict",
                    stableKey = "song-d",
                    bytesWritten = 44L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-zero",
                    stableKey = "song-zero",
                    bytesWritten = 0L
                ),
                PendingWorkingProgressRecord(
                    operationId = "op-negative",
                    stableKey = "song-negative",
                    bytesWritten = -1L
                )
            )
        )

        assertEquals(70L, snapshot.workingFileBytes("op-a", "song-a"))
        assertEquals(90L, snapshot.workingFileBytes("missing", "song-a"))
        assertEquals(90L, snapshot.workingFileBytes(null, "song-a"))
        assertEquals(100L, snapshot.workingFileBytes("op-a", "song-b"))
        assertEquals(33L, snapshot.workingFileBytes("op-conflict", "song-c"))
        assertEquals(44L, snapshot.workingFileBytes("op-conflict", "song-d"))
        assertEquals(0L, snapshot.workingFileBytes("op-zero", "song-zero"))
        assertEquals(0L, snapshot.workingFileBytes("op-negative", "song-negative"))
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

    private fun song(id: Long, name: String = "Song $id"): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "https://example.com/$id"
        )
    }
}
