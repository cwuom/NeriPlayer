package moe.ouom.neriplayer.core.player.lan
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
class LanPlaybackBindingTest {
    @After fun cleanup() { LanPlaybackBinding.clear() }
    @Test fun safetyPauseInterruptsExternalAudioWithoutRoutingMemberCommands() {
        var interrupted = 0
        LanPlaybackBinding.interruptAudio = { interrupted++ }
        assertFalse(LanPlaybackBinding.interrupt())
        LanPlaybackBinding.externalAudio = true
        assertFalse(LanPlaybackBinding.safetyPause(PlaybackCommandSource.LOCAL))
        assertTrue(LanPlaybackBinding.safetyPause(PlaybackCommandSource.LOCAL_SAFETY))
        assertEquals(1, interrupted)
    }
    @Test fun remoteScheduledCommandsNeverLoopBackIntoRoomRequests() {
        val requests = mutableListOf<String>()
        LanPlaybackBinding.command = { intent, _, _ -> requests += intent }
        assertFalse(LanPlaybackBinding.route("PLAY_PAUSE", PlaybackCommandSource.REMOTE_SYNC, null, 0.0))
        assertTrue(LanPlaybackBinding.route("PAUSE", PlaybackCommandSource.LOCAL, null, 0.0))
        assertEquals(listOf("PAUSE"), requests)
        LanPlaybackBinding.clear()
        assertFalse(LanPlaybackBinding.route("PAUSE", PlaybackCommandSource.LOCAL, null, 0.0))
    }
}
