package moe.ouom.neriplayer.core.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressProjectionStoreTest {

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
        assertEquals(emptyMap<String, AudioDownloadManager.DownloadProgress>(), store.snapshot.value)
    }

    private fun progress(
        operationId: String = "operation",
        attemptId: Long = 1L,
        bytesRead: Long
    ): AudioDownloadManager.DownloadProgress {
        return AudioDownloadManager.DownloadProgress(
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
