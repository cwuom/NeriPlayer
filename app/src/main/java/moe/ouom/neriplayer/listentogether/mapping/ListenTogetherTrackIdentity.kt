package moe.ouom.neriplayer.listentogether.mapping

import android.net.Uri
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

fun buildStableTrackKey(
    channelId: String,
    audioId: String,
    subAudioId: String? = null,
    playlistContextId: String? = null
): String {
    return when (channelId) {
        ListenTogetherChannels.BILIBILI -> {
            listOf(channelId, audioId, subAudioId).filterNot { it.isNullOrBlank() }.joinToString(":")
        }

        ListenTogetherChannels.YOUTUBE_MUSIC -> {
            listOf(channelId, audioId, playlistContextId).filterNot { it.isNullOrBlank() }.joinToString(":")
        }

        else -> "$channelId:$audioId"
    }
}

fun SongItem.resolvedChannelId(): String? {
    channelId?.takeIf { it.isNotBlank() }?.let { return it }
    return when {
        LocalSongSupport.isLocalSong(this, null) -> ListenTogetherChannels.LOCAL
        !extractYouTubeMusicVideoId(mediaUri).isNullOrBlank() -> ListenTogetherChannels.YOUTUBE_MUSIC
        album.startsWith(PlayerManager.BILI_SOURCE_TAG) -> ListenTogetherChannels.BILIBILI
        else -> ListenTogetherChannels.NETEASE
    }
}

fun SongItem.resolvedAudioId(): String? {
    audioId?.takeIf { it.isNotBlank() }?.let { return it }
    return when (resolvedChannelId()) {
        ListenTogetherChannels.YOUTUBE_MUSIC -> extractYouTubeMusicVideoId(mediaUri)
        else -> id.toString()
    }
}

fun SongItem.resolvedSubAudioId(): String? {
    subAudioId?.takeIf { it.isNotBlank() }?.let { return it }
    if (resolvedChannelId() != ListenTogetherChannels.BILIBILI) {
        return null
    }
    return album.substringAfter('|', "").takeIf { it.isNotBlank() }
}

fun SongItem.resolvedPlaylistContextId(): String? {
    playlistContextId?.takeIf { it.isNotBlank() }?.let { return it }
    if (resolvedChannelId() != ListenTogetherChannels.YOUTUBE_MUSIC) {
        return null
    }
    return mediaUri
        ?.let(Uri::parse)
        ?.getQueryParameter("playlistId")
        ?.takeIf { it.isNotBlank() }
}
