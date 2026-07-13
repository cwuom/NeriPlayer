package moe.ouom.neriplayer.core.player.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceRestartPolicyTest {
    @Test
    fun `paused queue remains sticky until the configured idle shutdown`() {
        assertTrue(
            resolveShouldKeepSticky(
                hasResumableQueue = true,
                foregroundPlaybackRequired = false,
            )
        )
    }

    @Test
    fun `foreground playback and listen together remain sticky`() {
        assertTrue(resolveShouldKeepSticky(foregroundPlaybackRequired = true))
        assertTrue(resolveShouldKeepSticky(listenTogetherSessionActive = true))
    }

    @Test
    fun `unready or empty service does not remain sticky`() {
        assertFalse(resolveShouldKeepSticky(playerRuntimeReady = false))
        assertFalse(resolveShouldKeepSticky(hasPlaybackSurfaceContent = false))
    }

    @Test
    fun `system restart stays sticky while runtime initializes`() {
        assertTrue(
            shouldUseStickyStartModeWhilePlayerRuntimeInitializes(
                hasExplicitAction = false
            )
        )
        assertFalse(
            shouldUseStickyStartModeWhilePlayerRuntimeInitializes(
                hasExplicitAction = true
            )
        )
    }

    private fun resolveShouldKeepSticky(
        playerRuntimeReady: Boolean = true,
        hasPlaybackSurfaceContent: Boolean = true,
        hasResumableQueue: Boolean = false,
        foregroundPlaybackRequired: Boolean = false,
        listenTogetherSessionActive: Boolean = false,
    ): Boolean {
        return shouldKeepPlaybackServiceSticky(
            playerRuntimeReady = playerRuntimeReady,
            hasPlaybackSurfaceContent = hasPlaybackSurfaceContent,
            hasResumableQueue = hasResumableQueue,
            foregroundPlaybackRequired = foregroundPlaybackRequired,
            listenTogetherSessionActive = listenTogetherSessionActive,
        )
    }
}
