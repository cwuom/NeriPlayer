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
}
