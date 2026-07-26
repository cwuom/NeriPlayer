package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YouTubeJsSolveQueueTest {

    @Test
    fun `later arrival runs before the ones already queued`() {
        val queue = YouTubeJsSolveQueue()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val holderInside = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)

        val holder = Thread {
            queue.withNewestFirst {
                order.add("holder")
                holderInside.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        val waiters = listOf("prefetch-1", "prefetch-2", "click").map { name ->
            val entered = CountDownLatch(1)
            val thread = Thread {
                entered.countDown()
                queue.withNewestFirst { order.add(name) }
            }
            thread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // 逐个确认已经进入等待队列，保证入队顺序确定
            while (thread.state != Thread.State.WAITING) {
                Thread.yield()
            }
            thread
        }

        releaseHolder.countDown()
        holder.join(5_000)
        waiters.forEach { it.join(5_000) }

        assertEquals(listOf("holder", "click", "prefetch-2", "prefetch-1"), order.toList())
        assertEquals(0, queue.waitingCountForTest())
    }

    @Test
    fun `single caller runs without waiting`() {
        val queue = YouTubeJsSolveQueue()
        assertEquals("done", queue.withNewestFirst { "done" })
        assertEquals(0, queue.waitingCountForTest())
    }

    @Test
    fun `lock is released when the body throws`() {
        val queue = YouTubeJsSolveQueue()
        runCatching { queue.withNewestFirst { error("boom") } }
        assertEquals("recovered", queue.withNewestFirst { "recovered" })
        assertEquals(0, queue.waitingCountForTest())
    }
}
