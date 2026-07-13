package moe.ouom.neriplayer.listentogether.mapping

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.SongItem

fun ListenTogetherTrack.toSongItem(): SongItem {
    val trustedStreamUrl = trustedListenTogetherStreamUrl(channelId, streamUrl)
    return when (channelId) {
        ListenTogetherChannels.YOUTUBE_MUSIC -> {
            val playlistId = playlistContextId?.takeIf { it.isNotBlank() }
            SongItem(
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
                playlistContextId = playlistContextId,
                streamUrl = trustedStreamUrl
            )
        }

        ListenTogetherChannels.BILIBILI -> {
            val songId = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()
            val albumTag = subAudioId?.takeIf { it.isNotBlank() }
                ?.let { "${PlayerManager.BILI_SOURCE_TAG}|$it" }
                ?: PlayerManager.BILI_SOURCE_TAG
            SongItem(
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
                playlistContextId = playlistContextId,
                streamUrl = trustedStreamUrl
            )
        }

        ListenTogetherChannels.LOCAL -> {
            val songId = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()
            SongItem(
                id = songId,
                name = name,
                artist = artist,
                album = album ?: LocalSongSupport.LOCAL_ALBUM_IDENTITY,
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
                playlistContextId = playlistContextId,
                streamUrl = trustedStreamUrl
            )
        }

        else -> {
            val songId = audioId.toLongOrNull() ?: stableKey.hashCode().toLong()
            SongItem(
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
                playlistContextId = playlistContextId,
                streamUrl = trustedStreamUrl
            )
        }
    }
}

fun ListenTogetherTrack.withStreamUrl(streamUrl: String?): ListenTogetherTrack {
    val normalizedStreamUrl = trustedListenTogetherStreamUrl(channelId, streamUrl)
    if (normalizedStreamUrl == this.streamUrl) return this
    return copy(streamUrl = normalizedStreamUrl)
}
