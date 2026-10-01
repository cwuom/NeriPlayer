package moe.ouom.neriplayer.core.player.integration.ltw

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.ltw.mapping.DefaultListenTogetherSongMapper
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

private val songMapper = DefaultListenTogetherSongMapper(
    isLocalSong = { LocalSongSupport.isLocalSong(it, context = null) },
    localAlbumIdentity = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
)

internal fun SongItem.resolvedChannelId(): String? = with(songMapper) { resolvedChannelId() }
internal fun SongItem.resolvedAudioId(): String? = with(songMapper) { resolvedAudioId() }
internal fun SongItem.resolvedSubAudioId(): String? = with(songMapper) { resolvedSubAudioId() }
internal fun SongItem.resolvedPlaylistContextId(): String? = with(songMapper) { resolvedPlaylistContextId() }
internal fun ListenTogetherTrack.toSongItem(): SongItem = with(songMapper) { toSongItem() }
internal fun SongItem.toListenTogetherTrackOrNull(includeLocal: Boolean = false): ListenTogetherTrack? =
    with(songMapper) { toListenTogetherTrackOrNull(includeLocal) }

internal fun SongItem.sameTrackAs(other: SongItem): Boolean = with(songMapper) { sameTrackAs(other) }
