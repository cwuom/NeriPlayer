package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveManualPlaybackRoutePolicyTest {

    @Test
    fun `DAC recovery uses the managed queue index instead of Media3 single item index`() {
        val queueIndex = resolveUsbExclusiveInterruptedPlaybackQueueIndex(
            currentQueueIndex = 4,
            queueSize = 832,
            currentQueueIndexMatchesCurrentSong = true,
            currentSongQueueIndex = 4
        )

        assertEquals(4, queueIndex)
    }

    @Test
    fun `DAC recovery recovers the current song queue index when stored queue index is stale`() {
        val queueIndex = resolveUsbExclusiveInterruptedPlaybackQueueIndex(
            currentQueueIndex = 0,
            queueSize = 8,
            currentQueueIndexMatchesCurrentSong = false,
            currentSongQueueIndex = 4
        )

        assertEquals(4, queueIndex)
    }

    @Test
    fun `DAC recovery does not invent a queue item when no index is valid`() {
        val queueIndex = resolveUsbExclusiveInterruptedPlaybackQueueIndex(
            currentQueueIndex = -1,
            queueSize = 8,
            currentQueueIndexMatchesCurrentSong = false,
            currentSongQueueIndex = -1
        )

        assertNull(queueIndex)
    }

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

    @Test
    fun `native USB path owns noisy route handling while active`() {
        assertTrue(
            shouldDeferUsbExclusiveNoisyRouteToNativePath(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                routeIsUsbOutput = true,
                nativePlayerPcmActive = true
            )
        )
        assertFalse(
            shouldDeferUsbExclusiveNoisyRouteToNativePath(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                routeIsUsbOutput = true,
                nativePlayerPcmActive = false
            )
        )
    }

    @Test
    fun `attached selected DAC resumes only after permission and open gate clear`() {
        assertTrue(
            shouldResumeUsbExclusivePlaybackAfterDeviceAttach(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                hasInterruptedPlayback = true,
                resumePlaybackRequested = true,
                selectedUsbOutputAvailable = true,
                selectedUsbHostPermissionGranted = true,
                nativeOpenGateActive = false
            )
        )
        assertFalse(
            shouldResumeUsbExclusivePlaybackAfterDeviceAttach(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                hasInterruptedPlayback = true,
                resumePlaybackRequested = true,
                selectedUsbOutputAvailable = true,
                selectedUsbHostPermissionGranted = false,
                nativeOpenGateActive = false
            )
        )
        assertFalse(
            shouldResumeUsbExclusivePlaybackAfterDeviceAttach(
                usbExclusivePlaybackEnabled = true,
                allowMixedPlaybackEnabled = false,
                hasInterruptedPlayback = true,
                resumePlaybackRequested = true,
                selectedUsbOutputAvailable = true,
                selectedUsbHostPermissionGranted = true,
                nativeOpenGateActive = true
            )
        )
    }
}
