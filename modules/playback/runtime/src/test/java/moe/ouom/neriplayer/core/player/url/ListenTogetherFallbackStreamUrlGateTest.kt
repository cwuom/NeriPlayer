package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.FakeListenTogetherAccess
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackUrlCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ListenTogetherFallbackStreamUrlGateTest {

    private val listenTogether = FakeListenTogetherAccess()
    private val previousSong = PlayerManager._currentSongFlow.value
    private val previousMediaUrl = PlayerManager._currentMediaUrl.value
    private val previousCandidates = PlayerManager.activePlaybackCandidates
    private val previousCandidateIndex = PlayerManager.activePlaybackUrlIndex

    @Before
    fun setUp() {
        PlayerTestEnvironment.install(listenTogether)
    }

    @After
    fun tearDown() {
        PlayerManager._currentSongFlow.value = previousSong
        PlayerManager._currentMediaUrl.value = previousMediaUrl
        PlayerManager.activePlaybackCandidates = previousCandidates
        PlayerManager.activePlaybackUrlIndex = previousCandidateIndex
        PlayerTestEnvironment.reset()
    }

    @Test
    fun `fallback links are offered only to listeners of an active room that shares them`() {
        val song = neteaseSong(42)
        val sharedTrack = neteaseTrack(42, streamUrls = listOf(TRUSTED_URL))

        assertEquals(emptyList<String>(), PlayerManager.listenTogetherFallbackStreamUrls(song))

        joinAs(CONTROLLER_UUID, "controller", room(sharedTrack))
        assertEquals(emptyList<String>(), PlayerManager.listenTogetherFallbackStreamUrls(song))

        val listenerRooms = listOf(
            null,
            room(sharedTrack, shareAudioLinks = false),
            room(sharedTrack, roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE),
            room(track = null)
        )
        listenerRooms.forEach { room ->
            joinAs(LISTENER_UUID, "listener", room)
            assertEquals(emptyList<String>(), PlayerManager.listenTogetherFallbackStreamUrls(song))
        }
    }

    @Test
    fun `fallback links belong to the room track and keep only trusted hosts`() {
        val track = neteaseTrack(
            42,
            streamUrls = listOf("https://evil.example/a.flac", TRUSTED_URL),
            streamUrl = TRUSTED_LEGACY_URL
        )
        joinAs(LISTENER_UUID, "listener", room(track))

        assertEquals(emptyList<String>(), PlayerManager.listenTogetherFallbackStreamUrls(neteaseSong(43)))
        assertEquals(
            listOf(TRUSTED_URL, TRUSTED_LEGACY_URL),
            PlayerManager.listenTogetherFallbackStreamUrls(neteaseSong(42))
        )
    }

    @Test
    fun `listen together session streams are never republished as shareable links`() {
        PlayerManager._currentSongFlow.value = null
        PlayerManager._currentMediaUrl.value = SESSION_URL
        PlayerManager.activePlaybackCandidates = listOf(
            PlaybackUrlCandidate(url = SESSION_URL, cacheKeyOverride = "$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX:42"),
            PlaybackUrlCandidate(url = OWN_URL, cacheKeyOverride = "netease:42"),
            PlaybackUrlCandidate(url = PLAIN_URL)
        )
        PlayerManager.activePlaybackUrlIndex = 0

        assertEquals(listOf(OWN_URL, PLAIN_URL), PlayerManager.currentListenTogetherShareableStreamUrls())
    }

    private fun joinAs(userUuid: String, role: String, room: ListenTogetherRoomState?) {
        listenTogether.sessionState.value = ListenTogetherSessionState(
            roomId = ROOM_ID,
            userUuid = userUuid,
            role = role
        )
        listenTogether.roomState.value = room
    }

    private fun room(
        track: ListenTogetherTrack?,
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

    private fun neteaseTrack(id: Long, streamUrls: List<String>, streamUrl: String? = null) = ListenTogetherTrack(
        stableKey = "netease:$id",
        channelId = ListenTogetherChannels.NETEASE,
        audioId = id.toString(),
        streamUrl = streamUrl,
        streamUrls = streamUrls,
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

    private companion object {
        const val ROOM_ID = "room-1"
        const val CONTROLLER_UUID = "controller-uuid"
        const val LISTENER_UUID = "listener-uuid"
        const val TRUSTED_URL = "https://m801.music.126.net/20260101/song-42.flac"
        const val TRUSTED_LEGACY_URL = "https://m701.music.126.net/20260101/song-42.mp3"
        const val SESSION_URL = "https://m801.music.126.net/20260101/session.flac"
        const val OWN_URL = "https://m801.music.126.net/20260101/own.flac"
        const val PLAIN_URL = "https://cdn.example/plain.mp3"
    }
}
