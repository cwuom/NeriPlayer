package moe.ouom.neriplayer.core.player

import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.Lifecycle
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
                callerHasResumedUi = false
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
                callerHasResumedUi = false
            )
        )
    }

    @Test
    fun `resumed activity caller avoids foreground service timer`() {
        assertFalse(
            shouldUseForegroundServiceStart(
                sdkInt = 36,
                forceForeground = true,
                shouldRunPlaybackServiceInForeground = true,
                callerHasResumedUi = true
            )
        )
    }

    @Test
    fun `only resumed activity can use direct playback service start`() {
        assertTrue(
            canUseDirectPlaybackServiceStart(
                isFinishing = false,
                isDestroyed = false,
                lifecycleState = Lifecycle.State.RESUMED,
                hasWindowFocus = true
            )
        )
        assertFalse(
            canUseDirectPlaybackServiceStart(
                isFinishing = false,
                isDestroyed = false,
                lifecycleState = Lifecycle.State.STARTED,
                hasWindowFocus = true
            )
        )
        assertFalse(
            canUseDirectPlaybackServiceStart(
                isFinishing = false,
                isDestroyed = false,
                lifecycleState = Lifecycle.State.RESUMED,
                hasWindowFocus = false
            )
        )
    }

    @Test
    fun `service start not allowed failure is downgraded`() {
        assertTrue(
            isServiceStartNotAllowedFailure(
                IllegalStateException("Not allowed to start service Intent { act=test }")
            )
        )
        assertFalse(
            isServiceStartNotAllowedFailure(IllegalStateException("different failure"))
        )
    }

    @Test
    fun `recent explicit sync start suppresses redundant app bootstrap start`() {
        assertTrue(
            shouldSkipRedundantSyncServiceStart(
                source = "app_bootstrap",
                lastSuccessfulSource = "external_audio_import",
                lastSuccessfulStartElapsedRealtime = 10_000L,
                nowElapsedRealtime = 10_800L
            )
        )
    }

    @Test
    fun `stale sync start does not suppress app bootstrap`() {
        assertFalse(
            shouldSkipRedundantSyncServiceStart(
                source = "app_bootstrap",
                lastSuccessfulSource = "external_audio_import",
                lastSuccessfulStartElapsedRealtime = 10_000L,
                nowElapsedRealtime = 12_000L
            )
        )
    }

    @Test
    fun `non bootstrap sources are never deduped by bootstrap policy`() {
        assertFalse(
            shouldSkipRedundantSyncServiceStart(
                source = "play_songs_and_open_now_playing",
                lastSuccessfulSource = "external_audio_import",
                lastSuccessfulStartElapsedRealtime = 10_000L,
                nowElapsedRealtime = 10_200L
            )
        )
    }

    @Test
    fun `local playback sync start is skipped only when service is already ready`() {
        assertTrue(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = true,
                hasItems = true
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = false,
                hasItems = true
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "local_playback_command_play_playlist",
                serviceReady = true,
                hasItems = false
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = "app_bootstrap",
                serviceReady = true,
                hasItems = true
            )
        )
    }

    @Test
    fun `opening now playing from local playback is treated as local sync start only for local songs`() {
        assertTrue(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                serviceReady = true,
                hasItems = true,
                hasLocalCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipLocalPlaybackSyncServiceStart(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                serviceReady = true,
                hasItems = true,
                hasLocalCurrentSong = false
            )
        )
    }

    @Test
    fun `local playback action sync is skipped only after service is already tracking a song`() {
        assertTrue(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = false,
                hasItems = true,
                hasCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "local_playback_command_play_playlist",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = false
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = "app_bootstrap",
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true
            )
        )
    }

    @Test
    fun `opening now playing from local playback skips full sync only when current song is local`() {
        assertTrue(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true,
                hasLocalCurrentSong = true
            )
        )
        assertFalse(
            shouldSkipFullSyncForLocalPlaybackAction(
                source = PLAY_SONGS_AND_OPEN_NOW_PLAYING_SOURCE,
                foregroundStarted = true,
                hasItems = true,
                hasCurrentSong = true,
                hasLocalCurrentSong = false
            )
        )
    }
}
