package moe.ouom.neriplayer.core.player.lan.timing

import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDriftControllerTest {
    @Test fun boundsTenMinutesOfClockDriftInBothDirections() {
        for (clockPpm in listOf(-50, 50)) {
            for (initialErrorMs in listOf(-35.0, 0.0, 35.0)) {
                val controller = LanDriftController()
                val clockRatio = 1.0 + clockPpm / 1_000_000.0
                var errorMs = initialErrorMs
                var peakErrorMs = abs(initialErrorMs)
                var settledPeakMs = 0.0
                var previousSpeed = 1f
                var changes = 0
                val queuedSpeeds = ArrayDeque(listOf(1f, 1f))
                for (sample in 0 until 2_400) {
                    val elapsedMs = sample * 250L
                    val jitterMs = if (sample % 2 == 0) 0.6 else -0.6
                    val speed = controller.speedFor(errorMs + jitterMs, elapsedMs)
                    assertTrue(speed in 0.9975f..1.0025f)
                    if (speed != previousSpeed) changes++
                    previousSpeed = speed
                    // 已排队的半秒音频仍按此前的速率呈现
                    queuedSpeeds.addLast(speed)
                    errorMs += 250.0 * (1.0 - clockRatio * queuedSpeeds.removeFirst())
                    peakErrorMs = max(peakErrorMs, abs(errorMs))
                    if (elapsedMs >= 25_000) settledPeakMs = max(settledPeakMs, abs(errorMs))
                }
                assertTrue("peak=$peakErrorMs", peakErrorMs <= max(7.0, abs(initialErrorMs) + 1.0))
                assertTrue("settled peak=$settledPeakMs", settledPeakMs < 7.0)
                assertTrue("rate changes=$changes", changes in 1..40)
            }
        }
    }

    @Test fun isolatedNoiseNeverStartsCorrectionAndAReversalChangesDirection() {
        val controller = LanDriftController()
        for (sample in 0..20) {
            val errorMs = if (sample % 2 == 0) 6.0 else -6.0
            assertEquals(1f, controller.speedFor(errorMs, sample * 250L), 0f)
        }
        assertTrue(controller.speedFor(7.0, 5_250) > 1f)
        assertEquals(1f, controller.speedFor(0.0, 5_500), 0f)
        controller.speedFor(-7.0, 5_750)
        assertTrue(controller.speedFor(-7.0, 6_000) < 1f)
    }

    @Test fun badObservationsDisarmAnActiveCorrectionAndRequireFreshConfirmation() {
        val invalidSamples = listOf(
            20.0 to 250L,
            20.0 to 150L,
            20.0 to -1L,
            20.0 to 3_500L,
            -90.0 to 500L,
            270.0 to 500L,
            Double.NaN to 500L,
            Double.POSITIVE_INFINITY to 500L,
            Double.NEGATIVE_INFINITY to 500L,
        )
        for ((errorMs, sampleMs) in invalidSamples) {
            val controller = LanDriftController()
            assertEquals(1f, controller.speedFor(20.0, 0), 0f)
            assertTrue(controller.speedFor(20.0, 250) > 1f)
            assertEquals(1f, controller.speedFor(errorMs, sampleMs), 0f)
            assertEquals(1f, controller.speedFor(-20.0, sampleMs + 250), 0f)
            assertTrue(controller.speedFor(-20.0, sampleMs + 500) < 1f)
        }
    }

    @Test fun stalledPresentationDoesNotKeepAccelerating() {
        val controller = LanDriftController()
        controller.speedFor(12.0, 0)
        assertTrue(controller.speedFor(12.0, 200) > 1f)
        for (missed in 1..5) {
            assertEquals(1f, controller.speedFor(12.0 + missed * 200.0, 200L + missed * 200), 0f)
        }
    }

    @Test fun rapidPollsOfStalledOutputCannotArmTheController() {
        val controller = LanDriftController()
        for (elapsedMs in 0L..50L) {
            assertEquals(1f, controller.speedFor(10.0 + elapsedMs, elapsedMs), 0f)
        }
    }

    @Test fun sustainedGrossOffsetStaysAtNormalSpeed() {
        for (errorMs in listOf(-300.0, 300.0)) {
            val controller = LanDriftController()
            for (sample in 0..10) {
                assertEquals(1f, controller.speedFor(errorMs, sample * 250L), 0f)
            }
        }
    }

    @Test fun resetDropsHistoryBeforeTheFirstSampleOfAnotherPlan() {
        val controller = LanDriftController()
        controller.speedFor(20.0, 0)
        assertTrue(controller.speedFor(20.0, 250) > 1f)
        controller.reset()
        assertEquals(1f, controller.speedFor(-20.0, 500), 0f)
        assertTrue(controller.speedFor(-20.0, 750) < 1f)
    }
}
