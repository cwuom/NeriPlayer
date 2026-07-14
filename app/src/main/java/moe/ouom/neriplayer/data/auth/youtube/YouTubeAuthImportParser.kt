package moe.ouom.neriplayer.data.auth.youtube

import moe.ouom.neriplayer.data.auth.common.parseRawCookieText
import moe.ouom.neriplayer.data.auth.common.parseRawHeaderText

private val YOUTUBE_IMPORT_HEADER_KEYS = setOf(
    "cookie",
    "authorization",
    "x-goog-authuser",
    "x-origin",
    "origin",
    "user-agent"
)

fun parseYouTubeAuthBundleFromRaw(
    raw: String,
    savedAt: Long = System.currentTimeMillis()
): YouTubeAuthBundle? {
    val normalizedRaw = raw.trim()
    if (normalizedRaw.isBlank()) {
        return null
    }

    val parsedHeaders = parseRawHeaderText(normalizedRaw)
        .filterKeys { it in YOUTUBE_IMPORT_HEADER_KEYS }
    if (parsedHeaders.isNotEmpty()) {
        val cookieHeader = parsedHeaders["cookie"].orEmpty()
        val cookies = parseRawCookieText(cookieHeader)
        if (cookies.isNotEmpty()) {
            return YouTubeAuthBundle(
                cookieHeader = cookieHeader,
                cookies = cookies,
                authorization = parsedHeaders["authorization"].orEmpty(),
                xGoogAuthUser = parsedHeaders["x-goog-authuser"].orEmpty(),
                origin = parsedHeaders["x-origin"].orEmpty()
                    .ifBlank { parsedHeaders["origin"].orEmpty() }
                    .ifBlank { YOUTUBE_MUSIC_ORIGIN },
                userAgent = parsedHeaders["user-agent"].orEmpty(),
                savedAt = savedAt
            )
        }
    }

    val parsedCookies = parseRawCookieText(normalizedRaw)
    if (parsedCookies.isEmpty()) {
        return null
    }
    return YouTubeAuthBundle(
        cookieHeader = parsedCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" },
        cookies = parsedCookies,
        savedAt = savedAt
    )
}
