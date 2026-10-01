package moe.ouom.neriplayer.data.ltw.testing

import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlayerStateApplierConfig
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

internal fun testSong(id: String = "1", channel: String = "netease") = SongItem(
    id = id.toLongOrNull() ?: id.hashCode().toLong(), name = "Song $id", artist = "Artist", album = "Album", albumId = 1L,
    durationMs = 180_000L, coverUrl = null, channelId = channel, audioId = id
)

internal fun testTrack(id: String = "1", channel: String = "netease") = ListenTogetherTrack(
    stableKey = "$channel:$id", channelId = channel, audioId = id, name = "Song $id", artist = "Artist", durationMs = 180_000L
)

internal fun testRoom(
    tracks: List<ListenTogetherTrack> = listOf(testTrack()),
    index: Int = 0,
    playing: Boolean = false,
    position: Long = 0L,
    version: Long = 5L,
    shareLinks: Boolean = false
) = ListenTogetherRoomState(
    roomId = "ABC234", version = version, controllerUserUuid = "controller", queue = tracks, currentIndex = index,
    track = tracks.getOrNull(index), settings = ListenTogetherRoomSettings(shareAudioLinks = shareLinks),
    playback = ListenTogetherPlaybackState(state = if (playing) "playing" else "paused", basePositionMs = position)
)

internal val testApplierConfig = ListenTogetherPlayerStateApplierConfig(
    "test", 500L, 5_000L, 2_500L, 800L, 600L, 1_500L, 800L, 2_000L
)
