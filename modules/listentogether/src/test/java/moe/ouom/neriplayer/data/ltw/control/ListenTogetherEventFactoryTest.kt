package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.ltw.testing.*
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import org.junit.Assert.*
import org.junit.Test

class ListenTogetherEventFactoryTest {
    @Test
    fun `playback commands publish controller commits or listener requests with captured intent`() {
        val f = Fixture()
        val types = mapOf("PLAY_PLAYLIST" to "SET_TRACK", "PLAY_FROM_QUEUE" to "SET_TRACK", "NEXT" to "SET_TRACK", "PREVIOUS" to "SET_TRACK", "PLAY" to "PLAY", "PAUSE" to "PAUSE", "SEEK" to "SEEK", "PLAYBACK_MODE" to "PLAYBACK_MODE", "SET_QUEUE" to "SET_QUEUE")
        for (controller in listOf(true, false)) {
            f.controller = controller
            for ((command, commit) in types) {
                val event = requireNotNull(f.factory.buildEventForPlaybackCommand(PlaybackCommand(command, PlaybackCommandSource.LOCAL, queue = listOf(testSong("2")), positionMs = 123L, currentIndex = 0, shouldPlay = false)))
                assertEquals(if (controller) commit else "REQUEST_$commit", event.type)
                assertEquals("client", event.clientInstanceId)
                assertTrue(requireNotNull(event.clientSequence) > 0L)
            }
        }
        assertNull(f.factory.buildEventForPlaybackCommand(PlaybackCommand("UNKNOWN", PlaybackCommandSource.LOCAL)))
    }

    @Test
    fun `local tracks and invalid seek indices cannot manufacture remote controls`() {
        val f = Fixture()
        f.player.currentSongFlow.value = testSong(channel = "local")
        for (type in listOf("PLAY", "PAUSE", "SEEK")) assertNull(f.factory.buildEventForPlaybackCommand(PlaybackCommand(type, PlaybackCommandSource.LOCAL)))
        assertNull(f.factory.buildEventForPlaybackCommand(PlaybackCommand("NEXT", PlaybackCommandSource.LOCAL, queue = listOf(testSong(channel = "local")), currentIndex = 0)))
        f.player.currentSongFlow.value = testSong()
        assertNull(f.factory.buildEventForPlaybackCommand(PlaybackCommand("SEEK", PlaybackCommandSource.LOCAL, currentIndex = 99)))
    }

    @Test
    fun `queue clear and unshareable queue retain distinct outcomes`() {
        val f = Fixture()
        val clear = requireNotNull(f.factory.buildEventForPlaybackCommand(PlaybackCommand("SET_QUEUE", PlaybackCommandSource.LOCAL, queue = emptyList())))
        assertEquals(-1, clear.currentIndex)
        assertEquals(emptyList<Any>(), clear.queue)
        assertEquals(false, clear.shouldPlay)
        assertEquals(0L, clear.positionMs)
        assertNull(f.factory.buildSetQueueEvent(listOf(testSong(channel = "local")), 0, 0L))
        f.player.transportActive = true
        assertEquals(true, f.factory.buildSetQueueEvent(listOf(testSong()), 0, -1L)?.shouldPlay)
        f.player.transportActive = false
        f.player.isPlayingFlow.value = true
        assertEquals(true, f.factory.buildSetQueueEvent(listOf(testSong()), 0, 1L)?.shouldPlay)
        assertEquals(false, f.factory.buildSetQueueEvent(listOf(testSong()), 0, 1L, false)?.shouldPlay)
    }

    @Test
    fun `schema two sends mutation or omits redundant snapshot while schema one retains full queue`() {
        val f = Fixture()
        assertNotNull(f.factory.buildPlayEvent(0L).queue)
        f.room = f.room?.copy(schemaVersion = 2)
        val track = f.factory.buildSetTrackEvent(listOf(testSong("2")), 0, 0L, true)
        assertNull(track.queue)
        assertNotNull(track.queueMutation)
        assertNotNull(track.legacyQueueSnapshot)
        assertNull(f.factory.buildPlayEvent(0L).queue)
        assertNotNull(f.factory.buildPlaybackModeEvent(1, true).queueMutation)
        f.room = null
        assertNotNull(f.factory.buildRequestSetTrackEvent(listOf(testSong()), 0, 0L, false).queue)
    }

    @Test
    fun `finished track commits next selection only for controller and rejects unshareable intent`() {
        val f = Fixture()
        val command = PlaybackCommand("TRACK_FINISHED", PlaybackCommandSource.LOCAL, currentIndex = 1, shouldPlay = true)
        val songs = listOf(testSong(), testSong("2"))
        val controller = requireNotNull(f.factory.buildTrackFinishedEvent(command, songs, songs[0], 180_000L))
        assertEquals("2", controller.track?.audioId)
        assertEquals("netease:1", controller.finishedTrackStableKey)
        f.controller = false
        val listener = requireNotNull(f.factory.buildTrackFinishedEvent(command, songs, songs[0], 180_000L))
        assertNull(listener.queue)
        assertNull(listener.currentIndex)
        assertNull(listener.shouldPlay)
        assertNull(f.factory.buildTrackFinishedEvent(command, emptyList(), songs[0], 0L))
        assertNull(f.factory.buildTrackFinishedEvent(command, songs, null, 0L))
        assertNull(f.factory.buildTrackFinishedEvent(command, songs, testSong(channel = "local"), 0L))
        assertNull(f.factory.buildTrackFinishedEvent(command, listOf(songs[0], testSong(channel = "local")), songs[0], 0L))
        f.controller = true
        assertNull(f.factory.buildTrackFinishedEvent(command.copy(currentIndex = null, shouldPlay = false), songs, songs[0], 0L)?.track)
        assertNotNull(f.factory.buildEventForPlaybackCommand(command))
    }

    @Test
    fun `link publication uses verified current urls and rejects mismatched or missing current track`() {
        val f = Fixture()
        assertNull(f.factory.buildLinkReadyEvent("netease:1", 0L))
        f.player.streamUrls = listOf("https://m701.music.126.net/a")
        val ready = requireNotNull(f.factory.buildLinkReadyEvent("netease:1", -1L))
        assertEquals(listOf("https://m701.music.126.net/a"), ready.track?.streamUrls)
        assertEquals(0L, ready.positionMs)
        assertEquals(listOf("https://m701.music.126.net/b"), f.factory.buildLinkReadyEvent("netease:1", 0L, streamUrlsOverride = listOf("https://m701.music.126.net/b"))?.track?.streamUrls)
        assertEquals("https://m701.music.126.net/primary", f.factory.buildLinkReadyEvent("netease:1", 0L, "https://m701.music.126.net/primary")?.track?.streamUrl)
        assertNull(f.factory.buildLinkReadyEvent("netease:other", 0L))
        assertEquals(emptyList<String>(), f.factory.buildLinkUnavailableEvent("netease:1")?.track?.streamUrls)
        assertNull(f.factory.buildLinkUnavailableEvent("netease:other"))
        f.player.currentQueueFlow.value = emptyList()
        assertNull(f.factory.buildLinkReadyEvent("netease:1", 0L))
        f.player.currentSongFlow.value = testSong(channel = "local")
        assertNull(f.factory.buildLinkReadyEvent("local:1", 0L))
        assertNull(f.factory.buildLinkUnavailableEvent("local:1"))
        f.player.currentSongFlow.value = null
        assertNull(f.factory.buildLinkReadyEvent("netease:1", 0L))
        assertNull(f.factory.buildLinkUnavailableEvent("netease:1"))
    }

    @Test
    fun `forwarded requests preserve command fields and wrap playback-mode position using previous repeat mode`() {
        val f = Fixture()
        assertNull(f.factory.buildControllerCommitEventFromForwardedRequest(ListenTogetherSocketEnvelope(type = "member_control_requested")))
        assertNull(f.factory.buildControllerCommitEventFromForwardedRequest(ListenTogetherSocketEnvelope(type = "member_control_requested", causedBy = ListenTogetherCause(type = "PLAY"))))
        val message = ListenTogetherSocketEnvelope(type = "member_control_requested", causedBy = ListenTogetherCause(type = "REQUEST_SEEK"), expectedPositionMs = 123L, currentIndex = 0, requestTrackStableKey = "netease:1")
        assertEquals("SEEK", f.factory.buildControllerCommitEventFromForwardedRequest(message)?.type)
        assertEquals(123L, f.factory.buildControllerCommitEventFromForwardedRequest(message)?.positionMs)
        assertEquals(0L, f.factory.buildControllerCommitEventFromForwardedRequest(message.copy(positionMs = -1L))?.positionMs)
        f.room = f.room?.copy(playback = requireNotNull(f.room).playback.copy(repeatMode = 1))
        assertEquals(10L, f.factory.buildControllerCommitEventFromForwardedRequest(message.copy(causedBy = ListenTogetherCause(type = "REQUEST_PLAYBACK_MODE"), positionMs = 180_010L))?.positionMs)
        f.room = null
        f.player.currentSongFlow.value = null
        f.factory.buildPlaybackModeEvent(0, false)
    }

    @Test
    fun `heartbeat legacy queue policy and direct transport snapshots preserve wire types`() {
        val f = Fixture()
        assertNotNull(f.factory.buildHeartbeatEvent("paused", 0L).queue)
        assertNull(f.factory.buildHeartbeatEvent("paused", 0L, false).queue)
        f.room = f.room?.copy(schemaVersion = 2)
        assertNull(f.factory.buildHeartbeatEvent("playing", 0L).queue)
        assertEquals("REQUEST_PLAY", f.factory.buildRequestPlayEvent(0L).type)
        assertEquals("REQUEST_PAUSE", f.factory.buildRequestPauseEvent(0L).type)
        assertNull(f.factory.buildRequestLinkEvent("netease:1").forceRefresh)
        assertEquals(true, f.factory.buildRequestLinkEvent("netease:1", forceRefresh = true).forceRefresh)
        assertEquals(0L, resolveListenTogetherPlaybackCommandSnapshot(emptyList<String>(), -1L, listOf("current"), 99L).positionMs)
        assertEquals(listOf("current"), resolveListenTogetherPlaybackCommandSnapshot<String>(null, null, listOf("current"), 99L).queue)
        assertEquals(99L, resolveListenTogetherPlaybackCommandSnapshot<String>(null, null, listOf("current"), 99L).positionMs)
    }

    private class Fixture {
        val player = FakeListenTogetherPlaybackHost().apply {
            currentSongFlow.value = testSong()
            currentQueueFlow.value = listOf(testSong())
        }
        var room: ListenTogetherRoomState? = testRoom()
        var controller = true
        private var sequence = 0L
        val factory = ListenTogetherEventFactory(player, TestSongMapper, { room }, { controller }, { "event" }, { "client" }, { ++sequence }, { "paused" }, { player.transportActive })
    }
}
