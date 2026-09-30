package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.testing.FakeListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.testing.TestSongMapper
import moe.ouom.neriplayer.data.ltw.testing.testRoom
import moe.ouom.neriplayer.data.ltw.testing.testSong
import moe.ouom.neriplayer.data.ltw.testing.testTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherAuthoritativeStreamSyncTest {
    @Test
    fun `explicit sharing controls reload while absent settings preserve protocol defaults`() {
        val remote = testTrack().copy(streamUrl = "https://m701.music.126.net/new")
        val state = testRoom(listOf(remote), shareLinks = true)
        var knownRoom = state
        var hasRoom = true
        val player = FakeListenTogetherPlaybackHost().apply {
            requiresAuthoritativeStream = true
            currentMediaUrlFlow.value = "https://m701.music.126.net/old"
        }
        val sync = ListenTogetherAuthoritativeStreamSync(player, TestSongMapper, { false }, { knownRoom.takeIf { hasRoom } })
        knownRoom = state.copy(settings = state.settings.copy(shareAudioLinks = false))
        assertFalse(sync.inspect(state, testSong(), testSong(), "LINK_READY").reloadForStream)
        hasRoom = false
        assertTrue(sync.inspect(state, testSong(), testSong(), "LINK_READY").reloadForStream)
        hasRoom = true
        knownRoom = state
        assertTrue(sync.inspect(state, testSong(), testSong(), "LINK_READY").reloadForStream)
    }

    @Test
    fun `first or different track selection does not claim an existing stream reload`() {
        val state = testRoom(listOf(testTrack().copy(streamUrl = "https://m701.music.126.net/new")), shareLinks = true)
        val player = FakeListenTogetherPlaybackHost().apply { requiresAuthoritativeStream = true }
        val sync = ListenTogetherAuthoritativeStreamSync(player, TestSongMapper, { false }, { state })
        for (previous in listOf(null, testSong("2"))) {
            assertFalse(sync.inspect(state, testSong(), previous, "LINK_READY").requiresReload)
        }
    }

    @Test
    fun `stall repair requires the same currently playing track`() {
        val player = FakeListenTogetherPlaybackHost()
        var state = testRoom()
        val sync = ListenTogetherAuthoritativeStreamSync(player, TestSongMapper, { false }, { state })
        assertFalse(sync.inspect(state, testSong(), testSong(), "WATCHDOG_STALL").reloadForStall)
        state = testRoom(playing = true)
        for (previous in listOf(null, testSong("2"))) {
            assertFalse(sync.inspect(state, testSong(), previous, "WATCHDOG_STALL").reloadForStall)
        }
        assertTrue(sync.inspect(state, testSong(), testSong(), "WATCHDOG_STALL").reloadForStall)
    }
}
