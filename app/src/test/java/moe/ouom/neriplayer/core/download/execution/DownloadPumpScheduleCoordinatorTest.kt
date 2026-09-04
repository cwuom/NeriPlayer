package moe.ouom.neriplayer.core.download.execution

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
    fun `blocked unclaimed worker releases only its pending generation`() {
        val coordinator = DownloadPumpScheduleCoordinator()
        val generation = coordinator.request()!!

        assertTrue(coordinator.cancelUnclaimed(generation))
        assertFalse(coordinator.cancelUnclaimed(generation))
        assertTrue(coordinator.request() != null)
    }
}
