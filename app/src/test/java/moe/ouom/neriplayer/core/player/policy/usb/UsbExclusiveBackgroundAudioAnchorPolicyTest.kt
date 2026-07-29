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

    @Test
    fun `built in anchor carrier is a bounded zero mean PCM signal`() {
        val carrier = usbExclusiveBackgroundAudioAnchorCarrier(
            bufferBytes = 16,
            channelCount = 2
        )
        val samples = carrier
            .asList()
            .chunked(2)
            .map { bytes ->
                (bytes[0].toInt() and 0xff) or (bytes[1].toInt() shl 8)
            }
            .map { sample -> sample.toShort().toInt() }

        assertEquals(listOf(1, 1, -1, -1, 1, 1, -1, -1), samples)
        assertEquals(0, samples.sum())
        assertTrue(samples.all { sample -> kotlin.math.abs(sample) <= 1 })
    }

    @Test
    fun `anchor carrier rejects empty and invalid shapes`() {
        assertTrue(usbExclusiveBackgroundAudioAnchorCarrier(0, 2).isEmpty())
        assertTrue(usbExclusiveBackgroundAudioAnchorCarrier(8, 0).isEmpty())
    }
}
