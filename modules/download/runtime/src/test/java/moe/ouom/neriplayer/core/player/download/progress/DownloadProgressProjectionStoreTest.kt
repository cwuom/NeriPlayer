package moe.ouom.neriplayer.core.player.download.progress

import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadStage

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressProjectionStoreTest {

    @Test
    fun `late snapshot cannot flash active enrichment back to waiting or transferring`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 1_000L)
        val waiting = progress(bytesRead = 90L)
            .copy(stage = DownloadStage.WAITING_RETRY).forPublication()
        store.record(waiting, nowNs = 0L)
        val oldSnapshot = store.snapshot.value.getValue("operation")
        val transferring = progress(bytesRead = 100L).forPublication()
        store.record(transferring, nowNs = 1L)
        val enriching = transferring
            .copy(stage = DownloadStage.ASSETS_ENRICHING).forPublication()
        store.record(enriching, nowNs = 2L)

        repeat(100) {
            assertEquals(enriching, store.record(oldSnapshot, nowNs = 3L + it))
            assertEquals(enriching, store.record(transferring, nowNs = 3L + it))
        }
        val retry = enriching.copy(stage = DownloadStage.WAITING_RETRY)
            .forPublication()
        assertEquals(retry, store.record(retry, nowNs = 200L))
        val resumed = retry.copy(stage = DownloadStage.ASSETS_ENRICHING)
            .forPublication()
        assertEquals(resumed, store.record(resumed, nowNs = 201L))
    }

    @Test
    fun `persisted checkpoint cannot overwrite a live stage but a new attempt starts fresh`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 0L)
        val live = progress(bytesRead = 100L)
            .copy(stage = DownloadStage.FINALIZING).forPublication()
        store.record(live, nowNs = 0L)
        assertEquals(live, store.record(progress(bytesRead = 50L), nowNs = 1L))
        val next = progress(attemptId = 2L, bytesRead = 0L).forPublication()
        assertEquals(next, store.record(next, nowNs = 2L))
        assertEquals(next, store.record(live.forPublication(), nowNs = 3L))
    }

    @Test
    fun `record keeps the newest owner and refreshes bounded snapshot`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 100L)
        val first = progress(bytesRead = 10L)
        val second = progress(bytesRead = 20L)

        assertEquals(first, store.record(first, nowNs = 0L))
        assertEquals(first, store.snapshot.value.getValue("operation"))

        assertEquals(second, store.record(second, nowNs = 1L))
        assertEquals(first, store.snapshot.value.getValue("operation"))

        store.record(progress(bytesRead = 30L), nowNs = 100L)
        assertEquals(30L, store.snapshot.value.getValue("operation").bytesRead)
    }

    @Test
    fun `older attempt cannot replace a newer attempt`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 0L)
        val newer = progress(attemptId = 2L, bytesRead = 20L)
        val older = progress(attemptId = 1L, bytesRead = 90L)

        store.record(newer, nowNs = 0L)
        assertEquals(newer, store.record(older, nowNs = 1L))
        assertEquals(newer, store.latest("operation"))
    }

    @Test
    fun `remove and clear expose an immediately consistent snapshot`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 1_000L)
        store.record(progress(operationId = "a", bytesRead = 1L), nowNs = 0L)
        store.record(progress(operationId = "b", bytesRead = 2L), nowNs = 1L)

        store.remove("a")
        assertNull(store.latest("a"))
        assertEquals(setOf("b"), store.snapshot.value.keys)

        store.clear()
        assertEquals(emptyMap<String, DownloadProgress>(), store.snapshot.value)
    }

    @Test
    fun `progress without operation id is keyed by song and attempt`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 0L)
        val anonymous = progress(bytesRead = 10L).copy(songKey = "song", operationId = null, attemptId = null)
        val blankOperation = progress(attemptId = 5L, bytesRead = 20L).copy(songKey = "song", operationId = " ")

        store.record(anonymous, nowNs = 0L)
        store.record(blankOperation, nowNs = 1L)

        assertEquals(anonymous, store.latest("song#0"))
        assertEquals(blankOperation, store.latest(" song#5 "))
        assertNull(store.latest(null))
        assertNull(store.latest("  "))
        store.remove(null)
        store.remove("  ")
        store.remove("missing")
        assertEquals(setOf("song#0", "song#5"), store.snapshot.value.keys)
        store.remove(" song#0 ")
        assertEquals(setOf("song#5"), store.snapshot.value.keys)
    }

    @Test
    fun `throttled snapshots still publish new operations after the interval and completed transfers`() {
        val store = DownloadProgressProjectionStore(snapshotIntervalNs = 100L)
        val unknownSize = progress(operationId = "a", bytesRead = 1L).copy(totalBytes = 0L)
        store.record(unknownSize, nowNs = 0L)
        store.record(progress(operationId = "b", bytesRead = 1L), nowNs = 10L)

        assertEquals(setOf("a"), store.snapshot.value.keys)
        assertEquals(setOf("a", "b"), store.snapshotValues().map { it.operationId }.toSet())

        store.record(progress(operationId = "c", bytesRead = 1L), nowNs = 100L)
        assertEquals(setOf("a", "b", "c"), store.snapshot.value.keys)

        store.record(unknownSize.copy(bytesRead = 50L), nowNs = 110L)
        assertEquals(1L, store.snapshot.value.getValue("a").bytesRead)

        store.record(progress(operationId = "b", bytesRead = 100L), nowNs = 120L)
        assertEquals(100L, store.snapshot.value.getValue("b").bytesRead)
        assertEquals(50L, store.snapshot.value.getValue("a").bytesRead)
    }

    private fun progress(
        operationId: String = "operation",
        attemptId: Long = 1L,
        bytesRead: Long
    ): DownloadProgress {
        return DownloadProgress(
            songKey = operationId,
            songId = operationId.hashCode().toLong(),
            fileName = "$operationId.flac",
            bytesRead = bytesRead,
            totalBytes = 100L,
            speedBytesPerSec = 10L,
            attemptId = attemptId,
            operationId = operationId
        )
    }
}
