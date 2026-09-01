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
}
