package moe.ouom.neriplayer.core.download.presentation

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.BatchDownloadPresentationState
import moe.ouom.neriplayer.data.model.download.BatchDownloadTerminalState
import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadStage
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import moe.ouom.neriplayer.data.model.download.DownloadTaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskPresentationStateTest {
    @Test
    fun `finalizing requires a downloading task in the finalizing stage`() {
        val track = song(1L)

        assertFalse(isDownloadTaskFinalizing(null))
        assertTrue(isDownloadTaskFinalizing(task(track, DownloadStatus.DOWNLOADING, DownloadStage.FINALIZING)))
        assertFalse(isDownloadTaskFinalizing(task(track, DownloadStatus.DOWNLOADING, DownloadStage.TRANSFERRING)))
        assertFalse(isDownloadTaskFinalizing(task(track, DownloadStatus.DOWNLOADING, stage = null)))
        assertFalse(isDownloadTaskFinalizing(task(track, DownloadStatus.QUEUED, DownloadStage.FINALIZING)))
    }

    @Test
    fun `only queued downloading and network waiting tasks are cancellable`() {
        val track = song(1L)

        assertFalse(isDownloadTaskCancellable(null))
        assertTrue(isDownloadTaskCancellable(task(track, DownloadStatus.QUEUED)))
        assertTrue(isDownloadTaskCancellable(task(track, DownloadStatus.DOWNLOADING)))
        assertTrue(isDownloadTaskCancellable(task(track, DownloadStatus.WAITING_NETWORK)))
        assertFalse(isDownloadTaskCancellable(task(track, DownloadStatus.COMPLETED)))
        assertFalse(isDownloadTaskCancellable(task(track, DownloadStatus.FAILED)))
        assertFalse(isDownloadTaskCancellable(task(track, DownloadStatus.CANCELLED)))
    }

    @Test
    fun `started work ignores waits and progress published by another attempt`() {
        val track = song(1L)
        val workingStages = listOf(
            DownloadStage.RESOLVING_SOURCE,
            DownloadStage.PREPARING_STORAGE,
            DownloadStage.TRANSFERRING,
            DownloadStage.VERIFYING_AUDIO,
            DownloadStage.COMMITTING_CORE,
            DownloadStage.ASSETS_ENRICHING,
            DownloadStage.WAITING_RETRY,
            DownloadStage.FINALIZING
        )

        workingStages.forEach { stage ->
            assertTrue(stage.name, hasDownloadTaskStartedWork(task(track, DownloadStatus.DOWNLOADING, stage)))
        }
        assertFalse(hasDownloadTaskStartedWork(task(track, DownloadStatus.QUEUED, DownloadStage.WAITING_HOST)))
        assertFalse(
            hasDownloadTaskStartedWork(task(track, DownloadStatus.QUEUED, DownloadStage.WAITING_DELETE_CLEANUP))
        )
        assertFalse(hasDownloadTaskStartedWork(task(track, DownloadStatus.QUEUED, stage = null)))
        assertFalse(
            hasDownloadTaskStartedWork(
                task(track, DownloadStatus.DOWNLOADING, DownloadStage.TRANSFERRING, progressAttemptId = 1L)
                    .copy(attemptId = 2L)
            )
        )
        assertTrue(
            hasDownloadTaskStartedWork(
                task(track, DownloadStatus.DOWNLOADING, DownloadStage.TRANSFERRING, progressAttemptId = null)
                    .copy(attemptId = 2L)
            )
        )
    }

    @Test
    fun `active progress task requires downloading status and current or unattributed progress`() {
        val queuedWithProgress = task(song(1L), DownloadStatus.QUEUED, DownloadStage.TRANSFERRING)
        val downloadingWithoutProgress = task(song(2L), DownloadStatus.DOWNLOADING, stage = null)
        val staleAttempt = task(song(3L), DownloadStatus.DOWNLOADING, DownloadStage.TRANSFERRING, 1L)
            .copy(attemptId = 2L)
        val unattributed = task(song(4L), DownloadStatus.DOWNLOADING, DownloadStage.TRANSFERRING, null)
            .copy(attemptId = 8L)

        assertNull(
            activeDownloadTaskWithProgress(listOf(queuedWithProgress, downloadingWithoutProgress, staleAttempt))
        )
        assertEquals(
            unattributed,
            activeDownloadTaskWithProgress(
                listOf(queuedWithProgress, downloadingWithoutProgress, staleAttempt, unattributed)
            )
        )
    }

    @Test
    fun `summary stabilization keeps a running single or batch job visible`() {
        val idle = DownloadTaskSummary(failedTaskCount = 1)
        val pending = DownloadTaskSummary(pendingTaskCount = 2, queuedTaskCount = 1)

        assertSame(idle, stabilizeDownloadTaskSummary(idle, isSingleDownloading = false, hasActiveBatchJobs = false))
        assertEquals(
            DownloadTaskSummary(
                pendingTaskCount = 2,
                queuedTaskCount = 1,
                hasActiveTasks = true,
                hasActiveOperations = true
            ),
            stabilizeDownloadTaskSummary(pending, isSingleDownloading = true, hasActiveBatchJobs = false)
        )
        assertEquals(
            DownloadTaskSummary(
                pendingTaskCount = 2,
                queuedTaskCount = 1,
                hasActiveTasks = false,
                hasActiveOperations = true
            ),
            stabilizeDownloadTaskSummary(pending, isSingleDownloading = false, hasActiveBatchJobs = true)
        )
        assertEquals(
            DownloadTaskSummary(failedTaskCount = 1, hasActiveTasks = false, hasActiveOperations = true),
            stabilizeDownloadTaskSummary(idle, isSingleDownloading = false, hasActiveBatchJobs = true)
        )
        assertEquals(
            DownloadTaskSummary(failedTaskCount = 1, hasActiveTasks = true, hasActiveOperations = true),
            stabilizeDownloadTaskSummary(idle, isSingleDownloading = true, hasActiveBatchJobs = true)
        )
    }

    @Test
    fun `network wait marks only matching queued or downloading attempts`() {
        val queued = task(song(1L), DownloadStatus.QUEUED).copy(attemptId = 1L)
        val downloading = task(song(2L), DownloadStatus.DOWNLOADING).copy(attemptId = 2L)
        val completed = task(song(3L), DownloadStatus.COMPLETED).copy(attemptId = 3L)
        val otherAttempt = task(song(4L), DownloadStatus.QUEUED).copy(attemptId = 4L)
        val tasks = listOf(queued, downloading, completed, otherAttempt)

        val updated = applyWaitingNetworkStatus(
            tasks,
            listOf(queued, downloading, completed, otherAttempt.copy(attemptId = 99L))
        )

        assertEquals(
            listOf(
                DownloadStatus.WAITING_NETWORK,
                DownloadStatus.WAITING_NETWORK,
                DownloadStatus.COMPLETED,
                DownloadStatus.QUEUED
            ),
            updated.map(DownloadTask::status)
        )
        assertSame(tasks, applyWaitingNetworkStatus(tasks, listOf(completed, otherAttempt.copy(attemptId = 5L))))
        assertSame(tasks, applyWaitingNetworkStatus(tasks, emptyList()))
        val noTasks = emptyList<DownloadTask>()
        assertSame(noTasks, applyWaitingNetworkStatus(noTasks, listOf(queued)))
    }

    @Test
    fun `retrying a batch member clears only its own failed terminal state`() {
        val presentation = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = mapOf("a" to 1L, "b" to 2L),
            terminalStates = mapOf(
                "a" to BatchDownloadTerminalState.FAILED,
                "b" to BatchDownloadTerminalState.COMPLETED
            )
        )

        assertEquals(
            mapOf("b" to BatchDownloadTerminalState.COMPLETED),
            resumeBatchDownloadPresentationForRetry(presentation, "a", 1L).terminalStates
        )
        assertSame(presentation, resumeBatchDownloadPresentationForRetry(presentation, "a", 7L))
        assertSame(presentation, resumeBatchDownloadPresentationForRetry(presentation, "b", 2L))
    }

    @Test
    fun `progress fraction weights transfer bytes and reserves fixed tails after transfer`() {
        val postCoreFraction = 0.97f

        assertEquals(0f, downloadProgressFraction(progress(bytesRead = 50L, totalBytes = 0L)), 0.0001f)
        assertEquals(0.45f, downloadProgressFraction(progress(50L, 100L)), 0.0001f)
        assertEquals(0.9f, downloadProgressFraction(progress(500L, 100L)), 0.0001f)
        assertEquals(0.92f, downloadProgressFraction(progress(50L, 100L, DownloadStage.VERIFYING_AUDIO)), 0.0001f)
        assertEquals(0.94f, downloadProgressFraction(progress(50L, 100L, DownloadStage.COMMITTING_CORE)), 0.0001f)
        assertEquals(
            postCoreFraction,
            downloadProgressFraction(progress(50L, 100L, DownloadStage.ASSETS_ENRICHING)),
            0.0001f
        )
        assertEquals(0.99f, downloadProgressFraction(progress(0L, 0L, DownloadStage.FINALIZING)), 0.0001f)
        assertEquals(
            postCoreFraction,
            downloadProgressFraction(progress(100L, 100L, DownloadStage.WAITING_RETRY)),
            0.0001f
        )
        assertEquals(0.45f, downloadProgressFraction(progress(50L, 100L, DownloadStage.WAITING_HOST)), 0.0001f)
        assertEquals(
            0.18f,
            downloadProgressFraction(progress(20L, 100L, DownloadStage.WAITING_DELETE_CLEANUP)),
            0.0001f
        )
    }

    @Test
    fun `settled overlapping memberships keep the newest terminal watermark`() {
        val track = song(1L)
        val key = track.stableKey()
        val older = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = mapOf(key to 1L),
            terminalStates = mapOf(key to BatchDownloadTerminalState.FAILED),
            maximumObservedFractions = mapOf(key to 0.6f)
        )
        val newer = BatchDownloadPresentationState(
            id = 2L,
            memberAttemptIds = mapOf(key to 2L),
            memberOperationIds = mapOf(key to "operation-2"),
            terminalStates = mapOf(key to BatchDownloadTerminalState.CANCELLED),
            maximumObservedFractions = mapOf(key to 0.25f),
            batchId = "batch-2",
            batchGeneration = 5L
        )

        val merged = requireNotNull(mergeBatchDownloadPresentations(listOf(older, newer), emptyList()))

        assertEquals(mapOf(key to 2L), merged.memberAttemptIds)
        assertEquals(mapOf(key to "operation-2"), merged.memberOperationIds)
        assertEquals(mapOf(key to BatchDownloadTerminalState.CANCELLED), merged.terminalStates)
        assertEquals(mapOf(key to 0.25f), merged.maximumObservedFractions)
        assertEquals("batch-2", merged.batchId)
        assertEquals(5L, merged.batchGeneration)
    }

    @Test
    fun `pending overlapping memberships follow the live task attempt`() {
        val track = song(1L)
        val key = track.stableKey()
        val first = BatchDownloadPresentationState(
            id = 1L,
            memberAttemptIds = mapOf(key to 3L),
            maximumObservedFractions = mapOf(key to 0.5f)
        )
        val second = BatchDownloadPresentationState(
            id = 2L,
            memberAttemptIds = mapOf(key to 4L, "" to 9L),
            maximumObservedFractions = mapOf(key to 0.1f)
        )
        val failedSameAttempt = BatchDownloadPresentationState(
            id = 3L,
            memberAttemptIds = mapOf(key to 3L),
            terminalStates = mapOf(key to BatchDownloadTerminalState.FAILED),
            maximumObservedFractions = mapOf(key to 0.9f)
        )
        val presentations = listOf(first, second, failedSameAttempt)
        val liveTask = task(track, DownloadStatus.DOWNLOADING).copy(attemptId = 3L)

        val merged = requireNotNull(mergeBatchDownloadPresentations(presentations, listOf(liveTask)))
        val withoutLiveTask = requireNotNull(
            mergeBatchDownloadPresentations(presentations, listOf(liveTask.copy(status = DownloadStatus.COMPLETED)))
        )

        assertEquals(mapOf(key to 3L), merged.memberAttemptIds)
        assertEquals(mapOf(key to 0.5f), merged.maximumObservedFractions)
        assertTrue(merged.terminalStates.isEmpty())
        assertEquals(mapOf(key to 4L), withoutLiveTask.memberAttemptIds)
        assertEquals(mapOf(key to 0.1f), withoutLiveTask.maximumObservedFractions)
        assertNull(
            mergeBatchDownloadPresentations(
                listOf(BatchDownloadPresentationState(id = 3L, memberAttemptIds = emptyMap())),
                emptyList()
            )
        )
    }

    private fun task(
        song: SongItem,
        status: DownloadStatus,
        stage: DownloadStage? = DownloadStage.TRANSFERRING,
        progressAttemptId: Long? = 0L
    ): DownloadTask {
        return DownloadTask(
            song = song,
            progress = stage?.let { progress(42L, 100L, it).copy(attemptId = progressAttemptId) },
            status = status,
            attemptId = 0L
        )
    }

    private fun progress(
        bytesRead: Long,
        totalBytes: Long,
        stage: DownloadStage = DownloadStage.TRANSFERRING
    ): DownloadProgress {
        return DownloadProgress(
            songKey = "song",
            songId = 1L,
            fileName = "song.mp3",
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            speedBytesPerSec = 0L,
            stage = stage
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
