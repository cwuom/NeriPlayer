package moe.ouom.neriplayer.core.download.enrichment

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val completionCount = AtomicInteger(0)
        val duplicateCompletionCount = AtomicInteger(0)

        coordinator.enqueue(
            operationId = "operation",
            onCompletion = { completionCount.incrementAndGet() }
        ) {
            runs.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        val duplicate = coordinator.enqueue(
            operationId = "operation",
            onCompletion = { duplicateCompletionCount.incrementAndGet() }
        ) { runs.incrementAndGet() }

        started.await()
        assertTrue(duplicate.isActive)
        assertEquals(1, runs.get())
        release.complete(Unit)
        withTimeout(2_000L) { duplicate.join() }
        assertEquals(1, runs.get())
        assertEquals(1, completionCount.get())
        assertEquals(0, duplicateCompletionCount.get())
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

    @Test
    fun `timeout callback failure is reported without cancelling the job`() = runBlocking {
        val unhandledFailures = AtomicInteger(0)
        val exceptionHandler = CoroutineExceptionHandler { _, _ ->
            unhandledFailures.incrementAndGet()
        }
        val scope = kotlinx.coroutines.CoroutineScope(
            SupervisorJob() + Dispatchers.Default + exceptionHandler
        )
        val coordinator = AssetEnrichmentCoordinator(
            scope = scope,
            parallelism = 1,
            timeoutMs = 20L
        )
        val callbackFailure = IllegalStateException("timeout callback failed")
        val completionError = AtomicReference<Throwable?>(null)

        val job = coordinator.enqueue(
            operationId = "timeout-callback-failure",
            onTimeout = { throw callbackFailure },
            onCompletion = { error -> completionError.set(error) }
        ) {
            delay(200L)
        }

        withTimeout(2_000L) { job.join() }
        assertTrue(job.isCompleted)
        assertTrue(!job.isCancelled)
        assertEquals(callbackFailure, completionError.get())
        assertEquals(0, unhandledFailures.get())
        scope.cancel()
    }

    @Test
    fun `queued enrichment times out while waiting for a permit`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(
            scope = scope,
            parallelism = 1,
            timeoutMs = 50L
        )
        val permitHeld = CompletableDeferred<Unit>()
        val releasePermit = CompletableDeferred<Unit>()
        val queuedRuns = AtomicInteger(0)
        val timeout = AtomicReference<Throwable?>(null)

        val blockingJob = coordinator.enqueue("permit-holder") {
            permitHeld.complete(Unit)
            releasePermit.await()
        }
        permitHeld.await()
        val queuedJob = coordinator.enqueue(
            operationId = "queued-operation",
            onTimeout = { error -> timeout.set(error) }
        ) {
            queuedRuns.incrementAndGet()
        }

        try {
            withTimeout(2_000L) { queuedJob.join() }
            assertTrue(timeout.get() is kotlinx.coroutines.TimeoutCancellationException)
            assertFalse(queuedJob.isCancelled)
            assertEquals(0, queuedRuns.get())
            assertFalse("queued-operation" in coordinator.activeOperationIds())
        } finally {
            releasePermit.complete(Unit)
            withTimeout(2_000L) { blockingJob.join() }
            scope.cancel()
        }
    }

    @Test
    fun `enqueued enrichment lets its caller finish while assets remain active`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val caller = launch {
            coordinator.enqueue("durable-operation") {
                started.complete(Unit)
                release.await()
            }
        }

        withTimeout(2_000L) { caller.join() }
        withTimeout(2_000L) { started.await() }
        assertTrue(caller.isCompleted)
        assertEquals(1, coordinator.activeCount())
        release.complete(Unit)
        withTimeout(2_000L) {
            while (coordinator.activeCount() != 0) {
                delay(10L)
            }
        }
        assertEquals(0, coordinator.activeCount())
        scope.cancel()
    }

    @Test
    fun `active flow remains true until enrichment completion`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = coordinator.enqueue("active-flow") {
            started.complete(Unit)
            release.await()
        }

        withTimeout(2_000L) { started.await() }
        assertTrue(coordinator.hasActiveJobs.value)
        release.complete(Unit)
        withTimeout(2_000L) { job.join() }
        withTimeout(2_000L) {
            while (coordinator.hasActiveJobs.value) {
                delay(10L)
            }
        }
        assertTrue(!coordinator.hasActiveJobs.value)
        scope.cancel()
    }

    @Test
    fun `completion hook releases ownership once for every terminal outcome`() = runBlocking {
        val ignoredFailures = AtomicInteger(0)
        val exceptionHandler = CoroutineExceptionHandler { _, _ ->
            ignoredFailures.incrementAndGet()
        }
        val scope = kotlinx.coroutines.CoroutineScope(
            SupervisorJob() + Dispatchers.Default + exceptionHandler
        )
        val coordinator = AssetEnrichmentCoordinator(
            scope = scope,
            parallelism = 1,
            timeoutMs = 20L
        )

        suspend fun assertReleasedOnce(
            operationId: String,
            block: suspend () -> Unit
        ) {
            val releases = AtomicInteger(0)
            val job = coordinator.enqueue(
                operationId = operationId,
                onCompletion = { releases.incrementAndGet() },
                block = block
            )
            withTimeout(2_000L) { job.join() }
            assertEquals("operation=$operationId", 1, releases.get())
        }

        assertReleasedOnce("completed") {}
        assertReleasedOnce("timed-out") { delay(200L) }
        assertReleasedOnce("failed") { error("asset failure") }

        val cancellationStarted = CompletableDeferred<Unit>()
        val cancellationReleases = AtomicInteger(0)
        val cancelledJob = coordinator.enqueue(
            operationId = "cancelled",
            onCompletion = { cancellationReleases.incrementAndGet() }
        ) {
            cancellationStarted.complete(Unit)
            awaitCancellation()
        }
        withTimeout(2_000L) { cancellationStarted.await() }
        assertTrue(coordinator.cancel("cancelled"))
        withTimeout(2_000L) { cancelledJob.join() }
        assertEquals(1, cancellationReleases.get())
        assertEquals(1, ignoredFailures.get())
        scope.cancel()
    }

    @Test
    fun `cancelAll stops every active enrichment and exposes active operation ids`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 2)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val jobs = listOf("cancel-one", "cancel-two").map { operationId ->
            coordinator.enqueue(operationId) {
                started.complete(Unit)
                release.await()
            }
        }

        withTimeout(2_000L) {
            while (coordinator.activeOperationIds().size != 2) {
                delay(10L)
            }
        }
        assertEquals(setOf("cancel-one", "cancel-two"), coordinator.activeOperationIds())
        assertEquals(2, coordinator.cancelAll("clear requested"))
        jobs.forEach { job -> job.cancelAndJoin() }
        withTimeout(2_000L) {
            while (coordinator.activeCount() != 0) {
                delay(10L)
            }
        }
        assertTrue(coordinator.activeOperationIds().isEmpty())
        release.cancel()
        started.cancel()
        scope.cancel()
    }

    @Test
    fun `cancelAllAndJoin waits until every enrichment releases file ownership`() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssetEnrichmentCoordinator(scope, parallelism = 2)
        val started = AtomicInteger(0)
        val jobs = listOf("join-one", "join-two").map { operationId ->
            coordinator.enqueue(operationId) {
                started.incrementAndGet()
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { delay(20L) }
                }
            }
        }

        withTimeout(2_000L) {
            while (started.get() != jobs.size) {
                delay(10L)
            }
        }
        assertTrue(
            coordinator.cancelAllAndJoin(
                reason = "clear requested",
                timeoutMs = 2_000L
            )
        )
        assertTrue(jobs.all(Job::isCompleted))
        assertTrue(coordinator.activeOperationIds().isEmpty())
        scope.cancel()
    }
}
