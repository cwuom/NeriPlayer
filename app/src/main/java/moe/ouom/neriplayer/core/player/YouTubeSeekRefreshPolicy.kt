package moe.ouom.neriplayer.core.player

import java.net.URI
import java.net.URLDecoder
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
        if (shouldRefreshForMissingPoToken(resolvedUrl) || isNearExpiry(resolvedUrl)) {
            return true
        }
        if (YouTubeGoogleVideoRangeSupport.supportsSeekingWithoutUrlRefresh(resolvedUrl)) {
            return false
        }
        return true
    }

    fun shouldRefreshUrlBeforeResume(song: SongItem?, currentUrl: String?): Boolean {
        if (song == null || !isYouTubeMusicSong(song)) {
            return false
        }
        val resolvedUrl = currentUrl?.takeIf { it.isNotBlank() } ?: return false
        if (resolvedUrl.startsWith("file://") || resolvedUrl.startsWith("http://offline.cache/")) {
            return false
        }
        return shouldRefreshForMissingPoToken(resolvedUrl) || isNearExpiry(resolvedUrl)
    }

    private fun shouldRefreshForMissingPoToken(url: String): Boolean {
        val host = runCatching { URI(url).host }
            .getOrNull()
            ?.lowercase()
            .orEmpty()
        if (!host.contains("googlevideo.com")) {
            return false
        }
        val source = extractQueryParameter(url, "source")
        if (!source.equals("youtube", ignoreCase = true)) {
            return false
        }
        if (extractQueryParameter(url, "pot").isNullOrBlank().not()) {
            return false
        }
        val clientName = extractQueryParameter(url, "c")
            ?.trim()
            ?.uppercase()
            .orEmpty()
        return clientName.isBlank() || clientName == "WEB_REMIX"
    }

    private fun isNearExpiry(url: String): Boolean {
        val expireEpochSeconds = extractQueryParameter(url, "expire")
            ?.toLongOrNull()
            ?: return false
        val expireAtMs = expireEpochSeconds * 1000L
        return System.currentTimeMillis() + URL_EXPIRY_GRACE_MS >= expireAtMs
    }

    private fun extractQueryParameter(url: String, key: String): String? {
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        return rawQuery.split('&')
            .asSequence()
            .mapNotNull { segment ->
                val resolvedKey = URLDecoder.decode(
                    segment.substringBefore('='),
                    Charsets.UTF_8.name()
                )
                if (resolvedKey.isBlank()) {
                    null
                } else {
                    resolvedKey to URLDecoder.decode(
                        segment.substringAfter('=', ""),
                        Charsets.UTF_8.name()
                    )
                }
            }
            .firstOrNull { (resolvedKey, _) -> resolvedKey == key }
            ?.second
    }

    private const val URL_EXPIRY_GRACE_MS = 2L * 60L * 1000L
}
