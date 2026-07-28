package moe.ouom.neriplayer.core.player.policy.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionWakeLockPolicyTest {
    @Test
    fun `stale request cannot release the current transition lock`() {
        assertFalse(shouldReleasePlaybackTransitionWakeLock(1L, 2L))
        assertTrue(shouldReleasePlaybackTransitionWakeLock(2L, 2L))
    }

    @Test
    fun `transition lease is bounded`() {
        assertTrue(PLAYBACK_TRANSITION_WAKE_LOCK_LEASE_MS in 1L..60_000L)
    }
}
