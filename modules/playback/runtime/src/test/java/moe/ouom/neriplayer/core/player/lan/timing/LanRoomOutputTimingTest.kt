package moe.ouom.neriplayer.core.player.lan.timing

import moe.ouom.neriplayer.core.player.lan.LanMedia3StartPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanRoomOutputTimingTest {
    private val timing = LanRoomOutputTiming()

    @Test fun correctedRoomClockAndNudgeStayOnTheScheduledTimeline() {
        timing.arm(1, LanMedia3StartPlan(1_000, 5_000), 300.0, 800)
        timing.updateClock(304.0, 1_180)
        sample(1_200, 5_198.0)
        assertEquals(6.0, timing.errorMs!!, 0.001)
        assertEquals(1.002f, sample(1_400, 5_398.0), 0.00001f)

        timing.movedByNudge(-30)
        timing.resumeOutput(2, 1_500)
        sample(1_600, 5_574.0, generation = 2)
        assertEquals(0.0, timing.errorMs!!, 0.001)
    }

    @Test fun observationsCannotCrossRoutePlanOrClockExpiry() {
        timing.arm(1, LanMedia3StartPlan(1_000, 0), 0.0, 900)
        sample(1_100, 90.0)
        assertEquals(1.002f, sample(1_300, 290.0), 0.00001f)
        assertEquals(1f, sample(1_500, 490.0, route = 2), 0f)
        assertEquals(1f, timing.speed(2, 1_501, 1.002f), 0f)
        assertNull(timing.errorMs)
        assertEquals(1f, sample(12_000, 10_900.0), 0f)
        assertNull(timing.errorMs)
        timing.cancel()
        assertNull(timing.hardware.plan)
    }

    private fun sample(elapsed: Long, position: Double, route: Int = 1, generation: Long = 1): Float {
        timing.hardware.observed(checkNotNull(timing.hardware.plan), LanHardwareObservation(elapsed.toDouble(), position, route))
        return timing.speed(generation, elapsed, 1f)
    }
}
