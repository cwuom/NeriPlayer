package moe.ouom.neriplayer.platform.youtube.api.protocol

import moe.ouom.neriplayer.platform.youtube.api.auth.hasEffectiveAuth
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized

import java.util.Locale
import moe.ouom.neriplayer.platform.youtube.api.auth.shouldStartYouTubeWebAuthRecovery
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicBootstrapConfig
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicVideoMetadata
import org.json.JSONObject

internal const val YOUTUBE_MUSIC_SAFE_FALLBACK_HL = "zh-CN"
internal const val YOUTUBE_MUSIC_SAFE_FALLBACK_GL = "JP"
internal const val YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT = 24
internal const val YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT = 30
internal const val YOUTUBE_MUSIC_AUTH_REFRESH_RETRY_LIMIT = 1

internal fun parseYouTubeMusicVideoMetadata(raw: String): YouTubeMusicVideoMetadata {
    val root = JSONObject(raw)
    return YouTubeMusicVideoMetadata(
        title = root.optString("title"),
        authorName = root.optString("author_name"),
        thumbnailUrl = root.optString("thumbnail_url")
    )
}

internal fun YouTubeMusicBootstrapConfig.hasEffectiveLogin(auth: YouTubeAuthBundle): Boolean {
    return loggedIn || auth.normalized().hasEffectiveAuth()
}

internal fun shouldRefreshYouTubeAuthAfterEmptyResponse(
    bootstrapLoggedIn: Boolean,
    hasLoginCookies: Boolean
): Boolean {
    return !bootstrapLoggedIn && hasLoginCookies
}

internal fun shouldRefreshYouTubeAuthAfterBootstrapFailure(
    error: Throwable,
    hasCookieHeader: Boolean
): Boolean {
    return hasCookieHeader && shouldStartYouTubeWebAuthRecovery(error)
}

internal fun shouldRetryYouTubeMusicAuthRefresh(authRefreshRetryCount: Int): Boolean {
    return authRefreshRetryCount < YOUTUBE_MUSIC_AUTH_REFRESH_RETRY_LIMIT
}

object YouTubeMusicLocaleResolver {
    private val safeFallback = YouTubeMusicRequestLocale(
        hl = YOUTUBE_MUSIC_SAFE_FALLBACK_HL,
        gl = YOUTUBE_MUSIC_SAFE_FALLBACK_GL
    )

    fun preferred(locale: Locale = Locale.getDefault()): YouTubeMusicRequestLocale {
        var country = locale.country
        if (country.isBlank() || country.equals("CN", ignoreCase = true)) {
            country = safeFallback.gl
        }
        val language = locale.language.ifBlank { safeFallback.hl.substringBefore('-') }
        return YouTubeMusicRequestLocale(
            hl = if (language.equals("zh", ignoreCase = true)) "zh-CN" else "$language-$country",
            gl = country
        )
    }

    fun requestCandidates(
        preferredLocale: YouTubeMusicRequestLocale = preferred()
    ): List<YouTubeMusicRequestLocale> {
        return if (preferredLocale == safeFallback) {
            listOf(safeFallback)
        } else {
            listOf(preferredLocale, safeFallback)
        }
    }

    fun shouldRetryWithSafeFallback(payload: JSONObject, root: JSONObject): Boolean {
        if (payload.has("continuation")) {
            return false
        }
        return root.optJSONObject("contents") == null &&
            root.optJSONObject("continuationContents") == null
    }
}

internal object YouTubeMusicSearchParams {
    private const val SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    private const val VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
    private const val CREATORS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"

    fun forFilter(filter: YouTubeMusicSearchFilter): String {
        return when (filter) {
            YouTubeMusicSearchFilter.Song -> SONGS
            YouTubeMusicSearchFilter.Video -> VIDEOS
            YouTubeMusicSearchFilter.Creator -> CREATORS
        }
    }

    fun songs(): String = forFilter(YouTubeMusicSearchFilter.Song)

    fun videos(): String = forFilter(YouTubeMusicSearchFilter.Video)

    fun creators(): String = forFilter(YouTubeMusicSearchFilter.Creator)
}
