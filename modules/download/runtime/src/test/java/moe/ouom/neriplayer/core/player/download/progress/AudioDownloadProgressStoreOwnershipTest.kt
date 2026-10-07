package moe.ouom.neriplayer.core.player.download.progress

import moe.ouom.neriplayer.data.model.download.BatchDownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadProgressStoreOwnershipTest {

    @Test
    fun `late progress from an older attempt is not published`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val current = progress(attemptId = 2L, bytesRead = 50L)

        store.publish(current, nowNs = 0L)
        store.publish(progress(attemptId = 1L, bytesRead = 80L), nowNs = 1L)

        assertEquals(current, store.currentProgress())
        assertEquals(listOf(current), store.latestProgressSnapshot())
    }

    @Test
    fun `duplicate progress is only republished when forced`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val current = progress(bytesRead = 50L)
        store.publish(current, nowNs = 0L)
        store.clearVisibleProgress()

        store.publish(current, nowNs = 1L)
        assertNull(store.currentProgress())

        store.publish(current, nowNs = 2L, force = true)
        assertEquals(current, store.currentProgress())
    }

    @Test
    fun `clearing published progress requires the expected attempt and operation`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val published = progress(operationId = "op-1", attemptId = 1L, bytesRead = 10L)
        store.publish(published, nowNs = 0L)

        store.clearPublished("song", expectedAttemptId = 2L)
        store.clearPublished("song", expectedOperationId = " other ")
        store.clearPublished("song", expectedAttemptId = 1L, expectedOperationId = "op-2")
        store.clearPublished("unknown")
        assertEquals(published, store.latestProgressForSong("song"))

        store.clearPublished("song", expectedAttemptId = 1L, expectedOperationId = "  ")
        assertNull(store.latestProgressForSong("song"))
        assertEquals(emptyMap<String, DownloadProgress>(), store.latestProgressByOperation.value)
    }

    @Test
    fun `cleared publication state lets the next progress through immediately`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        store.publish(progress(bytesRead = 10L), nowNs = 0L)
        store.clearVisibleProgress()

        store.publish(progress(bytesRead = 20L), nowNs = 1L)
        assertNull(store.currentProgress())

        store.clearPublished("song", expectedOperationId = " operation ")
        val next = progress(bytesRead = 30L)
        store.publish(next, nowNs = 2L)
        assertEquals(next, store.currentProgress())
    }

    @Test
    fun `operation keys fall back to song and attempt when the operation id is blank`() {
        assertEquals(setOf("song#0"), firstSnapshotKeys(progress(operationId = null, attemptId = null)))
        assertEquals(setOf("song#3"), firstSnapshotKeys(progress(operationId = "  ", attemptId = 3L)))
        assertEquals(setOf("op"), firstSnapshotKeys(progress(operationId = " op ", attemptId = 1L)))
    }

    @Test
    fun `latest progress for a song filters by owner and prefers the newest attempt`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        val first = progress(operationId = "a", attemptId = 1L)
        val newest = progress(operationId = "b", attemptId = 3L)
        val anonymous = progress(operationId = null, attemptId = null)
        listOf(first, newest, anonymous, progress(songKey = "other", operationId = "c")).forEach {
            store.publish(it, nowNs = 0L)
        }

        assertEquals(newest, store.latestProgressForSong("song"))
        assertEquals(first, store.latestProgressForSong("song", attemptId = 1L))
        assertEquals(first, store.latestProgressForSong("song", operationId = "a"))
        assertNull(store.latestProgressForSong("song", attemptId = 9L))
        assertNull(store.latestProgressForSong("song", attemptId = 3L, operationId = "a"))
        assertNull(store.latestProgressForSong("missing"))
    }

    @Test
    fun `latest snapshot is refreshed for new operations attempts and terminal transfers`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 4)
        val interval = 450_000_000L

        store.publish(progress(operationId = "a", bytesRead = 10L), nowNs = 0L)
        store.publish(progress(operationId = "b", bytesRead = 10L), nowNs = 1L)
        assertEquals(setOf("a"), snapshotKeys(store))

        store.publish(progress(operationId = "b", attemptId = 2L, bytesRead = 5L), nowNs = 2L)
        assertEquals(2L, snapshot(store, "b").attemptId)

        store.publish(progress(operationId = "a", bytesRead = 20L), nowNs = 3L)
        assertEquals(10L, snapshot(store, "a").bytesRead)

        store.publish(progress(operationId = "a", bytesRead = 100L), nowNs = 4L)
        assertEquals(100L, snapshot(store, "a").bytesRead)

        store.publish(progress(operationId = "a", bytesRead = 100L, stage = DownloadStage.FINALIZING), nowNs = 5L)
        assertEquals(DownloadStage.FINALIZING, snapshot(store, "a").stage)

        store.publish(progress(operationId = "c", bytesRead = 10L, totalBytes = 0L), nowNs = 6L)
        store.publish(progress(operationId = "c", bytesRead = 20L, totalBytes = 0L), nowNs = 7L)
        assertFalse("c" in snapshotKeys(store))

        store.publish(progress(operationId = "c", bytesRead = 30L, totalBytes = 0L), nowNs = 5L + interval)
        assertEquals(30L, snapshot(store, "c").bytesRead)

        store.publish(progress(operationId = "d", bytesRead = 1L), nowNs = 5L + 2 * interval)
        assertEquals(setOf("a", "b", "c", "d"), snapshotKeys(store))
    }

    @Test
    fun `batch sessions stay current until finished or invalidated`() {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        assertTrue(store.isBatchSessionCurrent(null))

        val older = store.startBatchSession()
        val newer = store.startBatchSession()
        store.updateBatchProgressForSession(older, batchProgress("older"))
        assertNull(store.batchProgressFlow.value)

        store.finishBatchSession(older)
        assertFalse(store.isBatchSessionCurrent(older))
        assertTrue(store.isBatchSessionCurrent(newer))
        assertNull(store.batchProgressFlow.value)

        store.updateBatchProgressForSession(newer, batchProgress("newer"))
        assertEquals(batchProgress("newer"), store.batchProgressFlow.value)
        store.finishBatchSession(newer)
        assertFalse(store.isBatchSessionCurrent(newer))
        assertNull(store.batchProgressFlow.value)

        val invalidated = store.startBatchSession()
        store.invalidateBatchSession()
        store.updateBatchProgressForSession(invalidated, batchProgress("late"))
        assertFalse(store.isBatchSessionCurrent(invalidated))
        assertNull(store.batchProgressFlow.value)
    }

    private fun firstSnapshotKeys(progress: DownloadProgress): Set<String> {
        val store = AudioDownloadProgressStore(bufferCapacity = 2)
        store.publish(progress, nowNs = 0L)
        return snapshotKeys(store)
    }

    private fun snapshotKeys(store: AudioDownloadProgressStore): Set<String> =
        store.latestProgressByOperation.value.keys

    private fun snapshot(store: AudioDownloadProgressStore, operationId: String): DownloadProgress =
        store.latestProgressByOperation.value.getValue(operationId)

    private fun batchProgress(currentSong: String) = BatchDownloadProgress(
        totalSongs = 2,
        completedSongs = 0,
        currentSong = currentSong,
        currentProgress = null
    )

    private fun progress(
        songKey: String = "song",
        operationId: String? = "operation",
        attemptId: Long? = 1L,
        bytesRead: Long = 10L,
        totalBytes: Long = 100L,
        stage: DownloadStage = DownloadStage.TRANSFERRING
    ): DownloadProgress {
        return DownloadProgress(
            songKey = songKey,
            songId = 1L,
            fileName = "song.flac",
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            speedBytesPerSec = 10L,
            stage = stage,
            attemptId = attemptId,
            operationId = operationId
        )
    }
}
