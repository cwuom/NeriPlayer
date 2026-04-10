package moe.ouom.neriplayer.core.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.player.policy.shouldClearResumePlaybackRequestOnPlayWhenReadyPause
import moe.ouom.neriplayer.core.player.policy.shouldShowPauseButtonForPlaybackControls
import moe.ouom.neriplayer.core.player.policy.shouldPausePlaybackWhenToggling

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
}
