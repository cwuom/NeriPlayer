package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioDownloadProgressStoreTest {

    @Test
    fun `latest progress uses deltas while snapshot publication is bounded`() = runTest {
        val store = AudioDownloadProgressStore(bufferCapacity = 4)
        val received = mutableListOf<AudioDownloadManager.DownloadProgress>()
        val collector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler)
        ) {
            store.latestProgressEvents.take(3).collect { progress ->
                received += progress
            }
        }
        val first = progress(bytesRead = 10L)
        val second = progress(bytesRead = 20L)
        val final = progress(
            bytesRead = 100L,
            stage = AudioDownloadManager.DownloadStage.FINALIZING
        )

        store.publish(first, nowNs = 0L)
        store.publish(second, nowNs = 1L)

        assertEquals(first, store.latestProgressByOperation.value.getValue("operation"))

        store.publish(final, nowNs = 2L)
        collector.join()

        assertEquals(listOf(first, second, final), received)
        assertEquals(final, store.latestProgressByOperation.value.getValue("operation"))
    }

    @Test
    fun `snapshot refreshes after bounded interval without losing latest value`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val first = progress(bytesRead = 1L)
        val second = progress(bytesRead = 2L)

        store.publish(first, nowNs = 0L)
        store.publish(second, nowNs = 450_000_000L)

        assertEquals(second, store.latestProgressByOperation.value.getValue("operation"))
        assertEquals(second, store.latestProgressForSong("song"))
    }

    @Test
    fun `clearing an old owner keeps replacement progress visible`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val old = progress(operationId = "old", attemptId = 1L, bytesRead = 10L)
        val replacement = progress(operationId = "new", attemptId = 2L, bytesRead = 20L)

        store.publish(old, nowNs = 0L)
        store.publish(replacement, nowNs = 1L)
        store.clearPublished(
            songKey = "song",
            expectedAttemptId = old.attemptId,
            expectedOperationId = old.operationId
        )

        assertNull(store.latestProgressForSong("song", operationId = "old"))
        assertEquals(
            replacement,
            store.latestProgressForSong("song", operationId = "new")
        )
        // 旧 owner 清理不能误删替代 operation 的可见进度
        assertEquals(replacement, store.currentProgress())
    }

    @Test
    fun `finishing the visible batch restores progress from an older active batch`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val olderSession = store.startBatchSession()
        val olderProgress = batchProgress("older")
        store.updateBatchProgressForSession(olderSession, olderProgress)
        val newerSession = store.startBatchSession()
        val newerProgress = batchProgress("newer")
        store.updateBatchProgressForSession(newerSession, newerProgress)

        assertEquals(newerProgress, store.batchProgressFlow.value)

        store.finishBatchSession(newerSession)

        assertEquals(olderProgress, store.batchProgressFlow.value)
    }

    private fun batchProgress(currentSong: String) =
        AudioDownloadManager.BatchDownloadProgress(
            totalSongs = 2,
            completedSongs = 0,
            currentSong = currentSong,
            currentProgress = null
        )

    private fun progress(
        operationId: String = "operation",
        attemptId: Long = 1L,
        bytesRead: Long,
        stage: AudioDownloadManager.DownloadStage =
            AudioDownloadManager.DownloadStage.TRANSFERRING
    ): AudioDownloadManager.DownloadProgress {
        return AudioDownloadManager.DownloadProgress(
            songKey = "song",
            songId = 1L,
            fileName = "song.flac",
            bytesRead = bytesRead,
            totalBytes = 100L,
            speedBytesPerSec = 10L,
            stage = stage,
            attemptId = attemptId,
            operationId = operationId
        )
    }
}
