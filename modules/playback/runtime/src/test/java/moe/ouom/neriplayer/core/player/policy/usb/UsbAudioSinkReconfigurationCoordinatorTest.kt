package moe.ouom.neriplayer.core.player.policy.usb

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioSinkReconfigurationCoordinatorTest {

    @Test
    fun `new request supersedes installed job and rejects stale install`() {
        val coordinator = UsbAudioSinkReconfigurationCoordinator()
        val first = coordinator.begin("first")
        val firstJob = Job()
        val staleJob = Job()

        assertTrue(coordinator.install(first.token, firstJob))
        val second = coordinator.begin("second")

        assertSame(firstJob, second.supersededJob)
        assertFalse(coordinator.isLatest(first.token))
        assertTrue(coordinator.isLatest(second.token))
        assertFalse(coordinator.install(first.token, staleJob))
        assertEquals(
            UsbAudioSinkReconfigurationSnapshot(pending = true, reason = "second"),
            coordinator.snapshot()
        )

        firstJob.cancel()
        staleJob.cancel()
    }

    @Test
    fun `stale completion cannot clear newer request`() {
        val coordinator = UsbAudioSinkReconfigurationCoordinator()
        val first = coordinator.begin("first")
        val firstJob = Job()
        assertTrue(coordinator.install(first.token, firstJob))

        val second = coordinator.begin("second")
        val secondJob = Job()
        assertTrue(coordinator.install(second.token, secondJob))

        assertFalse(coordinator.complete(first.token, firstJob))
        assertTrue(coordinator.snapshot().pending)
        assertTrue(coordinator.complete(second.token, secondJob))
        assertEquals(
            UsbAudioSinkReconfigurationSnapshot(pending = false, reason = null),
            coordinator.snapshot()
        )

        firstJob.cancel()
        secondJob.cancel()
    }

    @Test
    fun `uninstalled request can be abandoned without touching newer request`() {
        val coordinator = UsbAudioSinkReconfigurationCoordinator()
        val first = coordinator.begin("first")
        val second = coordinator.begin("second")

        assertFalse(coordinator.abandonIfUninstalled(first.token))
        assertTrue(coordinator.snapshot().pending)
        assertTrue(coordinator.abandonIfUninstalled(second.token))
        assertFalse(coordinator.snapshot().pending)
    }

    @Test
    fun `invalidate returns active job and rejects late completion`() {
        val coordinator = UsbAudioSinkReconfigurationCoordinator()
        val start = coordinator.begin("release")
        val job = Job()
        assertTrue(coordinator.install(start.token, job))

        assertSame(job, coordinator.invalidate())
        assertFalse(coordinator.isLatest(start.token))
        assertFalse(coordinator.complete(start.token, job))
        assertFalse(coordinator.snapshot().pending)
        assertNull(coordinator.invalidate())

        job.cancel()
    }
}
