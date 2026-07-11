package moe.ouom.neriplayer.core.player.policy

import org.junit.Assert.assertFalse
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
}
