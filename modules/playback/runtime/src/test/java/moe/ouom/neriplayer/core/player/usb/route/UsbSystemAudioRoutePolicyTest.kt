package moe.ouom.neriplayer.core.player.usb.route

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSystemAudioRoutePolicyTest {
    private val playingIntent = UsbSystemAudioSnapshot(
        usbEnabled = false, playerInitialized = true, mediaItemCount = 1, hasMediaItem = true,
        mediaItemIndex = 0, positionMs = 42L, playWhenReady = true, isPlaying = false,
        playbackState = Player.STATE_READY
    )

    @Test
    fun `watchdog only observes current system route with playback intent`() {
        assertTrue(playingIntent.watchdogRouteStillCurrent(3L, 3L))
        assertFalse(playingIntent.watchdogRouteStillCurrent(3L, 4L))
        assertFalse(playingIntent.copy(usbEnabled = true).watchdogRouteStillCurrent(3L, 3L))
        assertFalse(playingIntent.copy(playWhenReady = false).watchdogRouteStillCurrent(3L, 3L))
        assertFalse(playingIntent.copy(hasMediaItem = false).watchdogRouteStillCurrent(3L, 3L))
        assertFalse(playingIntent.copy(playerInitialized = false).watchdogRouteStillCurrent(3L, 3L))
    }

    @Test
    fun `watchdog retries only a stationary stalled item`() {
        assertTrue(playingIntent.playbackStalledAt(0, 0L))
        assertFalse(playingIntent.playbackStalledAt(1, 0L))
        assertFalse(playingIntent.copy(isPlaying = true).playbackStalledAt(0, 0L))
        assertFalse(playingIntent.copy(positionMs = 600L).playbackStalledAt(0, 0L))
        assertFalse(playingIntent.copy(playbackState = Player.STATE_ENDED).playbackStalledAt(0, 0L))
        assertTrue(playingIntent.copy(playbackState = Player.STATE_BUFFERING).playbackStalledAt(0, 0L))
    }

    @Test
    fun `release generation and enabled state prevent stale system reset`() {
        assertTrue(playingIntent.releaseStillCurrent(4L, 4L, true))
        assertTrue(playingIntent.copy(usbEnabled = true).releaseStillCurrent(4L, 4L, false))
        assertFalse(playingIntent.copy(usbEnabled = true).releaseStillCurrent(4L, 4L, true))
        assertFalse(playingIntent.releaseStillCurrent(4L, 5L, false))
        assertFalse(playingIntent.copy(playerInitialized = false).releaseStillCurrent(4L, 4L, false))
        assertTrue(playingIntent.canResetAfterRelease(4L, 4L))
        assertFalse(playingIntent.canResetAfterRelease(4L, 5L))
        assertFalse(playingIntent.copy(usbEnabled = true).canResetAfterRelease(4L, 4L))
        assertFalse(playingIntent.copy(playerInitialized = false).canResetAfterRelease(4L, 4L))
        assertTrue(playingIntent.hasResetMedia())
        assertFalse(playingIntent.copy(mediaItemCount = 0).hasResetMedia())
        assertFalse(playingIntent.copy(hasMediaItem = false).hasResetMedia())
    }
}
