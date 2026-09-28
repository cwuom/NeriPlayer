package moe.ouom.neriplayer.core.api.youtube.protocol

import java.util.Locale
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.shouldStartYouTubeWebAuthRecovery
import org.json.JSONObject

internal const val YOUTUBE_MUSIC_SAFE_FALLBACK_HL = "zh-CN"
internal const val YOUTUBE_MUSIC_SAFE_FALLBACK_GL = "JP"
internal const val YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT = 24
internal const val YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT = 30
internal const val YOUTUBE_MUSIC_AUTH_REFRESH_RETRY_LIMIT = 1

data class YouTubeMusicLibraryPlaylist(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val trackCount: Int? = null
)

data class YouTubeMusicPlaylistTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationText: String,
    val durationMs: Long,
    val coverUrl: String
)

data class YouTubeMusicPlaylistDetail(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val trackCount: Int? = null,
    val tracks: List<YouTubeMusicPlaylistTrack>,
    val fullyLoaded: Boolean = true
)

internal data class YouTubeMusicPlaylistPage(
    val tracks: List<YouTubeMusicPlaylistTrack>,
    val continuation: String? = null
)

data class YouTubeMusicPlayableAudio(
    val url: String,
    val durationMs: Long,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val bitrate: Int = 0
)

data class YouTubeMusicLyrics(
    val lyrics: String,
    val source: String = ""
)

data class YouTubeMusicDebugProbeResult(
    val summary: String,
    val rawJson: String
)

enum class YouTubeMusicSearchResultType {
    Song,
    Video
}

enum class YouTubeMusicSearchFilter {
    Song,
    Video,
    Creator
}

data class YouTubeMusicSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val subtitle: String,
    val coverUrl: String,
    val durationText: String,
    val durationMs: Long,
    val type: YouTubeMusicSearchResultType
)

data class YouTubeMusicCreatorSummary(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val channelId: String = ""
)

data class YouTubeMusicCreatorHeader(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val description: String = "",
    val subscriberCountText: String = "",
    val monthlyListenerCountText: String = ""
)

enum class YouTubeMusicCreatorItemType {
    Song,
    Video,
    Album,
    Playlist,
    Creator
}

data class YouTubeMusicCreatorItem(
    val type: YouTubeMusicCreatorItemType,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val videoId: String = "",
    val browseId: String = "",
    val playlistId: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L
)

data class YouTubeMusicCreatorBrowseEndpoint(
    val browseId: String,
    val params: String = ""
)

data class YouTubeMusicCreatorSection(
    val title: String,
    val items: List<YouTubeMusicCreatorItem>,
    val moreEndpoint: YouTubeMusicCreatorBrowseEndpoint? = null
)

data class YouTubeMusicCreatorDetail(
    val header: YouTubeMusicCreatorHeader,
    val sections: List<YouTubeMusicCreatorSection>
)

data class YouTubeMusicCreatorItemsPage(
    val title: String,
    val items: List<YouTubeMusicCreatorItem>,
    val continuation: String? = null
)

data class YouTubeMusicVideoMetadata(
    val title: String,
    val authorName: String,
    val thumbnailUrl: String
)

internal fun parseYouTubeMusicVideoMetadata(raw: String): YouTubeMusicVideoMetadata {
    val root = JSONObject(raw)
    return YouTubeMusicVideoMetadata(
        title = root.optString("title"),
        authorName = root.optString("author_name"),
        thumbnailUrl = root.optString("thumbnail_url")
    )
}

/** 首页推荐栏 */
data class YouTubeMusicHomeShelf(
    val title: String,
    val items: List<YouTubeMusicHomeItem>
)

/** 推荐栏中的单个项 (歌单/专辑/单曲) */
data class YouTubeMusicHomeItem(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val browseId: String = "",
    val videoId: String = "",
    val pageType: String = "",
    val durationText: String = "",
    val durationMs: Long = 0L
)

internal data class ParsedYouTubeMusicHomeShelf(
    val title: String,
    val items: List<YouTubeMusicHomeItem>,
    val continuation: String? = null
)

internal data class YouTubeMusicBootstrapConfig(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val sessionIndex: String,
    val loggedIn: Boolean,
    val userSessionId: String,
    val cookieHeader: String,
    val authFingerprint: String,
    val webUserAgent: String,
    val fetchedAtMs: Long
)

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

data class YouTubeMusicRequestLocale(
    val hl: String,
    val gl: String
) {
    val acceptLanguage: String
        get() = buildString {
            append(hl)
            append(",")
            append(gl.lowercase(Locale.US))
            append(";q=0.9,en;q=0.8")
        }
}

internal data class YouTubeMusicBrowseResponse(
    val bootstrap: YouTubeMusicBootstrapConfig,
    val root: JSONObject,
    val requestLocale: YouTubeMusicRequestLocale
)

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

data class YouTubeMusicHomeSongMetadata(
    val artist: String,
    val album: String
)
