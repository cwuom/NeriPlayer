package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyriconUpdateCoordinatorTest {

    @Test
    fun `replacement cancels old job before publishing current job`() {
        val coordinator = LyriconUpdateCoordinator()
        val oldJob = Job()
        coordinator.replace(createJob = { oldJob }, onPublished = {})

        val currentJob = Job()
        var publishedRequest: LyriconUpdateRequest? = null
        val currentRequest = coordinator.replace(
            createJob = { currentJob },
            onPublished = { request ->
                publishedRequest = request
                assertTrue(oldJob.isCancelled)
                assertFalse(currentJob.isCancelled)
                assertTrue(
                    coordinator.runIfCurrent(request.generation, currentJob) {}
                )
            },
        )

        assertSame(currentRequest, publishedRequest)
        assertTrue(coordinator.hasPendingJob())

        oldJob.cancel()
        currentJob.cancel()
    }

    @Test
    fun `stale completion cannot update after a newer job is published`() {
        val coordinator = LyriconUpdateCoordinator()
        val oldJob = Job()
        val oldRequest = coordinator.replace(createJob = { oldJob }, onPublished = {})
        val currentJob = Job()
        val currentRequest = coordinator.replace(createJob = { currentJob }, onPublished = {})
        var staleCompletionRan = false
        var currentCompletionRan = false

        assertFalse(
            coordinator.runIfCurrent(oldRequest.generation, oldJob) {
                staleCompletionRan = true
            }
        )
        assertTrue(
            coordinator.runIfCurrent(currentRequest.generation, currentJob) {
                currentCompletionRan = true
            }
        )
        assertFalse(staleCompletionRan)
        assertTrue(currentCompletionRan)

        oldJob.cancel()
        currentJob.cancel()
    }

    @Test
    fun `cancellation invalidates current completion`() {
        val coordinator = LyriconUpdateCoordinator()
        val job = Job()
        val request = coordinator.replace(createJob = { job }, onPublished = {})

        coordinator.cancelActive()

        assertTrue(job.isCancelled)
        assertFalse(coordinator.hasPendingJob())
        assertFalse(coordinator.runIfCurrent(request.generation, job) {})
    }
}
