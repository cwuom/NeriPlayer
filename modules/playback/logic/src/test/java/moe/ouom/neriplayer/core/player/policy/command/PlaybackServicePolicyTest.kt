package moe.ouom.neriplayer.core.player.policy.command

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServicePolicyTest {
    @Test
    fun `each active playback phase keeps the service foreground and eligible for bootstrap`() {
        val phases = listOf(
            Phase(resumeRequested = true),
            Phase(playJob = true),
            Phase(pauseJob = true),
            Phase(playWhenReady = true),
            Phase(playing = true),
            Phase(state = Player.STATE_BUFFERING)
        )
        phases.forEach { phase ->
            assertTrue(shouldRunPlaybackServiceInForeground(
                true, phase.resumeRequested, phase.playJob, phase.pauseJob,
                phase.playWhenReady, phase.playing, phase.state
            ))
            assertTrue(shouldBootstrapPlaybackServiceOnAppLaunch(
                true, false, phase.resumeRequested, phase.playJob, phase.pauseJob,
                phase.playWhenReady, phase.playing, phase.state
            ))
        }
        assertFalse(shouldRunPlaybackServiceInForeground(false, true, true, true, true, true, Player.STATE_BUFFERING))
        assertFalse(shouldBootstrapPlaybackServiceOnAppLaunch(false, true, true, true, true, true, true, Player.STATE_BUFFERING))
    }

    private data class Phase(
        val resumeRequested: Boolean = false,
        val playJob: Boolean = false,
        val pauseJob: Boolean = false,
        val playWhenReady: Boolean = false,
        val playing: Boolean = false,
        val state: Int = Player.STATE_READY
    )
}
