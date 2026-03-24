package moe.ouom.neriplayer.data.platform.youtube

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.platform.youtube/YouTubeMusicSupport
 * Updated: 2026/3/23
 */


import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import moe.ouom.neriplayer.data.auth.youtube.YouTubeCookieSupport
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.auth.youtube.parseCookieHeader
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

const val YOUTUBE_MUSIC_MEDIA_URI_SCHEME: String = "ytmusic"
private const val YOUTUBE_MUSIC_MEDIA_URI_HOST: String = "video"
private const val YOUTUBE_MUSIC_SOCIALLY_CONSENTED_COOKIE: String = "SOCS=CAI"
const val YOUTUBE_WEB_ORIGIN: String = "https://www.youtube.com"
const val YOUTUBE_DEFAULT_WEB_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/146.0.0.0 Safari/537.36"
internal const val YOUTUBE_STREAM_ANDROID_USER_AGENT: String =
    "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
internal const val YOUTUBE_STREAM_IOS_USER_AGENT: String =
    "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"

private val YOUTUBE_GOOGLE_VIDEO_STRIPPED_HEADERS = setOf(
    "authorization",
    "cookie",
    "x-goog-api-format-version",
    "x-goog-authuser",
    "x-goog-visitor-id",
    "x-origin",
    "x-youtube-client-name",
    "x-youtube-client-version"
)

fun YouTubeAuthBundle.effectiveCookieHeader(): String {
    val normalized = normalized(savedAt = savedAt)
    val cookieMap = normalized.cookies.ifEmpty { parseCookieHeader(normalized.cookieHeader) }
    if (cookieMap.isEmpty()) {
        return normalized.cookieHeader.trim()
    }
    val sanitizedCookies = YouTubeCookieSupport.sanitizePersistedCookies(cookieMap)
    if (sanitizedCookies.isEmpty()) {
        return ""
    }
    val merged = LinkedHashMap(sanitizedCookies)
    if (merged["SOCS"].isNullOrBlank()) {
        merged["SOCS"] = "CAI"
    }
    return merged.entries.joinToString("; ") { (key, value) -> "$key=$value" }
}

fun YouTubeAuthBundle.resolveRequestUserAgent(): String {
    return userAgent
        .takeIf { it.isNotBlank() }
        ?: YOUTUBE_DEFAULT_WEB_USER_AGENT
}

fun YouTubeAuthBundle.resolveBootstrapUserAgent(): String {
    val candidate = resolveRequestUserAgent().trim()
    if (candidate.isBlank()) {
        return YOUTUBE_DEFAULT_WEB_USER_AGENT
    }
    val normalized = candidate.lowercase(Locale.US)
    return if (
        normalized.contains(" mobile") ||
        normalized.contains("android") ||
        normalized.contains("iphone") ||
        normalized.contains("ipad")
    ) {
        YOUTUBE_DEFAULT_WEB_USER_AGENT
    } else {
        candidate
    }
}

fun YouTubeAuthBundle.resolveXGoogAuthUser(): String {
    return xGoogAuthUser.takeIf { it.isNotBlank() } ?: "0"
}

fun YouTubeAuthBundle.resolveAuthorizationHeader(
    origin: String = this.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    userSessionId: String = ""
): String {
    val cookies = normalized(savedAt = savedAt).cookies.ifEmpty { parseCookieHeader(cookieHeader) }
    val sapisid = firstNonBlank(
        cookies["SAPISID"],
        cookies["__Secure-3PAPISID"],
        cookies["__Secure-1PAPISID"],
        cookies["APISID"]
    )
    val sapisid1P = firstNonBlank(cookies["__Secure-1PAPISID"])
    val sapisid3P = firstNonBlank(cookies["__Secure-3PAPISID"])

    if (sapisid.isNullOrBlank() && sapisid1P.isNullOrBlank() && sapisid3P.isNullOrBlank()) {
        return authorization.takeIf { it.isNotBlank() }.orEmpty()
    }
    return buildList {
        sapisid?.let {
            add(
                buildSidAuthorization(
                    scheme = "SAPISIDHASH",
                    sid = it,
                    origin = origin,
                    nowEpochSeconds = nowEpochSeconds,
                    userSessionId = userSessionId
                )
            )
        }
        sapisid1P?.let {
            add(
                buildSidAuthorization(
                    scheme = "SAPISID1PHASH",
                    sid = it,
                    origin = origin,
                    nowEpochSeconds = nowEpochSeconds,
                    userSessionId = userSessionId
                )
            )
        }
        sapisid3P?.let {
            add(
                buildSidAuthorization(
                    scheme = "SAPISID3PHASH",
                    sid = it,
                    origin = origin,
                    nowEpochSeconds = nowEpochSeconds,
                    userSessionId = userSessionId
                )
            )
        }
    }.joinToString(" ")
}

fun YouTubeAuthBundle.buildYouTubePageRequestHeaders(
    original: Map<String, String> = emptyMap(),
    userAgent: String = resolveRequestUserAgent(),
    includeAuthUser: Boolean = false
): Map<String, String> {
    val headers = LinkedHashMap(original)
    val cookieHeader = appendYouTubeConsentCookie(effectiveCookieHeader())
    if (cookieHeader.isNotBlank()) {
        headers["Cookie"] = cookieHeader
    }
    headers.putIfAbsent("User-Agent", userAgent)
    if (includeAuthUser) {
        headers.putIfAbsent("X-Goog-AuthUser", resolveXGoogAuthUser())
    }
    return headers
}

fun YouTubeAuthBundle.buildYouTubeInnertubeRequestHeaders(
    original: Map<String, String> = emptyMap(),
    authorizationOrigin: String = origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
    includeAuthorization: Boolean = true
): Map<String, String> {
    val headers = LinkedHashMap(original)
    val cookieHeader = appendYouTubeConsentCookie(effectiveCookieHeader())
    if (cookieHeader.isNotBlank()) {
        headers["Cookie"] = cookieHeader
    }
    headers.putIfAbsent("User-Agent", resolveRequestUserAgent())
    headers.putIfAbsent("X-Goog-AuthUser", resolveXGoogAuthUser())

    if (includeAuthorization) {
        val authorization = resolveAuthorizationHeader(origin = authorizationOrigin)
        if (authorization.isNotBlank()) {
            headers.putIfAbsent("Authorization", authorization)
        }
    }

    return headers
}

fun YouTubeAuthBundle.buildYouTubeStreamRequestHeaders(
    original: Map<String, String> = emptyMap(),
    refererOrigin: String = origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
    includeReferer: Boolean = true,
    streamUrl: String? = null
): Map<String, String> {
    // googlevideo/manifest 是跨域媒体请求，不应继续附带 YouTube 登录态头
    val headers = LinkedHashMap<String, String>()
    original.forEach { (name, value) ->
        if (name.lowercase(Locale.US) !in YOUTUBE_GOOGLE_VIDEO_STRIPPED_HEADERS) {
            headers[name] = value
        }
    }
    headers.putIfAbsent("User-Agent", resolveYouTubeStreamUserAgent(streamUrl))
    headers.putIfAbsent("Origin", refererOrigin)
    if (includeReferer) {
        headers.putIfAbsent("Referer", "$refererOrigin/")
    }
    return headers
}

fun YouTubeAuthBundle.resolveYouTubeStreamUserAgent(streamUrl: String?): String {
    val clientName = parseYouTubeStreamClientName(streamUrl)
    return when (clientName) {
        "IOS" -> YOUTUBE_STREAM_IOS_USER_AGENT
        "ANDROID", "ANDROID_TESTSUITE" -> YOUTUBE_STREAM_ANDROID_USER_AGENT
        else -> resolveBootstrapUserAgent()
    }
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

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun buildSidAuthorization(
    scheme: String,
    sid: String,
    origin: String,
    nowEpochSeconds: Long,
    userSessionId: String
): String {
    val additionalParts = linkedMapOf<String, String>()
    if (userSessionId.isNotBlank()) {
        additionalParts["u"] = userSessionId
    }
    val hashParts = mutableListOf<String>()
    if (additionalParts.isNotEmpty()) {
        hashParts += additionalParts.values.joinToString(":")
    }
    hashParts += nowEpochSeconds.toString()
    hashParts += sid
    hashParts += origin
    val digest = MessageDigest.getInstance("SHA-1")
        .digest(hashParts.joinToString(" ").toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    val headerParts = mutableListOf(nowEpochSeconds.toString(), digest)
    if (additionalParts.isNotEmpty()) {
        headerParts += additionalParts.keys.joinToString("")
    }
    return "$scheme ${headerParts.joinToString("_")}"
}

private fun parseYouTubeStreamClientName(streamUrl: String?): String? {
    if (streamUrl.isNullOrBlank()) {
        return null
    }
    val rawQuery = runCatching { URI(streamUrl).rawQuery }.getOrNull()
    return parseQueryParameters(rawQuery)["c"]
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.US)
}

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())
