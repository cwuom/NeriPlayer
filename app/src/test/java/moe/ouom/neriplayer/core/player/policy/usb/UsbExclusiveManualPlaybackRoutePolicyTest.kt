package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveManualPlaybackRoutePolicyTest {

    @Test
    fun `manual playback skips USB rebuild when no USB route or host device exists`() {
        assertTrue(
            shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                hasUsbAudioOutput = false,
                hasUsbHostAudioDevice = false
            )
        )
    }

    @Test
    fun `manual playback keeps USB rebuild when a USB audio output is available`() {
        assertFalse(
            shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                hasUsbAudioOutput = true,
                hasUsbHostAudioDevice = true
            )
        )
    }

    @Test
    fun `manual playback keeps USB rebuild while a host device is still present`() {
        assertFalse(
            shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                hasUsbAudioOutput = false,
                hasUsbHostAudioDevice = true
            )
        )
    }

    @Test
    fun `manual playback does not skip rebuild when mixed playback is allowed`() {
        assertFalse(
            shouldSkipUsbExclusiveRouteRebuildForManualPlayback(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = true,
                hasUsbAudioOutput = false,
                hasUsbHostAudioDevice = false
            )
        )
    }

    @Test
    fun `noisy USB route stops strict exclusive playback`() {
        assertTrue(
            shouldStopUsbExclusivePlaybackForNoisyRoute(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                routeIsUsbOutput = true,
                playbackActive = true
            )
        )
    }

    @Test
    fun `noisy USB route does not stop mixed or inactive playback`() {
        assertFalse(
            shouldStopUsbExclusivePlaybackForNoisyRoute(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = true,
                routeIsUsbOutput = true,
                playbackActive = true
            )
        )
        assertFalse(
            shouldStopUsbExclusivePlaybackForNoisyRoute(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                routeIsUsbOutput = true,
                playbackActive = false
            )
        )
        assertFalse(
            shouldStopUsbExclusivePlaybackForNoisyRoute(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                routeIsUsbOutput = false,
                playbackActive = true
            )
        )
    }
}
