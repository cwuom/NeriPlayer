package moe.ouom.neriplayer.core.player

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.testing.FakeListenTogetherAccess
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerManagerListenTogetherGateTest {
    private val listenTogether = FakeListenTogetherAccess()
    private val previousSong = PlayerManager._currentSongFlow.value
    private val previousPlayJob = PlayerManager.playJob
    private val previousPendingMediaLoad = PlayerManager.pendingMediaLoadActive
    private val activeJob = Job()

    @Before
    fun setUp() {
        PlayerTestEnvironment.install(listenTogether)
        PlayerManager.clearListenTogetherSafetyPause()
    }

    @After
    fun tearDown() {
        activeJob.cancel()
        PlayerManager.clearListenTogetherSafetyPause()
        PlayerManager._currentSongFlow.value = previousSong
        PlayerManager.playJob = previousPlayJob
        PlayerManager.pendingMediaLoadActive = previousPendingMediaLoad
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `listen together is active only while the session holds a room id`() {
        assertFalse(PlayerManager.isListenTogetherActive())

        listenTogether.sessionState.value = ListenTogetherSessionState(roomId = "   ")
        assertFalse(PlayerManager.isListenTogetherActive())

        listenTogether.sessionState.value = ListenTogetherSessionState(roomId = ROOM_ID)
        assertTrue(PlayerManager.isListenTogetherActive())
    }

    @Test
    fun `safety pause resume asks the room once per attempt for listeners`() {
        assertFalse(PlayerManager.requestListenTogetherSafetyPauseResume())

        joinAsListener()
        PlayerManager.markListenTogetherSafetyPausePendingResume()
        assertTrue(PlayerManager.requestListenTogetherSafetyPauseResume())
        assertTrue(PlayerManager.requestListenTogetherSafetyPauseResume())
        assertEquals(1, listenTogether.resumeRequests)

        PlayerManager.retryListenTogetherSafetyPauseResume()
        assertTrue(PlayerManager.requestListenTogetherSafetyPauseResume())
        assertEquals(2, listenTogether.resumeRequests)

        PlayerManager.completeListenTogetherSafetyPauseResume()
        assertFalse(PlayerManager.requestListenTogetherSafetyPauseResume())
        assertEquals(2, listenTogether.resumeRequests)
    }

    @Test
    fun `safety pause is dropped for controllers and after leaving the room`() {
        joinAsController()
        PlayerManager.markListenTogetherSafetyPausePendingResume()
        assertFalse(PlayerManager.requestListenTogetherSafetyPauseResume())
        joinAsListener()
        assertFalse(PlayerManager.requestListenTogetherSafetyPauseResume())

        PlayerManager.markListenTogetherSafetyPausePendingResume()
        listenTogether.sessionState.value = ListenTogetherSessionState()
        assertFalse(PlayerManager.requestListenTogetherSafetyPauseResume())
        joinAsListener()
        assertFalse(PlayerManager.requestListenTogetherSafetyPauseResume())

        assertEquals(0, listenTogether.resumeRequests)
    }

    @Test
    fun `stable keys come from the resolved channel and audio identity`() {
        with(PlayerManager) {
            assertEquals("netease:42", neteaseSong(42).listenTogetherStableKeyOrNull())
            assertNull(unresolvedYouTubeSong().listenTogetherStableKeyOrNull())
        }
    }

    @Test
    fun `audio link fallback needs an active listener room that shares links`() {
        assertFalse(PlayerManager.isListenTogetherAudioLinkFallbackEnabled())

        joinAsController()
        assertFalse(PlayerManager.isListenTogetherAudioLinkFallbackEnabled())

        joinAsListener(room = null)
        assertFalse(PlayerManager.isListenTogetherAudioLinkFallbackEnabled())

        joinAsListener(room(shareAudioLinks = false))
        assertFalse(PlayerManager.isListenTogetherAudioLinkFallbackEnabled())

        joinAsListener(room(roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE))
        assertFalse(PlayerManager.isListenTogetherAudioLinkFallbackEnabled())

        joinAsListener()
        assertTrue(PlayerManager.isListenTogetherAudioLinkFallbackEnabled())
    }

    @Test
    fun `authoritative stream target is the current room track for listeners`() {
        val song = neteaseSong(42)
        assertFalse(PlayerManager.isListenTogetherAuthoritativeStreamTarget(song))

        joinAsListener(room(track = null))
        assertFalse(PlayerManager.isListenTogetherAuthoritativeStreamTarget(song))

        joinAsListener()
        assertFalse(PlayerManager.isListenTogetherAuthoritativeStreamTarget(neteaseSong(43)))
        assertFalse(PlayerManager.isListenTogetherAuthoritativeStreamTarget(unresolvedYouTubeSong()))
        assertTrue(PlayerManager.isListenTogetherAuthoritativeStreamTarget(song))
    }

    @Test
    fun `listeners wait for the controller stream until it is shared or confirmed unavailable`() {
        val song = neteaseSong(42)
        joinAsListener()
        assertTrue(PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(song))
        assertFalse(PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(neteaseSong(43)))
        assertFalse(PlayerManager.isListenTogetherAuthoritativeStreamConfirmedUnavailable(neteaseSong(43)))

        joinAsListener(room(track = neteaseTrack(42, streamUrl = TRUSTED_STREAM_URL)))
        assertFalse(PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(song))

        joinAsListener()
        assertFalse(PlayerManager.isListenTogetherAuthoritativeStreamConfirmedUnavailable(song))
        listenTogether.unavailableAudioLinks += ROOM_ID to "netease:42"
        assertTrue(PlayerManager.isListenTogetherAuthoritativeStreamConfirmedUnavailable(song))
        assertFalse(PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(song))
    }

    @Test
    fun `local resolution is pending only while the current song is still loading`() {
        val song = neteaseSong(42)
        PlayerManager._currentSongFlow.value = song
        PlayerManager.pendingMediaLoadActive = false
        PlayerManager.playJob = activeJob
        assertFalse(PlayerManager.isListenTogetherLocalResolutionPendingFor(song))

        PlayerManager.pendingMediaLoadActive = true
        PlayerManager.playJob = null
        assertFalse(PlayerManager.isListenTogetherLocalResolutionPendingFor(song))
        PlayerManager.playJob = Job().apply { complete() }
        assertFalse(PlayerManager.isListenTogetherLocalResolutionPendingFor(song))

        PlayerManager.playJob = activeJob
        assertTrue(PlayerManager.isListenTogetherLocalResolutionPendingFor(song))
        assertFalse(PlayerManager.isListenTogetherLocalResolutionPendingFor(neteaseSong(43)))
        assertFalse(PlayerManager.isListenTogetherLocalResolutionPendingFor(unresolvedYouTubeSong()))

        PlayerManager._currentSongFlow.value = null
        assertFalse(PlayerManager.isListenTogetherLocalResolutionPendingFor(song))
    }

    @Test
    fun `direct stream urls are trimmed http or https addresses`() {
        assertTrue(PlayerManager.isDirectStreamUrl("  HTTPS://cdn.example/a.flac "))
        assertTrue(PlayerManager.isDirectStreamUrl("http://cdn.example/a.mp3"))
        assertFalse(PlayerManager.isDirectStreamUrl("content://media/external/audio/1"))
        assertFalse(PlayerManager.isDirectStreamUrl(""))
        assertFalse(PlayerManager.isDirectStreamUrl(null))
    }

    private fun joinAsListener(room: ListenTogetherRoomState? = room()) {
        listenTogether.sessionState.value = ListenTogetherSessionState(
            roomId = ROOM_ID,
            userUuid = LISTENER_UUID,
            role = "listener"
        )
        listenTogether.roomState.value = room
    }

    private fun joinAsController() {
        listenTogether.sessionState.value = ListenTogetherSessionState(
            roomId = ROOM_ID,
            userUuid = CONTROLLER_UUID,
            role = "controller"
        )
        listenTogether.roomState.value = room()
    }

    private fun room(
        track: ListenTogetherTrack? = neteaseTrack(42),
        shareAudioLinks: Boolean = true,
        roomStatus: String = ListenTogetherRoomStatuses.ACTIVE
    ) = ListenTogetherRoomState(
        roomId = ROOM_ID,
        version = 1L,
        controllerUserUuid = CONTROLLER_UUID,
        settings = ListenTogetherRoomSettings(shareAudioLinks = shareAudioLinks),
        track = track,
        roomStatus = roomStatus
    )

    private fun neteaseTrack(id: Long, streamUrl: String? = null) = ListenTogetherTrack(
        stableKey = "netease:$id",
        channelId = ListenTogetherChannels.NETEASE,
        audioId = id.toString(),
        streamUrl = streamUrl,
        name = "Song $id",
        artist = "Artist"
    )

    private fun neteaseSong(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = ListenTogetherChannels.NETEASE,
        audioId = id.toString()
    )

    private fun unresolvedYouTubeSong() = SongItem(
        id = 7L,
        name = "Video",
        artist = "Artist",
        album = "YouTube Music",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = ListenTogetherChannels.YOUTUBE_MUSIC
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val CONTROLLER_UUID = "controller-uuid"
        const val LISTENER_UUID = "listener-uuid"
        const val TRUSTED_STREAM_URL = "https://m801.music.126.net/20260101/song-42.flac"
    }
}
