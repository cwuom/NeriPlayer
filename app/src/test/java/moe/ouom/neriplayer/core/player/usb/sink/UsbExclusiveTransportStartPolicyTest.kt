package moe.ouom.neriplayer.core.player.usb.sink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveTransportStartPolicyTest {

    @Test
    fun `paused transport resumes with queued audio below initial preroll`() {
        assertTrue(
            shouldStartUsbExclusiveNativeTransport(
                hasQueuedPcm = true,
                queuedFrames = 4_800L,
                requiredPrerollFrames = 14_400L,
                pcmCapacityFrames = 48_000L,
                allowShortPreroll = false,
                resumingPausedTransport = true
            )
        )
    }

    @Test
    fun `fresh transport waits for configured preroll`() {
        assertFalse(
            shouldStartUsbExclusiveNativeTransport(
                hasQueuedPcm = true,
                queuedFrames = 4_800L,
                requiredPrerollFrames = 14_400L,
                pcmCapacityFrames = 48_000L,
                allowShortPreroll = false,
                resumingPausedTransport = false
            )
        )
    }

    @Test
    fun `resume waits until at least one audio frame is queued`() {
        assertFalse(
            shouldStartUsbExclusiveNativeTransport(
                hasQueuedPcm = false,
                queuedFrames = 0L,
                requiredPrerollFrames = 14_400L,
                pcmCapacityFrames = 48_000L,
                allowShortPreroll = true,
                resumingPausedTransport = true
            )
        )
    }

    @Test
    fun `fresh transport starts before a short fifo reaches capacity`() {
        assertTrue(
            shouldStartUsbExclusiveNativeTransport(
                hasQueuedPcm = true,
                queuedFrames = 18_000L,
                requiredPrerollFrames = 28_800L,
                pcmCapacityFrames = 24_000L,
                allowShortPreroll = false,
                resumingPausedTransport = false
            )
        )
    }

    @Test
    fun `fresh transport still waits below the short fifo safety watermark`() {
        assertFalse(
            shouldStartUsbExclusiveNativeTransport(
                hasQueuedPcm = true,
                queuedFrames = 17_999L,
                requiredPrerollFrames = 28_800L,
                pcmCapacityFrames = 24_000L,
                allowShortPreroll = false,
                resumingPausedTransport = false
            )
        )
    }

    @Test
    fun `unknown fifo capacity keeps the requested preroll`() {
        assertFalse(
            shouldStartUsbExclusiveNativeTransport(
                hasQueuedPcm = true,
                queuedFrames = 24_000L,
                requiredPrerollFrames = 28_800L,
                pcmCapacityFrames = 0L,
                allowShortPreroll = false,
                resumingPausedTransport = false
            )
        )
    }
}
