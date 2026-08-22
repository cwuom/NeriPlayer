package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetEnrichmentCoordinatorTest {
    @Test
    fun `same operation is enqueued only once`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runs = AtomicInteger(0)

        coordinator.enqueue("operation") {
            runs.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        val duplicate = coordinator.enqueue("operation") { runs.incrementAndGet() }

        started.await()
        assertTrue(duplicate.isActive)
        assertEquals(1, runs.get())
        release.complete(Unit)
        withTimeout(2_000L) { duplicate.join() }
        assertEquals(1, runs.get())
        scope.cancel()
    }

    @Test
    fun `one slow asset does not consume more than configured parallelism`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 2, timeoutMs = 5_000L)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val jobs = (0 until 5).map { index ->
            coordinator.enqueue("operation-$index") {
                val now = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                delay(20L)
                active.decrementAndGet()
            }
        }

        jobs.forEach { it.join() }
        assertEquals(2, peak.get())
        scope.cancel()
    }

    @Test
    fun `timeout completes as degraded instead of cancelling the coordinator job`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 1, timeoutMs = 20L)
        val timeout = AtomicReference<Throwable?>(null)

        val job = coordinator.enqueue(
            operationId = "slow-operation",
            block = { delay(200L) },
            onTimeout = { error -> timeout.set(error) }
        )

        job.join()
        assertTrue(timeout.get() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue(job.isCompleted)
        assertTrue(!job.isCancelled)
        scope.cancel()
    }
}
