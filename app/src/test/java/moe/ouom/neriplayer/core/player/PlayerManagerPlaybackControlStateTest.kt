package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
