package moe.ouom.neriplayer.core.player.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressUpdatePolicyTest {
    @Test
    fun `ready intent keeps progress sampler alive before isPlaying edge`() {
        assertTrue(
            shouldRunPlaybackProgressUpdates(
                initialized = true,
                pendingMediaLoad = false,
                hasMediaItem = true,
                isPlaying = false,
                playWhenReady = true
            )
        )
    }

    @Test
    fun `pending media load keeps old sampler stopped`() {
        assertFalse(
            shouldRunPlaybackProgressUpdates(
                initialized = true,
                pendingMediaLoad = true,
                hasMediaItem = true,
                isPlaying = true,
                playWhenReady = true
            )
        )
    }

    @Test
    fun `paused media does not run progress sampler`() {
        assertFalse(
            shouldRunPlaybackProgressUpdates(
                initialized = true,
                pendingMediaLoad = false,
                hasMediaItem = true,
                isPlaying = false,
                playWhenReady = false
            )
        )
    }

    @Test
    fun `startup progress must advance beyond resume baseline`() {
        assertFalse(
            hasPlaybackProgressAdvancedSinceBaseline(
                currentPositionMs = 30_000L,
                baselinePositionMs = 30_000L,
                toleranceMs = 500L
            )
        )
        assertTrue(
            hasPlaybackProgressAdvancedSinceBaseline(
                currentPositionMs = 30_700L,
                baselinePositionMs = 30_000L,
                toleranceMs = 500L
            )
        )
    }

    @Test
    fun `progress cadence keeps startup high frequency before first advance`() {
        assertEquals(
            PLAYBACK_PROGRESS_STARTUP_UPDATE_INTERVAL_MS,
            resolvePlaybackProgressUpdateIntervalMs(
                playbackProgressAdvanceReported = false,
                interactiveNowPlayingVisible = false
            )
        )
    }

    @Test
    fun `progress cadence slows down after startup according to visibility`() {
        assertEquals(
            PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS,
            resolvePlaybackProgressUpdateIntervalMs(
                playbackProgressAdvanceReported = true,
                interactiveNowPlayingVisible = true
            )
        )
        assertEquals(100L, PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS)
        assertEquals(
            PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS,
            resolvePlaybackProgressUpdateIntervalMs(
                playbackProgressAdvanceReported = true,
                interactiveNowPlayingVisible = false
            )
        )
    }
}
