package moe.ouom.neriplayer.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

const val YOUTUBE_MUSIC_MEDIA_URI_SCHEME: String = "ytmusic"
private const val YOUTUBE_MUSIC_MEDIA_URI_HOST: String = "video"
private const val YOUTUBE_MUSIC_SOCIALLY_CONSENTED_COOKIE: String = "SOCS=CAI"

fun YouTubeAuthBundle.effectiveCookieHeader(): String {
    val normalized = normalized(savedAt = savedAt)
    val cookieMap = normalized.cookies.ifEmpty { parseCookieHeader(normalized.cookieHeader) }
    if (cookieMap.isEmpty()) {
        return normalized.cookieHeader.trim()
    }
    val merged = LinkedHashMap(cookieMap)
    if (merged["SOCS"].isNullOrBlank()) {
        merged["SOCS"] = "CAI"
    }
    return merged.entries.joinToString("; ") { (key, value) -> "$key=$value" }
}

fun YouTubeAuthBundle.resolveRequestUserAgent(): String {
    return userAgent
        .takeIf { it.isNotBlank() }
        ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0"
}

fun YouTubeAuthBundle.resolveXGoogAuthUser(): String {
    return xGoogAuthUser.takeIf { it.isNotBlank() } ?: "0"
}

fun YouTubeAuthBundle.resolveAuthorizationHeader(
    origin: String = this.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
): String {
    val cookies = normalized(savedAt = savedAt).cookies.ifEmpty { parseCookieHeader(cookieHeader) }
    val sapisid = listOf(
        cookies["__Secure-3PAPISID"],
        cookies["SAPISID"],
        cookies["__Secure-1PAPISID"],
        cookies["APISID"]
    ).firstOrNull { !it.isNullOrBlank() }

    if (sapisid.isNullOrBlank()) {
        return authorization.takeIf { it.isNotBlank() }.orEmpty()
    }

    val input = "$nowEpochSeconds $sapisid $origin"
    val digest = MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    return "SAPISIDHASH ${nowEpochSeconds}_$digest"
}

fun buildYouTubeMusicMediaUri(videoId: String, playlistId: String? = null): String {
    val encodedVideoId = videoId.urlEncode()
    return buildString {
        append("$YOUTUBE_MUSIC_MEDIA_URI_SCHEME://$YOUTUBE_MUSIC_MEDIA_URI_HOST/")
        append(encodedVideoId)
        if (!playlistId.isNullOrBlank()) {
            append("?playlistId=")
            append(playlistId.urlEncode())
        }
    }
}

fun extractYouTubeMusicVideoId(mediaUri: String?): String? {
    if (mediaUri.isNullOrBlank()) {
        return null
    }

    return runCatching {
        when {
            mediaUri.startsWith("$YOUTUBE_MUSIC_MEDIA_URI_SCHEME://$YOUTUBE_MUSIC_MEDIA_URI_HOST/") -> {
                mediaUri
                    .removePrefix("$YOUTUBE_MUSIC_MEDIA_URI_SCHEME://$YOUTUBE_MUSIC_MEDIA_URI_HOST/")
                    .substringBefore('?')
                    .substringBefore('#')
                    .urlDecode()
                    .takeIf { it.isNotBlank() }
            }
            else -> {
                val uri = URI(mediaUri)
                val host = uri.host?.lowercase(Locale.US)
                if (host == "music.youtube.com" || host == "www.youtube.com" || host == "youtube.com") {
                    parseQueryParameters(uri.rawQuery)["v"]?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }
    }.getOrNull()
}

fun isYouTubeMusicSong(song: SongItem): Boolean = extractYouTubeMusicVideoId(song.mediaUri) != null

fun stableYouTubeMusicId(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .toByteArray()
    var result = 0L
    digest.forEach { byte ->
        result = (result shl 8) or (byte.toLong() and 0xff)
    }
    return if (result == 0L) 1L else result
}

fun appendYouTubeConsentCookie(cookieHeader: String): String {
    if (cookieHeader.isBlank()) {
        return YOUTUBE_MUSIC_SOCIALLY_CONSENTED_COOKIE
    }
    if (cookieHeader.contains("SOCS=")) {
        return cookieHeader
    }
    return "$cookieHeader; $YOUTUBE_MUSIC_SOCIALLY_CONSENTED_COOKIE"
}

private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) {
        return emptyMap()
    }

    return rawQuery
        .split('&')
        .mapNotNull { segment ->
            val key = segment.substringBefore('=').urlDecode()
            if (key.isBlank()) {
                null
            } else {
                val value = segment.substringAfter('=', "").urlDecode()
                key to value
            }
        }
        .toMap()
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())
