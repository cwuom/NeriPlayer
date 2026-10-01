package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

internal fun ListenTogetherTrack.toYouTubeSong(): SongItem {
    val playlistId = playlistContextId?.takeIf { it.isNotBlank() }
    return SongItem(
        id = stableYouTubeMusicId(audioId),
        name = name,
        artist = artist,
        album = album.orEmpty(),
        albumId = stableYouTubeMusicId(playlistId ?: audioId),
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri ?: buildYouTubeMusicMediaUri(audioId, playlistId),
        originalName = name,
        originalArtist = artist,
        originalCoverUrl = coverUrl,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId
    )
}

internal fun ListenTogetherTrack.toBilibiliSong(): SongItem {
    val songId = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()
    val albumTag = subAudioId?.takeIf { it.isNotBlank() }
        ?.let { "${SongSourceTags.BILIBILI}|$it" }
        ?: SongSourceTags.BILIBILI
    return SongItem(
        id = songId,
        name = name,
        artist = artist,
        album = albumTag,
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = coverUrl,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId
    )
}

internal fun ListenTogetherTrack.toLocalSong(localAlbumIdentity: String): SongItem {
    val songId = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()
    return SongItem(
        id = songId,
        name = name,
        artist = artist,
        album = album ?: localAlbumIdentity,
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        originalName = name,
        originalArtist = artist,
        originalCoverUrl = coverUrl,
        localFilePath = mediaUri,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId
    )
}

internal fun ListenTogetherTrack.toNeteaseSong(): SongItem {
    val songId = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()
    return SongItem(
        id = songId,
        name = name,
        artist = artist,
        album = album.orEmpty(),
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = coverUrl,
        channelId = ListenTogetherChannels.NETEASE,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId
    )
}
