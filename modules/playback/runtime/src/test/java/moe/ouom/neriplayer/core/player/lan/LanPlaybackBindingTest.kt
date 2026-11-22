package moe.ouom.neriplayer.core.player.lan
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
class LanPlaybackBindingTest {
    @After fun cleanup() { LanPlaybackBinding.clear() }
    @Test fun safetyPauseInterruptsExternalAudioWithoutRoutingMemberCommands() {
        val interruptions = mutableListOf<String>()
        LanPlaybackBinding.interruptAudio = { interruptions += it }
        assertFalse(LanPlaybackBinding.interrupt())
        LanPlaybackBinding.externalAudio = true
        assertFalse(LanPlaybackBinding.safetyPause(PlaybackCommandSource.LOCAL))
        assertTrue(LanPlaybackBinding.safetyPause(PlaybackCommandSource.LOCAL_SAFETY))
        assertTrue(LanPlaybackBinding.interrupt("OUTPUT"))
        assertEquals(listOf("AUDIO_SUSPENDED", "OUTPUT"), interruptions)
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

    @Test fun clearingTheHostQueuePausesTheRoom() {
        val requests = mutableListOf<String>()
        LanPlaybackBinding.isHost = { true }
        LanPlaybackBinding.command = { intent, _, _ -> requests += intent }

        LanPlaybackBinding.onQueueCleared()

        assertEquals(listOf("PAUSE"), requests)
    }

    @Test fun clearingAGuestQueueInterruptsOnlyTheLocalAudio() {
        val requests = mutableListOf<String>()
        var interrupted = 0
        LanPlaybackBinding.externalAudio = true
        LanPlaybackBinding.command = { intent, _, _ -> requests += intent }
        LanPlaybackBinding.interruptAudio = { interrupted++ }

        LanPlaybackBinding.onQueueCleared()

        assertEquals(1, interrupted)
        assertTrue(requests.isEmpty())
    }
}
