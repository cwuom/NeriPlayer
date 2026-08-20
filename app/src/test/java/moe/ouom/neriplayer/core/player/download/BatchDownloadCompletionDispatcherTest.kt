package moe.ouom.neriplayer.core.player.download

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDownloadCompletionDispatcherTest {

    @Test
    fun `completion callbacks are bounded and awaitable`() = runBlocking {
        coroutineScope {
            val dispatcher = BatchDownloadCompletionDispatcher(
                scope = this,
                maxConcurrentCallbacks = 2
            )
            val firstWaveStarted = CompletableDeferred<Unit>()
            val releaseCallbacks = CompletableDeferred<Unit>()
            val startedCount = AtomicInteger(0)
            val activeCount = AtomicInteger(0)
            val maxActiveCount = AtomicInteger(0)

            repeat(4) {
                dispatcher.dispatch {
                    val active = activeCount.incrementAndGet()
                    maxActiveCount.accumulateAndGet(active) { current, observed ->
                        maxOf(current, observed)
                    }
                    if (startedCount.incrementAndGet() == 2) {
                        firstWaveStarted.complete(Unit)
                    }
                    try {
                        releaseCallbacks.await()
                    } finally {
                        activeCount.decrementAndGet()
                    }
                }
            }

            withTimeout(5_000L) {
                firstWaveStarted.await()
            }
            assertEquals(2, startedCount.get())
            assertEquals(2, maxActiveCount.get())

            releaseCallbacks.complete(Unit)
            dispatcher.awaitAll()
            assertEquals(4, startedCount.get())
            assertEquals(0, activeCount.get())
        }
    }
}
