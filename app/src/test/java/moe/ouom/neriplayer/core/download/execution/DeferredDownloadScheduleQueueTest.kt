package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeferredDownloadScheduleQueueTest {
    @Test
    fun `requeued request yields to the next deferred request`() {
        val queue = DeferredDownloadScheduleQueue()
        val first = request("operation-first", 1L)
        val second = request("operation-second", 2L)

        queue.enqueue(first)
        queue.enqueue(second)

        assertEquals(first, queue.poll())
        queue.requeue(first)
        assertEquals(second, queue.poll())
        assertEquals(first, queue.poll())
    }

    @Test
    fun `updating a queued operation keeps only its latest request`() {
        val queue = DeferredDownloadScheduleQueue()
        val original = request("operation-update", 1L, attemptId = 1L)
        val updated = original.copy(attemptId = 2L)

        queue.enqueue(original)
        queue.enqueue(updated)

        assertEquals(updated, queue.poll())
        queue.requeue(original)
        assertNull(queue.poll())
    }

    @Test
    fun `removed request cannot be revived by a stale queue token`() {
        val queue = DeferredDownloadScheduleQueue()
        val removed = request("operation-removed", 1L)
        val next = request("operation-next", 2L)

        queue.enqueue(removed)
        queue.remove(removed.operationId)
        queue.enqueue(next)

        assertEquals(next, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `clearing queue allows an operation to be enqueued again`() {
        val queue = DeferredDownloadScheduleQueue()
        val request = request("operation-cleared", 3L)

        queue.enqueue(request)
        queue.poll()
        queue.clear()
        queue.enqueue(request)

        assertEquals(request, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `overflow evicts the oldest deferred request while persistence remains authoritative`() {
        val queue = DeferredDownloadScheduleQueue(maxRequests = 2)
        val first = request("operation-first", 1L)
        val second = request("operation-second", 2L)
        val third = request("operation-third", 3L)

        queue.enqueue(first)
        queue.enqueue(second)
        queue.enqueue(third)

        assertEquals(2, queue.size())
        assertEquals(second, queue.poll())
        assertEquals(third, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `repeated queued updates compact stale nodes and keep the newest request`() {
        val queue = DeferredDownloadScheduleQueue(maxRequests = 2)
        val original = request("operation-update", 1L, attemptId = 1L)
        queue.enqueue(original)

        val newest = (2L..128L).fold(original) { current, attemptId ->
            current.copy(attemptId = attemptId).also(queue::enqueue)
        }

        val snapshot = queue.snapshot()
        assertEquals(1, snapshot.requestCount)
        assertEquals(true, snapshot.compactionCount > 0L)
        assertEquals(true, snapshot.readyNodeCount <= 20)
        assertEquals(true, snapshot.orderNodeCount <= 20)
        assertEquals(newest, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `removal leaves stale nodes fenced without reviving the request`() {
        val queue = DeferredDownloadScheduleQueue(maxRequests = 2)
        val removed = request("operation-removed", 1L)
        val next = request("operation-next", 2L)

        queue.enqueue(removed)
        repeat(32) { attemptId ->
            queue.enqueue(removed.copy(attemptId = attemptId.toLong() + 1L))
        }
        queue.remove(removed.operationId)
        queue.enqueue(next)

        assertEquals(next, queue.poll())
        assertNull(queue.poll())
        queue.remove(next)
        assertEquals(0, queue.size())
    }

    private fun request(
        operationId: String,
        songId: Long,
        attemptId: Long? = null
    ): DownloadExecutionRequest {
        return DownloadExecutionRequest(
            operationId = operationId,
            song = SongItem(
                id = songId,
                name = "Song $songId",
                artist = "Artist",
                album = "Album",
                albumId = songId,
                durationMs = 1_000L,
                coverUrl = null
            ),
            attemptId = attemptId
        )
    }
}
