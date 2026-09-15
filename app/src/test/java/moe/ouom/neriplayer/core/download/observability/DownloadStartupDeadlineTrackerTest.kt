package moe.ouom.neriplayer.core.download.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartupDeadlineTrackerTest {

    @Test
    fun `startup boundaries use monotonic elapsed times`() {
        var nowNs = 100L
        val tracker = DownloadStartupDeadlineTracker(
            nowNs = { nowNs },
            deadlineNs = 500L
        )

        val started = tracker.begin()
        nowNs = 200L
        tracker.markQueueReady(started.generation)
        nowNs = 450L
        val completed = tracker.markTransferStarted(started.generation)

        requireNotNull(completed)
        assertEquals(100L, completed.t0ToT1Ns)
        assertEquals(250L, completed.t1ToT2Ns)
        assertEquals(350L, completed.t0ToT2Ns)
        assertTrue(completed.withinDeadline == true)
        assertEquals(DownloadStartupDeadlineTracker.Phase.TRANSFER_STARTED, completed.phase)
    }

    @Test
    fun `stale generation cannot write into a newer startup`() {
        var nowNs = 0L
        val tracker = DownloadStartupDeadlineTracker(nowNs = { nowNs })
        val oldGeneration = tracker.begin().generation
        nowNs = 10L
        val newGeneration = tracker.begin().generation

        assertEquals(null, tracker.markQueueReady(oldGeneration))
        val snapshot = tracker.snapshot()
        assertEquals(newGeneration, snapshot.generation)
        assertEquals(DownloadStartupDeadlineTracker.Phase.INTENT_RECORDED, snapshot.phase)
        assertEquals(null, snapshot.t1Ns)
    }

    @Test
    fun `blocked startup keeps reason and does not report transfer`() {
        val tracker = DownloadStartupDeadlineTracker(nowNs = { 10L })
        val generation = tracker.begin().generation

        val blocked = tracker.markBlocked("provider_permission", generation)

        requireNotNull(blocked)
        assertEquals(DownloadStartupDeadlineTracker.Phase.BLOCKED, blocked.phase)
        assertEquals("provider_permission", blocked.blockedReason)
        assertEquals(null, blocked.t2Ns)
        assertEquals(null, blocked.withinDeadline)
        assertEquals(
            DownloadStartupDeadlineTracker.Phase.BLOCKED,
            tracker.markTransferStarted(generation)?.phase
        )
    }

    @Test
    fun `transfer start implicitly records queue boundary`() {
        var nowNs = 1L
        val tracker = DownloadStartupDeadlineTracker(nowNs = { nowNs })
        val generation = tracker.begin().generation
        nowNs = 2L

        val snapshot = tracker.markTransferStarted(generation)

        requireNotNull(snapshot)
        assertEquals(snapshot.t1Ns, snapshot.t2Ns)
        assertEquals(1L, snapshot.t0ToT2Ns)
    }

    @Test
    fun `callback failure does not affect boundary recording`() {
        var callbackCount = 0
        val tracker = DownloadStartupDeadlineTracker(
            onSnapshot = {
                callbackCount++
                error("diagnostic sink unavailable")
            }
        )

        val generation = tracker.begin().generation
        tracker.markQueueReady(generation)
        tracker.markTransferStarted(generation)

        assertEquals(3, callbackCount)
        assertEquals(
            DownloadStartupDeadlineTracker.Phase.TRANSFER_STARTED,
            tracker.snapshot().phase
        )
    }
}
