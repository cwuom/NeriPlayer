package moe.ouom.neriplayer.core.player.policy.command

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.playback.resolveAudioRouteMuteRestoreVolume
import moe.ouom.neriplayer.core.player.playback.shouldDeferAudioRouteMuteRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPlaybackControlStateTest {

    @Test
    fun `resume request shows pause button immediately`() {
        val shouldShowPause = shouldShowPauseButtonForPlaybackControls(
            resumePlaybackRequested = true,
            pendingPauseJobActive = false
        )

        assertTrue(shouldShowPause)
    }

    @Test
    fun `pause request keeps play button visible immediately`() {
        val shouldShowPause = shouldShowPauseButtonForPlaybackControls(
            resumePlaybackRequested = false,
            pendingPauseJobActive = false
        )

        assertFalse(shouldShowPause)
    }

    @Test
    fun `pending pause keeps play button visible even before fade completes`() {
        val shouldShowPause = shouldShowPauseButtonForPlaybackControls(
            resumePlaybackRequested = true,
            pendingPauseJobActive = true
        )

        assertFalse(shouldShowPause)
    }

    @Test
    fun `toggle prefers play when pause fade is still pending`() {
        val shouldPause = shouldPausePlaybackWhenToggling(
            resumePlaybackRequested = false,
            pendingPauseJobActive = true,
            playerIsPlaying = true,
            playerPlayWhenReady = true,
            playJobActive = false
        )

        assertFalse(shouldPause)
    }

    @Test
    fun `audio focus loss clears visual playback intent`() {
        val shouldClear = shouldClearResumePlaybackRequestOnPlayWhenReadyPause(
            playWhenReady = false,
            playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            pendingPauseJobActive = false,
            playJobActive = false
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `pending local pause keeps external callback from overriding local transition`() {
        val shouldClear = shouldClearResumePlaybackRequestOnPlayWhenReadyPause(
            playWhenReady = false,
            playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            pendingPauseJobActive = true,
            playJobActive = false
        )

        assertFalse(shouldClear)
    }

    @Test
    fun `buffering style user intent is kept for non external play when ready reasons`() {
        val shouldClear = shouldClearResumePlaybackRequestOnPlayWhenReadyPause(
            playWhenReady = false,
            playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            pendingPauseJobActive = false,
            playJobActive = false
        )

        assertFalse(shouldClear)
    }

    @Test
    fun `listen together noisy pause resumes silently only for muted listener route loss`() {
        assertTrue(
            shouldResumeSilentlyForListenTogetherNoisyPause(
                playWhenReady = false,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                muteListenTogetherListenerForAudioRouteLoss = true
            )
        )
        assertFalse(
            shouldResumeSilentlyForListenTogetherNoisyPause(
                playWhenReady = false,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                muteListenTogetherListenerForAudioRouteLoss = true
            )
        )
        assertFalse(
            shouldResumeSilentlyForListenTogetherNoisyPause(
                playWhenReady = false,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                muteListenTogetherListenerForAudioRouteLoss = false
            )
        )
    }

    @Test
    fun `route mute only records a restorable nonzero volume`() {
        assertNull(
            resolveAudioRouteMuteRestoreVolume(
                currentVolume = 0f,
                existingRestoreVolume = null
            )
        )
        assertEquals(
            0.6f,
            resolveAudioRouteMuteRestoreVolume(
                currentVolume = 0.6f,
                existingRestoreVolume = null
            ) ?: -1f,
            0f
        )
        assertEquals(
            0.4f,
            resolveAudioRouteMuteRestoreVolume(
                currentVolume = 1f,
                existingRestoreVolume = 0.4f
            ) ?: -1f,
            0f
        )
    }

    @Test
    fun `listener route mute defers transient restoration until explicit user action`() {
        assertTrue(shouldDeferAudioRouteMuteRestore(requiresExplicitRestore = true))
        assertFalse(shouldDeferAudioRouteMuteRestore(requiresExplicitRestore = false))
    }
}
