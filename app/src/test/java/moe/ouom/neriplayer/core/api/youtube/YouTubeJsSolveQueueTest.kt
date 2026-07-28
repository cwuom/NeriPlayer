package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

        val waiters = listOf("prefetch-1", "prefetch-2", "click").mapIndexed { index, name ->
            val entered = CountDownLatch(1)
            val thread = Thread {
                entered.countDown()
                queue.withNewestFirst { order.add(name) }
            }
            thread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // 逐个确认已经进入等待队列，保证入队顺序确定
            while (queue.waitingCountForTest() < index + 1) {
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

    @Test
    fun `interrupted waiter leaves the queue`() {
        val queue = YouTubeJsSolveQueue()
        val holderInside = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val waiterFinished = CountDownLatch(1)
        var waiterFailure: Throwable? = null

        val holder = Thread {
            queue.withNewestFirst {
                holderInside.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        val waiter = Thread {
            runCatching { queue.withNewestFirst { Unit } }
                .onFailure { waiterFailure = it }
            waiterFinished.countDown()
        }
        waiter.start()
        while (queue.waitingCountForTest() < 1) {
            Thread.yield()
        }

        waiter.interrupt()
        assertTrue(waiterFinished.await(5, TimeUnit.SECONDS))
        releaseHolder.countDown()
        holder.join(5_000)
        waiter.join(5_000)

        assertTrue(waiterFailure is InterruptedException)
        assertNull(waiterFailure?.cause)
        assertEquals(0, queue.waitingCountForTest())
    }

    @Test
    fun `interrupted holder releases the queue`() {
        val queue = YouTubeJsSolveQueue()
        val bodyStarted = CountDownLatch(1)
        val bodyBlock = CountDownLatch(1)
        var holderFailure: Throwable? = null

        val holder = Thread {
            runCatching {
                queue.withNewestFirst {
                    bodyStarted.countDown()
                    bodyBlock.await()
                }
            }.onFailure { holderFailure = it }
        }
        holder.start()
        assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))

        holder.interrupt()
        holder.join(5_000)

        assertTrue(holderFailure is InterruptedException)
        assertEquals("recovered", queue.withNewestFirst { "recovered" })
        assertEquals(0, queue.waitingCountForTest())
    }

    @Test
    fun `suspending waiters keep newest first order after cancellation`() = runBlocking {
        val queue = YouTubeJsSolveQueue()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val holderInside = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)

        val holder = async(Dispatchers.Default) {
            queue.withNewestFirstCancellable {
                order.add("holder")
                holderInside.countDown()
                releaseHolder.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(holderInside.await(5, TimeUnit.SECONDS))

        val older = async(Dispatchers.Default) {
            queue.withNewestFirstCancellable {
                order.add("older")
            }
        }
        val canceled = async(Dispatchers.Default) {
            queue.withNewestFirstCancellable {
                order.add("canceled")
            }
        }
        withTimeout(1_000L) {
            while (queue.waitingCountForTest() < 2) yield()
        }
        canceled.cancelAndJoin()

        val latest = async(Dispatchers.Default) {
            queue.withNewestFirstCancellable {
                order.add("latest")
            }
        }
        withTimeout(1_000L) {
            while (queue.waitingCountForTest() < 2) yield()
        }
        releaseHolder.countDown()

        holder.await()
        latest.await()
        older.await()

        assertEquals(listOf("holder", "latest", "older"), order.toList())
        assertEquals(0, queue.waitingCountForTest())
    }
}
