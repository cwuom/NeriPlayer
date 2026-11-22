package moe.ouom.neriplayer.core.player.lan.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LanHardwareTimingTest {
    private val route = LanPcmRoute(3, 48_000, 4096, 2, 12, false)

    @Test fun onlyTwoConsistentStartsOnTheSameRouteCanAdvanceADeadline() {
        val history = LanStartLatency()
        history.record(route, 40.0)
        assertNull(history.leadMs(route))
        assertEquals(100L, history.waitMs(100, true, route))
        history.record(route, 42.0)
        assertEquals(41L, history.leadMs(route))
        assertEquals(59L, history.waitMs(100, true, route))
        assertEquals(0L, history.waitMs(41, true, route))
        assertEquals(100L, history.waitMs(100, false, route))
        assertEquals(100L, history.waitMs(100, true, null))
        assertNull(history.leadMs(route.copy(id = 9)))
        assertNull(history.leadMs(route.copy(resuming = true)))
        history.record(route, 130.0)
        assertNull(history.leadMs(route))
    }

    @Test fun projectsPositionAtActualHardwareTimeAcrossClockDomains() {
        val frame = LanPcmStamp(4800, 2_000_000_000L)
        val elapsed = frame.elapsedAtOutputUs(120_000, 48_000, 2_010_000_000L, 8_010_000_000L)
        assertEquals(8020.0, elapsed!!, 0.001)
        assertNull(frame.elapsedAtOutputUs(800_000, 48_000, 2_010_000_000L, 8_010_000_000L))
        assertNull(frame.elapsedAtOutputUs(120_000, 48_000, 3_000_000_000L, 9_000_000_000L))
    }

    @Test fun resumeLatencyUsesThePausedHardwareFrameAndRejectsTimestampJumps() {
        val first = LanPcmStamp(52_800, 1_140_000_000L)
        assertEquals(40.0, first.startupLatencyMs(48_000, 48_000, 1_000_000_000L)!!, 0.001)
        assertNull(first.startupLatencyMs(0, 48_000, 1_000_000_000L))
        assertEquals(true, LanPcmStamp(53_760, 1_160_000_000L).follows(first, 48_000))
        assertEquals(false, LanPcmStamp(60_000, 1_160_000_000L).follows(first, 48_000))
    }

    @Test fun aCancelledOrSupersededHardwareCallbackCannotStartTheTrackOrPublishOldFrames() {
        val clock = LanHardwareAudioClock()
        clock.arm(1, 900)
        val old = checkNotNull(clock.plan)
        clock.cancel()
        assertFalse(clock.start(old, "cold") { throw AssertionError("cancelled start") })
        clock.arm(2, 1000)
        assertFalse(clock.start(old, "calibrated") { throw AssertionError("superseded start") })
        clock.observed(old, LanHardwareObservation(900.0, 0.0, 3))
        assertNull(clock.observation(2))
    }
}
