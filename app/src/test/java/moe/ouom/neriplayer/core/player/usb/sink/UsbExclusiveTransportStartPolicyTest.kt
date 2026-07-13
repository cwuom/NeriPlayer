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
                allowShortPreroll = true,
                resumingPausedTransport = true
            )
        )
    }
}
