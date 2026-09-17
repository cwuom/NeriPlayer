package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.worker.DownloadPumpCompletion
import moe.ouom.neriplayer.core.download.execution.worker.DownloadPumpScheduleCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPumpScheduleCoordinatorTest {
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
