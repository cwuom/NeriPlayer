package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveRuntimeReportSamplingPolicyTest {
    @Test
    fun samplesWhenNoPreviousSampleExists() {
        assertTrue(
            UsbExclusiveRuntimeReportSamplingPolicy.shouldSampleFullRuntimeReport(
                nowMs = 10_000L,
                lastSampleAtMs = 0L,
                intervalMs = 15_000L
            )
        )
    }

    @Test
    fun samplesWhenIntervalIsDisabled() {
        assertTrue(
            UsbExclusiveRuntimeReportSamplingPolicy.shouldSampleFullRuntimeReport(
                nowMs = 10_000L,
                lastSampleAtMs = 9_999L,
                intervalMs = 0L
            )
        )
    }

    @Test
    fun skipsInsideSamplingWindow() {
        assertFalse(
            UsbExclusiveRuntimeReportSamplingPolicy.shouldSampleFullRuntimeReport(
                nowMs = 20_000L,
                lastSampleAtMs = 10_000L,
                intervalMs = 15_000L
            )
        )
    }

    @Test
    fun samplesAfterSamplingWindow() {
        assertTrue(
            UsbExclusiveRuntimeReportSamplingPolicy.shouldSampleFullRuntimeReport(
                nowMs = 25_000L,
                lastSampleAtMs = 10_000L,
                intervalMs = 15_000L
            )
        )
    }
}
