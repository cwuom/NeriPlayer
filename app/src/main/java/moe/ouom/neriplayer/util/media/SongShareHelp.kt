package moe.ouom.neriplayer.util.media

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId
import java.net.URI
import java.util.Locale

private val SHAREABLE_CATALOG_HOSTS = setOf(
    "music.163.com",
    "y.music.163.com",
    "www.bilibili.com",
    "bilibili.com",
    "m.bilibili.com",
    "b23.tv",
    "music.youtube.com",
    "www.youtube.com",
    "youtube.com",
    "m.youtube.com",
    "youtu.be",
)

internal fun buildRemoteSongShareUrl(originalSong: SongItem, queue: List<SongItem>): String? {
    extractYouTubeMusicVideoId(originalSong.mediaUri)?.let { videoId ->
        if (videoId.isNotBlank()) {
            return "https://music.youtube.com/watch?v=$videoId"
        }
    }

    if (isBilibiliSong(originalSong)) {
        if (originalSong.id <= 0L) return null
        val videoParts = queue.filter {
            it.id == originalSong.id && isBilibiliSong(it)
        }
        if (videoParts.size > 1) {
            val pageIndex = videoParts.indexOfFirst { it.album == originalSong.album }
            if (pageIndex != -1) {
                return "https://www.bilibili.com/video/av${originalSong.id}/?p=${pageIndex + 1}"
            }
        }
        return "https://www.bilibili.com/video/av${originalSong.id}"
    }

    if (isNeteaseSong(originalSong)) {
        if (originalSong.id <= 0L) return null
        return "https://music.163.com/#/song?id=${originalSong.id}"
    }

    // Pure local / unknown local-like songs must not invent a NetEase link.
    if (LocalSongSupport.isLocalSong(originalSong, context = null)) {
        return null
    }

    val mediaUri = originalSong.mediaUri?.trim().orEmpty()
    if (mediaUri.isNotEmpty() && isShareablePublicHttpUrl(mediaUri)) {
        return mediaUri
    }

    return null
}

internal fun isShareablePublicHttpUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase(Locale.US)?.trimEnd('.') ?: return false
    if (host.isEmpty()) return false
    return SHAREABLE_CATALOG_HOSTS.any { allowed ->
        host == allowed || host.endsWith(".$allowed")
    }
}

private fun isNeteaseSong(song: SongItem): Boolean {
    if (song.channelId.equals("netease", ignoreCase = true)) return true
    return song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG)
}

private fun isBilibiliSong(song: SongItem): Boolean {
    if (song.channelId.equals("bilibili", ignoreCase = true)) return true
    return song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)
}
