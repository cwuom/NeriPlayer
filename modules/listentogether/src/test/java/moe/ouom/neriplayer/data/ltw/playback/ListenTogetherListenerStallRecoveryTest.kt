package moe.ouom.neriplayer.data.ltw.playback

import androidx.media3.common.Player
import moe.ouom.neriplayer.data.ltw.testing.*
import org.junit.Assert.*
import org.junit.Test

class ListenTogetherListenerStallRecoveryTest {
    @Test
    fun `stall recovery waits for timeout and cooldown and resets on track change`() {
        val player = FakeListenTogetherPlaybackHost().apply { currentSongFlow.value = testSong(); playWhenReadyFlow.value = true }
        val owner = ListenTogetherListenerStallRecovery(player, TestSongMapper, 8_000L, 12_000L)
        val room = testRoom(playing = true)
        assertFalse(owner.shouldRecover(room, 1_000L))
        assertFalse(owner.shouldRecover(room, 5_000L))
        assertFalse(owner.shouldRecover(room, 9_000L))
        assertTrue(owner.shouldRecover(room, 13_000L))
        assertFalse(owner.shouldRecover(room, 14_000L))
        assertTrue(owner.shouldRecover(room, 25_000L))
        owner.reset()
        assertFalse(owner.shouldRecover(room, 26_000L))
        player.currentSongFlow.value = testSong("2")
        assertFalse(owner.shouldRecover(testRoom(listOf(testTrack("2")), playing = true), 40_000L))
    }

    @Test
    fun `paused playing missing and mismatched tracks never initiate recovery`() {
        val player = FakeListenTogetherPlaybackHost()
        val owner = ListenTogetherListenerStallRecovery(player, TestSongMapper, 8_000L, 12_000L)
        assertFalse(owner.shouldRecover(testRoom(), 1_000L))
        player.isPlayingFlow.value = true
        assertFalse(owner.shouldRecover(testRoom(playing = true), 1_000L))
        player.isPlayingFlow.value = false
        assertFalse(owner.shouldRecover(testRoom(emptyList(), playing = true), 1_000L))
        assertFalse(owner.shouldRecover(testRoom(playing = true), 1_000L))
        player.currentSongFlow.value = testSong("2")
        assertFalse(owner.shouldRecover(testRoom(playing = true), 1_000L))
        player.currentSongFlow.value = testSong()
        player.playerPlaybackStateFlow.value = Player.STATE_READY
        assertFalse(owner.shouldRecover(testRoom(playing = true), 1_000L))
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING)) {
            player.playerPlaybackStateFlow.value = state
            owner.reset()
            assertFalse(owner.shouldRecover(testRoom(playing = true), 1_000L))
            assertTrue(owner.shouldRecover(testRoom(playing = true), 13_000L))
        }
        player.playerPlaybackStateFlow.value = Player.STATE_READY
        player.pendingMediaLoad = true
        owner.reset()
        assertFalse(owner.shouldRecover(testRoom(playing = true), 1_000L))
        assertTrue(owner.shouldRecover(testRoom(playing = true), 13_000L))
    }
}
