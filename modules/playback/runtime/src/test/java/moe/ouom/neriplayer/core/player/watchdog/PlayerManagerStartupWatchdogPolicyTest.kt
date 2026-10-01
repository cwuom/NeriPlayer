package moe.ouom.neriplayer.core.player.watchdog

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerStartupWatchdogPolicyTest {
    @Test
    fun `buffering with no useful buffer recovers during early watchdog`() {
        assertTrue(
            shouldRecoverFromEarlyStartupStall(
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = true,
                advancedMs = 0L,
                bufferedDurationMs = 1_999L
            )
        )
    }

    @Test
    fun `buffering with a useful buffer gets more time to become ready`() {
        assertFalse(
            shouldRecoverFromEarlyStartupStall(
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = true,
                advancedMs = 0L,
                bufferedDurationMs = 2_000L
            )
        )
    }

    @Test
    fun `ready without progress still recovers early`() {
        assertTrue(
            shouldRecoverFromEarlyStartupStall(
                playbackState = Player.STATE_READY,
                playWhenReady = true,
                advancedMs = 500L,
                bufferedDurationMs = 0L
            )
        )
    }

    @Test
    fun `paused or advancing playback never triggers early recovery`() {
        assertFalse(
            shouldRecoverFromEarlyStartupStall(
                playbackState = Player.STATE_BUFFERING,
                playWhenReady = false,
                advancedMs = 0L,
                bufferedDurationMs = 0L
            )
        )
        assertFalse(
            shouldRecoverFromEarlyStartupStall(
                playbackState = Player.STATE_READY,
                playWhenReady = true,
                advancedMs = 501L,
                bufferedDurationMs = 0L
            )
        )
    }
}
