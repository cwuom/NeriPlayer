package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.data.isYouTubeMusicSong
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal object YouTubeSeekRefreshPolicy {
    fun shouldRefreshUrlBeforeSeek(song: SongItem?, currentUrl: String?): Boolean {
        if (song == null || !isYouTubeMusicSong(song)) {
            return false
        }
        val resolvedUrl = currentUrl?.takeIf { it.isNotBlank() } ?: return false
        if (resolvedUrl.startsWith("file://") || resolvedUrl.startsWith("http://offline.cache/")) {
            return false
        }
        if (YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh(resolvedUrl)) {
            return false
        }
        return true
    }
}
