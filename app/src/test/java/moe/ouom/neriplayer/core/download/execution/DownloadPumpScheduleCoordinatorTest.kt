package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.worker.DownloadPumpCompletion
import moe.ouom.neriplayer.core.download.execution.worker.DownloadPumpScheduleCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DownloadPumpScheduleCoordinatorTest {
    @Test
    fun `cancellation submission cannot remove a newer queued fallback before worker entry`() {
        assertCancellationKeepsNewFallback(coordinatorIndex = 0)
    }

    @Test
    fun `tag cancellation cannot remove a newer post core fallback before worker entry`() {
        assertCancellationKeepsNewFallback(coordinatorIndex = 1)
    }

    @Test
    fun `tag cancellation cannot remove a newer storage fallback before worker entry`() {
        assertCancellationKeepsNewFallback(coordinatorIndex = 2)
    }

    private fun assertCancellationKeepsNewFallback(coordinatorIndex: Int) {
        val submissionLock = Any()
        val coordinators = List(3) { DownloadPumpScheduleCoordinator(lock = submissionLock) }
        val coordinator = coordinators[coordinatorIndex]
        val persistedGenerations = ConcurrentHashMap.newKeySet<Long>()
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val enqueueThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cancellation = executor.submit {
                coordinators.first().invalidateAndSubmitCancellation {
                    coordinators.drop(1).forEach { it.invalidate() }
                    cancellationEntered.countDown()
                    assertTrue(releaseCancellation.await(5, TimeUnit.SECONDS))
                    persistedGenerations.clear()
                }
            }
            assertTrue(cancellationEntered.await(5, TimeUnit.SECONDS))
            val enqueue = executor.submit<Long> {
                enqueueThread.set(Thread.currentThread())
                val generation = requireNotNull(
                    if (coordinatorIndex == 0) coordinator.reserveImmediate() else coordinator.request()
                )
                assertTrue(coordinator.submitWorkEnqueue(generation) {
                    persistedGenerations.add(generation)
                })
                if (coordinatorIndex == 0) {
                    coordinator.completeImmediate(generation, DownloadExecutionPumpResult.Completed)
                }
                generation
            }
            awaitCompletionOrMonitorEntry(enqueue, enqueueThread)
            releaseCancellation.countDown()
            cancellation.get(5, TimeUnit.SECONDS)
            val generation = enqueue.get(5, TimeUnit.SECONDS)

            assertEquals(setOf(generation), persistedGenerations)
            // 尚未执行 doWork 的持久任务仍必须存在，随后才能接管同一代次
            assertTrue(coordinator.claimWorker(generation))
        } finally {
            releaseCancellation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `an enqueue already registered cannot escape a following cancellation submission`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val persistedGenerations = ConcurrentHashMap.newKeySet<Long>()
        val enqueueEntered = CountDownLatch(1)
        val releaseEnqueue = CountDownLatch(1)
        val cancellationThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(2)
        val generation = requireNotNull(coordinator.request())
        try {
            val enqueue = executor.submit {
                assertTrue(coordinator.submitWorkEnqueue(generation) {
                    enqueueEntered.countDown()
                    assertTrue(releaseEnqueue.await(5, TimeUnit.SECONDS))
                    persistedGenerations.add(generation)
                })
            }
            assertTrue(enqueueEntered.await(5, TimeUnit.SECONDS))
            val cancellation = executor.submit {
                cancellationThread.set(Thread.currentThread())
                coordinator.invalidateAndSubmitCancellation {
                    persistedGenerations.clear()
                }
            }
            awaitCompletionOrMonitorEntry(cancellation, cancellationThread)
            releaseEnqueue.countDown()
            enqueue.get(5, TimeUnit.SECONDS)
            cancellation.get(5, TimeUnit.SECONDS)

            assertTrue(persistedGenerations.isEmpty())
            assertFalse(coordinator.claimWorker(generation))
            val replacement = requireNotNull(coordinator.request())
            assertTrue(coordinator.submitWorkEnqueue(replacement) {
                persistedGenerations.add(replacement)
            })
            assertEquals(setOf(replacement), persistedGenerations)
        } finally {
            releaseEnqueue.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun awaitCompletionOrMonitorEntry(
        action: Future<*>,
        actionThread: AtomicReference<Thread>
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!action.isDone && actionThread.get()?.state != Thread.State.BLOCKED) {
            assertTrue("the competing submission never reached the coordinator", System.nanoTime() < deadline)
            Thread.sleep(1L)
        }
    }

    @Test
    fun `retry deadline requested during an active pump survives its completion`() {
        var now = 100L
        val coordinator = DownloadPumpScheduleCoordinator { now }
        val generation = coordinator.request()!!
        assertTrue(coordinator.claimWorker(generation))
        assertNull(coordinator.request(initialDelayMs = 30_000L))
        now += 500L
        assertEquals(
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
            coordinator.complete(generation, workWillRetry = false)
        )
        assertEquals(29_500L, coordinator.takeSuccessorDelayMs(generation))
    }

    @Test
    fun `manual wake shortens a pending retry deadline`() {
        val coordinator = DownloadPumpScheduleCoordinator { 100L }
        val generation = coordinator.request()!!
        coordinator.request(initialDelayMs = 30_000L)
        coordinator.request(initialDelayMs = 0L)
        coordinator.complete(generation, workWillRetry = false)
        assertEquals(0L, coordinator.takeSuccessorDelayMs(generation))
    }

    @Test
    fun `foreground resume takes over queued work without creating another generation`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!
        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        assertEquals(generation, coordinator.reserveImmediate())
        assertNull(coordinator.reserveImmediate())
        assertFalse(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
            coordinator.completeImmediate(generation, DownloadExecutionPumpResult.Completed)
        )
    }

    @Test
    fun `repeated requests collapse into one successor`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val firstGeneration = coordinator.request()

        assertTrue(firstGeneration != null)
        assertNull(coordinator.request())
        assertEquals(
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
            coordinator.complete(firstGeneration!!, workWillRetry = false)
        )
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `core commit wake does not manufacture a successor while the pump is busy`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!

        assertNull(coordinator.request(requestSuccessorWhenBusy = false))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.complete(generation, workWillRetry = false)
        )
        // 没有 successor 时，下一次真实请求可以直接取得新代次
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `retry keeps the current request authoritative`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!

        assertEquals(
            DownloadPumpCompletion.RETRYING,
            coordinator.complete(generation, workWillRetry = true)
        )
        assertNull(coordinator.request())
        assertTrue(coordinator.claimWorker(generation))
    }

    @Test
    fun `manual resume takes over a worker waiting for retry backoff`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!
        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        assertTrue(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.RETRYING,
            coordinator.complete(generation, workWillRetry = true)
        )

        assertEquals(generation, coordinator.reserveImmediate())
        assertFalse(coordinator.markWorkEnqueueStarted(generation))
        assertFalse(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.completeImmediate(generation, DownloadExecutionPumpResult.Completed)
        )
        assertTrue(coordinator.request() != null)
        assertFalse(coordinator.claimWorker(generation))
    }

    @Test
    fun `retrying worker remains the fallback when manual resume finishes before it starts`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!
        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        assertTrue(coordinator.claimWorker(generation))
        coordinator.complete(generation, workWillRetry = true)

        assertEquals(generation, coordinator.reserveImmediate())
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.completeImmediate(generation, DownloadExecutionPumpResult.ContinueSoon)
        )
        assertTrue(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.complete(generation, workWillRetry = false)
        )
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `clearing work prevents an old completion from changing a new pump`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val staleGeneration = coordinator.request()!!

        coordinator.invalidate()
        val currentGeneration = coordinator.request()!!

        assertFalse(coordinator.claimWorker(staleGeneration))
        assertEquals(
            DownloadPumpCompletion.IGNORED,
            coordinator.complete(staleGeneration, workWillRetry = false)
        )
        assertTrue(coordinator.claimWorker(currentGeneration))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.complete(currentGeneration, workWillRetry = false)
        )
    }

    @Test
    fun `asynchronous enqueue failure releases only its own generation`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val failedGeneration = coordinator.request()!!

        assertTrue(coordinator.failEnqueue(failedGeneration))
        assertTrue(coordinator.canRetry(failedGeneration))

        val replacementGeneration = coordinator.request()!!
        assertFalse(coordinator.failEnqueue(failedGeneration))
        assertFalse(coordinator.canRetry(failedGeneration))
        assertTrue(coordinator.claimWorker(replacementGeneration))
    }

    @Test
    fun `immediate reservation excludes a duplicate worker until it completes`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertFalse(coordinator.cancelUnclaimed(generation))
        assertFalse(coordinator.claimWorker(generation))
        assertFalse(coordinator.failEnqueue(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.complete(generation, workWillRetry = false)
        )
        assertTrue(coordinator.claimWorker(generation))
    }

    @Test
    fun `late enqueue failure cannot release an immediate retry generation`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertEquals(
            DownloadPumpCompletion.RETRYING,
            coordinator.complete(generation, workWillRetry = true)
        )
        assertFalse(coordinator.failEnqueue(generation))
        assertTrue(coordinator.claimWorker(generation))
    }

    @Test
    fun `immediate pump retry releases its generation for a successor`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertEquals(
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
            coordinator.completeImmediate(
                generation,
                DownloadExecutionPumpResult.Retry
            )
        )
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `queued worker keeps repeated release wakes from creating more generations`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.completeImmediate(
                generation,
                DownloadExecutionPumpResult.Completed
            )
        )
        assertNull(coordinator.request())
        assertNull(coordinator.request())
        assertTrue(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
            coordinator.complete(generation, workWillRetry = false)
        )
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `immediate owner keeps queued marker when worker starts before it releases`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        // Worker 观察到进程内泵仍是 owner 时不能清掉 queued fallback；
        // 进程内泵退出后必须交给新代次，避免留下永久 active generation
        assertFalse(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED_WITH_SUCCESSOR,
            coordinator.completeImmediate(
                generation,
                DownloadExecutionPumpResult.ContinueSoon
            )
        )
        assertTrue(coordinator.request() != null)
        assertFalse(coordinator.claimWorker(generation))
    }

    @Test
    fun `observed worker clears completed immediate generation without successor`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        assertFalse(coordinator.claimWorker(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.completeImmediate(
                generation,
                DownloadExecutionPumpResult.Completed
            )
        )
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `enqueue failure clears the queued fallback after immediate completion`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.reserveImmediate()!!

        assertTrue(coordinator.markWorkEnqueueStarted(generation))
        assertEquals(
            DownloadPumpCompletion.COMPLETED,
            coordinator.completeImmediate(
                generation,
                DownloadExecutionPumpResult.Completed
            )
        )
        // 失败回调到达时仍应能释放这一个代次，而不是留下永久 active。
        assertTrue(coordinator.failEnqueue(generation))
        assertTrue(coordinator.request() != null)
    }

    @Test
    fun `blocked unclaimed worker releases only its pending generation`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!

        assertTrue(coordinator.cancelUnclaimed(generation))
        assertFalse(coordinator.cancelUnclaimed(generation))
        assertTrue(coordinator.request() != null)
    }
}
