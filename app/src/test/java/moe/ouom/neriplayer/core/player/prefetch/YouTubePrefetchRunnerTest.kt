package moe.ouom.neriplayer.core.player.prefetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePrefetchRunnerTest {

    @Test
    fun `prefetch concurrency never exceeds maxConcurrency`() = runTest {
        val task = FakePrefetchTask(delayMs = 1_000)
        val runner = YouTubePrefetchRunner(task = task, maxConcurrency = 1)

        runner.launch(this, listOf("a", "b", "c"))
        advanceUntilIdle()

        assertEquals(1, task.maxConcurrencyObserved.get())
    }

    @Test
    fun `all videoIds are prefetched exactly once`() = runTest {
        val task = FakePrefetchTask(delayMs = 1_000)
        val runner = YouTubePrefetchRunner(task = task, maxConcurrency = 1)

        runner.launch(this, listOf("a", "b", "c"))
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), task.finished.toList())
    }

    @Test
    fun `a failing prefetch does not abort the rest`() = runTest {
        val task = FakePrefetchTask(delayMs = 1_000, failingVideoIds = setOf("b"))
        val runner = YouTubePrefetchRunner(task = task, maxConcurrency = 1)

        runner.launch(this, listOf("a", "b", "c"))
        advanceUntilIdle()

        assertEquals(listOf("a", "c"), task.finished.toList())
        assertEquals(listOf("b"), task.failed.toList())
    }

    @Test
    fun `cancelling the returned job stops pending prefetch`() = runTest {
        val task = FakePrefetchTask(delayMs = 10_000)
        val runner = YouTubePrefetchRunner(task = task, maxConcurrency = 1)

        val job = runner.launch(this, listOf("a", "b", "c"))
        advanceTimeBy(1)
        job.cancel(CancellationException("test cancellation"))
        advanceUntilIdle()

        assertTrue(task.started.contains("a"))
        assertFalse(task.started.contains("b"))
        assertFalse(task.started.contains("c"))
    }

    private class FakePrefetchTask(
        private val delayMs: Long,
        private val failingVideoIds: Set<String> = emptySet()
    ) : YouTubePrefetchTask {
        private val active = AtomicInteger(0)
        val maxConcurrencyObserved = AtomicInteger(0)
        val started = Collections.synchronizedList(mutableListOf<String>())
        val finished = Collections.synchronizedList(mutableListOf<String>())
        val failed = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun prefetch(videoId: String) {
            started.add(videoId)
            val now = active.incrementAndGet()
            maxConcurrencyObserved.updateAndGet { max(it, now) }
            try {
                delay(delayMs)
                if (videoId in failingVideoIds) {
                    failed.add(videoId)
                    error("boom")
                }
                finished.add(videoId)
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
