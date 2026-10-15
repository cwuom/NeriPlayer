package moe.ouom.neriplayer.core.player.policy.usb

import android.media.AudioDeviceInfo
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
    fun `anchor candidates use short streaming writes`() {
        val specs = usbExclusiveBackgroundAudioAnchorSpecs()

        assertEquals(
            UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming,
            specs.first().transferMode
        )
        assertTrue(specs.all { it.transferMode == UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming })
        assertTrue(specs.any { it.channelCount == 2 })
        assertTrue(specs.all { it.sampleRateHz > 0 && it.bufferFrames > 0 })
        assertTrue(specs.all { it.bufferFrames <= it.sampleRateHz / 10 })
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

        assertEquals(listOf(256, 256, -256, -256, 256, 256, -256, -256), samples)
        assertEquals(0, samples.sum())
        assertTrue(samples.all { sample -> kotlin.math.abs(sample) <= 256 })
    }

    @Test
    fun `carrier requires a streaming anchor on the confirmed built in route`() {
        assertTrue(
            shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier(
                transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming,
                routedToBuiltInOutput = true
            )
        )
        assertFalse(
            shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier(
                transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.StaticLoop,
                routedToBuiltInOutput = true
            )
        )
        assertFalse(
            shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier(
                transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming,
                routedToBuiltInOutput = false
            )
        )
    }

    @Test
    fun `carrier follows a usb route only while native output owns the stream interface`() {
        assertTrue(
            shouldWriteUsbExclusiveBackgroundAudioAnchorCarrier(
                transferMode = UsbExclusiveBackgroundAudioAnchorTransferMode.Streaming,
                routedToBuiltInOutput = false,
                routedToClaimedUsbOutput = true
            )
        )
        assertTrue(isUsbExclusiveBackgroundAudioAnchorUsbRoute(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertTrue(isUsbExclusiveBackgroundAudioAnchorUsbRoute(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(isUsbExclusiveBackgroundAudioAnchorUsbRoute(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertFalse(isUsbExclusiveBackgroundAudioAnchorUsbRoute(null))
        assertTrue(isUsbExclusiveStreamInterfaceClaimed(opened = true, streaming = true, transitioning = false))
        assertFalse(isUsbExclusiveStreamInterfaceClaimed(opened = true, streaming = true, transitioning = true))
        assertFalse(isUsbExclusiveStreamInterfaceClaimed(opened = true, streaming = false, transitioning = false))
        assertFalse(isUsbExclusiveStreamInterfaceClaimed(opened = false, streaming = true, transitioning = false))
    }

    @Test
    fun `usb carrier stays silent unless the routed usb output is the only one`() {
        assertTrue(isUsbExclusiveAnchorRouteTheClaimedDevice(usbOutputCount = 1))
        assertFalse(isUsbExclusiveAnchorRouteTheClaimedDevice(usbOutputCount = 2))
        assertFalse(isUsbExclusiveAnchorRouteTheClaimedDevice(usbOutputCount = 0))
    }

    @Test
    fun `only the built in speaker counts as a carrier route`() {
        assertTrue(isUsbExclusiveBackgroundAudioAnchorBuiltInRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(isUsbExclusiveBackgroundAudioAnchorBuiltInRoute(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(isUsbExclusiveBackgroundAudioAnchorBuiltInRoute(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(isUsbExclusiveBackgroundAudioAnchorBuiltInRoute(null))
    }

    @Test
    fun `anchor carrier rejects empty and invalid shapes`() {
        assertTrue(usbExclusiveBackgroundAudioAnchorCarrier(0, 2).isEmpty())
        assertTrue(usbExclusiveBackgroundAudioAnchorCarrier(8, 0).isEmpty())
    }
}
