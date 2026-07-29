package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveBackgroundAudioAnchorPolicyTest {

    @Test
    fun `anchor runs only for active background USB playback in a foreground service`() {
        assertTrue(
            shouldRunUsbExclusiveBackgroundAudioAnchor(
                appInForeground = false,
                serviceForeground = true,
                usbExclusivePlaybackActive = true
            )
        )
        assertFalse(
            shouldRunUsbExclusiveBackgroundAudioAnchor(
                appInForeground = true,
                serviceForeground = true,
                usbExclusivePlaybackActive = true
            )
        )
        assertFalse(
            shouldRunUsbExclusiveBackgroundAudioAnchor(
                appInForeground = false,
                serviceForeground = false,
                usbExclusivePlaybackActive = true
            )
        )
        assertFalse(
            shouldRunUsbExclusiveBackgroundAudioAnchor(
                appInForeground = false,
                serviceForeground = true,
                usbExclusivePlaybackActive = false
            )
        )
    }

    @Test
    fun `anchor candidates prefer a static loop and retain streaming fallbacks`() {
        val specs = usbExclusiveBackgroundAudioAnchorSpecs()

        assertEquals(
            UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop,
            specs.first().transferMode
        )
        assertTrue(
            specs.any {
                it.transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming
            }
        )
        assertTrue(specs.any { it.channelCount == 2 })
        assertTrue(specs.all { it.sampleRateHz > 0 && it.bufferFrames > 0 })
        assertEquals(specs.size, specs.map { it.name }.toSet().size)
    }
}
