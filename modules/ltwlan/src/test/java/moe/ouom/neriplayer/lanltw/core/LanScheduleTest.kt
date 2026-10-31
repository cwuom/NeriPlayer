package moe.ouom.neriplayer.lanltw.core
import org.junit.Assert.*
import org.junit.Test
class LanScheduleTest {
    @Test fun upstreamDelayAndCompensationOrder() {
        assertEquals(400.0, LanSchedule.executionDelay(0.0, 0.0), .00001)
        assertEquals(950.0, LanSchedule.executionDelay(500.0, 0.0), .00001)
        assertEquals(3000.0, LanSchedule.executionDelay(9000.0, 0.0), .00001)
        assertEquals(5700.0, LanSchedule.executionDelay(0.0, 4000.0, 1500.0), .00001)
    }
    @Test fun positiveNudgeAdvancesOnlyTheDeadline() {
        val p = LanSchedule.play(1000.0, 50.0, 100, 40.0, 2000.0, 10.0)
        assertEquals(810L, p.delayMs); assertEquals(10.0, p.positionSeconds, .00001)
        assertEquals(810L, LanSchedule.pauseDelay(1000.0, 50.0, 100, 40.0, 2000.0))
    }
    @Test fun doubleThresholdAndFullLatenessArePreserved() {
        val late = LanSchedule.play(1200.0, 0.0, 0, 40.0, 1000.0, 10.0)
        assertEquals(250L, late.delayMs); assertEquals(10.45, late.positionSeconds, .00001)
        val localCompensation = LanSchedule.play(1000.0, 0.0, 100, 40.0, 1100.0, 10.0)
        assertEquals(0L, localCompensation.delayMs); assertEquals(10.0, localCompensation.positionSeconds, .00001)
        val boundary = LanSchedule.play(1000.0, 0.0, 0, 0.0, 1050.0, 10.0)
        assertEquals(50L, boundary.delayMs)
    }
}
