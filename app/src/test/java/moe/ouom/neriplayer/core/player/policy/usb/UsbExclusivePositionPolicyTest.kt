package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExclusivePositionPolicyTest {
    @Test
    fun `completed frames are the primary native clock`() {
        val positionUs = resolveUsbExclusiveCompletedPositionUs(
            startMediaTimeUs = 1_000_000L,
            completedFrames = 48_000L,
            completedFramesAtTimelineStart = 0L,
            outputSampleRate = 48_000,
            clockPositionUs = 5_000_000L,
            lastPositionUs = 1_000_000L,
            extrapolationWindowUs = 250_000L,
            canExtrapolate = false
        )

        assertEquals(2_000_000L, positionUs)
    }

    @Test
    fun `active playback only extrapolates inside the bounded window`() {
        val positionUs = resolveUsbExclusiveCompletedPositionUs(
            startMediaTimeUs = 0L,
            completedFrames = 48_000L,
            completedFramesAtTimelineStart = 0L,
            outputSampleRate = 48_000,
            clockPositionUs = 3_000_000L,
            lastPositionUs = 0L,
            extrapolationWindowUs = 250_000L,
            canExtrapolate = true
        )

        assertEquals(1_250_000L, positionUs)
    }

    @Test
    fun `stale completion counters keep the last safe position`() {
        val positionUs = resolveUsbExclusiveCompletedPositionUs(
            startMediaTimeUs = 0L,
            completedFrames = 10L,
            completedFramesAtTimelineStart = 20L,
            outputSampleRate = 48_000,
            clockPositionUs = 2_000_000L,
            lastPositionUs = 900_000L,
            extrapolationWindowUs = 250_000L,
            canExtrapolate = true
        )

        assertEquals(900_000L, positionUs)
    }
}
