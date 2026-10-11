package moe.ouom.neriplayer.data.ltw.playback

import androidx.media3.common.Player
import moe.ouom.neriplayer.data.ltw.testing.*
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.Assert.*
import org.junit.Test

class ListenTogetherPlayerStateApplierTest {
    @Test
    fun `stale states and safety pause do not mutate playback`() {
        val f = Fixture(testRoom(version = 6))
        assertFalse(f.applier.apply(testRoom(version = 5), "PLAY", 0L))
        f.player.safetyPause = true
        assertFalse(f.applier.apply(f.room, "PLAY", 0L))
        assertTrue(f.player.calls.isEmpty())
    }

    @Test
    fun `only queue update can clear the player from an empty room`() {
        val f = Fixture(testRoom(emptyList()))
        assertFalse(f.applier.apply(f.room, "HEARTBEAT", null))
        assertTrue(f.applier.apply(f.room, "SET_QUEUE", null))
        assertEquals(listOf("queue:-1"), f.player.calls)
    }

    @Test
    fun `paused remote track load seeks then pauses with remote command source`() {
        val f = Fixture(testRoom(listOf(testTrack("2")), position = 12_000L))
        f.player.isPlayingFlow.value = true
        assertTrue(f.applier.apply(f.room, "SET_TRACK", 12_000L))
        assertEquals("playlist:0", f.player.calls.first { it.startsWith("playlist") })
        assertTrue(f.player.calls.indexOf("seek:12000") < f.player.calls.indexOf("pause:listen_together_remote_pause"))
        assertTrue(f.player.commandSources.all { it == PlaybackCommandSource.REMOTE_SYNC })
        assertEquals("2", f.player.currentSongFlow.value?.audioId)
    }

    @Test
    fun `queue reorder preserves current stream without reloading playlist`() {
        val f = Fixture(testRoom(listOf(testTrack("1"), testTrack("2"))))
        f.player.currentQueueFlow.value = listOf(testSong("2"), testSong("1"))
        f.player.currentSongFlow.value = testSong("1")
        assertTrue(f.applier.apply(f.room, "SET_QUEUE", 0L))
        assertTrue(f.player.calls.contains("queue:0"))
        assertFalse(f.player.calls.any { it.startsWith("playlist") })
        assertEquals(listOf("1", "2"), f.player.currentQueueFlow.value.map { it.audioId })
    }

    @Test
    fun `playing drift uses hard seek for large difference and rate correction for small difference`() {
        val f = Fixture(testRoom(playing = true))
        assertTrue(f.applier.apply(f.room, "PLAY", 10_000L))
        assertTrue(f.player.calls.contains("seek:10000"))
        assertTrue(f.player.calls.contains("play"))
        f.player.calls.clear()
        f.player.isPlayingFlow.value = true
        assertTrue(f.applier.apply(f.room, "HEARTBEAT", 11_100L))
        assertFalse(f.player.calls.any { it.startsWith("seek") })
        assertTrue(f.player.syncPlaybackRate > 1f)
        f.applier.apply(f.room, "HEARTBEAT", 10_000L)
        assertEquals(1f, f.player.syncPlaybackRate)
    }

    @Test
    fun `soft sync waits for a ready playing player without a pending media load`() {
        val f = Fixture(testRoom(playing = true))
        f.player.isPlayingFlow.value = true
        f.player.playbackPositionFlow.value = 10_000L
        f.player.playerPlaybackStateFlow.value = Player.STATE_BUFFERING
        f.applier.apply(f.room, "HEARTBEAT", 11_100L)
        f.player.playerPlaybackStateFlow.value = Player.STATE_READY
        f.player.pendingMediaLoad = true
        f.applier.apply(f.room, "HEARTBEAT", 11_100L)
        assertEquals(1f, f.player.syncPlaybackRate)
        assertFalse(f.player.calls.any { it.startsWith("rate:1.") })
        f.player.pendingMediaLoad = false
        f.applier.apply(f.room, "HEARTBEAT", 11_100L)
        assertEquals(1.03f, f.player.syncPlaybackRate)
    }

    @Test
    fun `usb exclusive output keeps the normal rate and corrects drift by seeking only`() {
        val f = Fixture(testRoom(playing = true))
        f.player.isPlayingFlow.value = true
        f.player.usbExclusiveOutput = true
        f.player.playbackPositionFlow.value = 10_000L
        f.applier.apply(f.room, "HEARTBEAT", 11_100L)
        assertEquals(1f, f.player.syncPlaybackRate)
        assertFalse(f.player.calls.any { it.startsWith("rate:1.") || it.startsWith("seek") })
        f.applier.apply(f.room, "PLAY", 13_000L)
        assertTrue(f.player.calls.contains("seek:13000"))
        assertEquals(1f, f.player.syncPlaybackRate)
    }

    @Test
    fun `authoritative stream reload deduplicates while resolution is pending and clears after resolution`() {
        val remote = testTrack().copy(streamUrl = "https://m701.music.126.net/a", streamUrls = listOf("https://m701.music.126.net/a"))
        val f = Fixture(testRoom(listOf(remote), shareLinks = true))
        f.player.requiresAuthoritativeStream = true
        f.player.currentMediaUrlFlow.value = "https://m701.music.126.net/old"
        f.applier.apply(f.room, "LINK_READY", 0L)
        assertEquals(1, f.player.calls.count { it.startsWith("playlist") })
        f.player.resolutionPending = true
        f.applier.apply(f.room, "LINK_READY", 0L)
        assertEquals(1, f.player.calls.count { it.startsWith("playlist") })
        f.player.resolutionPending = false
        f.player.currentMediaUrlFlow.value = "https://m701.music.126.net/a"
        f.applier.apply(f.room, "HEARTBEAT", 0L)
        assertEquals(1, f.player.calls.count { it.startsWith("playlist") })
        f.controller = true
        f.applier.apply(f.room, "LINK_READY", 0L)
        assertEquals(1, f.player.calls.count { it.startsWith("playlist") })
    }

    @Test
    fun `unavailable shared stream reloads once per track and permits future local resolution`() {
        val f = Fixture(testRoom())
        f.player.requiresAuthoritativeStream = true
        f.player.streamUnavailable = true
        f.applier.apply(f.room, "LINK_UNAVAILABLE", 0L)
        f.applier.apply(f.room, "LINK_UNAVAILABLE", 0L)
        assertEquals(1, f.player.calls.count { it.startsWith("playlist") })
        f.player.requiresAuthoritativeStream = false
        f.applier.apply(f.room, "HEARTBEAT", 0L)
        f.player.requiresAuthoritativeStream = true
        f.applier.apply(f.room, "LINK_UNAVAILABLE", 0L)
        assertEquals(2, f.player.calls.count { it.startsWith("playlist") })
    }

    @Test
    fun `waiting for controller stream holds playback while watchdog stall forces repair`() {
        val f = Fixture(testRoom(playing = true))
        f.player.waitForStream = true
        f.applier.apply(f.room, "HEARTBEAT", 0L)
        assertFalse(f.player.calls.contains("play"))
        f.player.waitForStream = false
        f.applier.apply(f.room, "WATCHDOG_STALL", 0L)
        assertTrue(f.player.calls.any { it.startsWith("playlist") })
    }

    @Test
    fun `track fallback and duration bounds survive repeated sync after grace period`() {
        val f = Fixture(testRoom(emptyList()).copy(track = testTrack("2")))
        f.applier.apply(f.room, "SET_TRACK", 999_999L)
        assertEquals(180_000L, f.player.playbackPositionFlow.value)
        f.now += 100L
        f.applier.apply(f.room, "HEARTBEAT", null)
        f.now += 1_000L
        f.applier.apply(f.room, "SEEK", 1_234L)
        assertEquals(1_234L, f.player.playbackPositionFlow.value)
    }

    private class Fixture(var room: ListenTogetherRoomState) {
        val player = FakeListenTogetherPlaybackHost().apply {
            currentQueueFlow.value = listOf(testSong())
            currentSongFlow.value = testSong()
            playerPlaybackStateFlow.value = Player.STATE_READY
        }
        var controller = false
        var now = 1_000L
        val applier = ListenTogetherPlayerStateApplier(player, TestSongMapper, testApplierConfig, { room }, { controller }, { 0L }, { now })
    }
}
