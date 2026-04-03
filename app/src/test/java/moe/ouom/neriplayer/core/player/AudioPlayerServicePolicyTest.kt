package moe.ouom.neriplayer.core.player

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlayerServicePolicyTest {

    @Test
    fun `media session stop is downgraded to pause-only`() {
        assertFalse(
            shouldStopServiceForExternalPauseCommand(
                source = MEDIA_SESSION_STOP_SOURCE,
                stopServiceRequested = true,
            )
        )
    }

    @Test
    fun `explicit stop intent still tears down service`() {
        assertTrue(
            shouldStopServiceForExternalPauseCommand(
                source = "intent_stop",
                stopServiceRequested = true,
            )
        )
    }

    @Test
    fun `media session actions no longer advertise stop`() {
        val actions = mediaSessionPlaybackActions()

        assertEquals(0L, actions and PlaybackStateCompat.ACTION_STOP)
        assertTrue(actions and PlaybackStateCompat.ACTION_PLAY != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_PAUSE != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L)
        assertTrue(actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L)
    }

    @Test
    fun `android o and above always use foreground service start`() {
        assertTrue(
            shouldUseForegroundServiceStart(
                sdkInt = 26,
                forceForeground = false,
                shouldRunPlaybackServiceInForeground = false,
                callerHasVisibleUi = false
            )
        )
    }

    @Test
    fun `pre o can use background start when foreground is unnecessary`() {
        assertFalse(
            shouldUseForegroundServiceStart(
                sdkInt = 25,
                forceForeground = false,
                shouldRunPlaybackServiceInForeground = false,
                callerHasVisibleUi = false
            )
        )
    }

    @Test
    fun `visible activity caller avoids foreground service timer`() {
        assertFalse(
            shouldUseForegroundServiceStart(
                sdkInt = 36,
                forceForeground = true,
                shouldRunPlaybackServiceInForeground = true,
                callerHasVisibleUi = true
            )
        )
    }
}
