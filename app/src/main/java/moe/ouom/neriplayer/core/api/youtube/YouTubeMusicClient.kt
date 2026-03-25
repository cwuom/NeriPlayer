package moe.ouom.neriplayer.core.api.youtube

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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeMusicClient
 * Updated: 2026/3/23
 */


import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.auth.youtube.isYouTubeAuthRecoverableFailure
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthRepository
import moe.ouom.neriplayer.data.platform.youtube.buildAuthCacheFingerprint
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveRequestUserAgent
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val YOUTUBE_MUSIC_BROWSE_ID_LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
private const val YOUTUBE_MUSIC_MUSIC_ORIGIN = "https://music.youtube.com"
private const val YOUTUBE_MUSIC_DEFAULT_WEB_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/134.0.0.0 Safari/537.36"
private const val YOUTUBE_MUSIC_BOOTSTRAP_TTL_MS = 10L * 60L * 1000L
private const val YOUTUBE_MUSIC_CLIENT_NAME_NUM_WEB_REMIX = "67"
private const val YOUTUBE_MUSIC_CLIENT_NAME_WEB_REMIX = "WEB_REMIX"
private const val YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT = 20
private const val YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS = 2
private const val YOUTUBE_MUSIC_SAFE_FALLBACK_HL = "zh-CN"
private const val YOUTUBE_MUSIC_SAFE_FALLBACK_GL = "JP"
private const val YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT = 24
private const val YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT = 30

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
    val tracks: List<YouTubeMusicPlaylistTrack>
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

/** 首页推荐栏 */
data class YouTubeMusicHomeShelf(
    val title: String,
    val items: List<YouTubeMusicHomeItem>
)

/** 推荐栏中的单个项（歌单/专辑/单曲） */
data class YouTubeMusicHomeItem(
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val browseId: String = "",
    val videoId: String = "",
    val pageType: String = ""
)

internal data class ParsedYouTubeMusicHomeShelf(
    val title: String,
    val items: List<YouTubeMusicHomeItem>,
    val continuation: String? = null
)

private data class ParsedYouTubeMusicSearchMetadata(
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationText: String = ""
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

internal data class YouTubeMusicRequestLocale(
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

internal object YouTubeMusicLocaleResolver {
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
    private const val FILTERED_PREFIX = "EgWKAQ"
    private const val FILTER_SUFFIX_DEFAULT = "AWoMEA4QChADEAQQCRAF"
    private const val FILTER_SUFFIX_IGNORE_SPELLING = "AUICCAFqDBAOEAoQAxAEEAkQBQ%3D%3D"

    /**
     * YouTube Music Web search filter params.
     * 当前 songs -> "II" 为固定协议片段，但属于未公开内部参数，未来可能变化
     */
    fun songs(ignoreSpelling: Boolean = false): String {
        return buildString {
            append(FILTERED_PREFIX)
            append("II")
            append(
                if (ignoreSpelling) {
                    FILTER_SUFFIX_IGNORE_SPELLING
                } else {
                    FILTER_SUFFIX_DEFAULT
                }
            )
        }
    }
}

internal data class YouTubeMusicHomeSongMetadata(
    val artist: String,
    val album: String
)

internal object YouTubeMusicParser {
    fun parseBootstrapConfig(
        html: String,
        cookieHeader: String,
        userAgent: String
    ): YouTubeMusicBootstrapConfig {
        val now = System.currentTimeMillis()
        val bootstrapSource = YouTubeBootstrapHtmlSource(html)
        val dataSyncId = bootstrapSource.optionalString("DATASYNC_ID", "datasyncId")
        val (_, derivedUserSessionId) = parseDataSyncId(dataSyncId)
        val loggedIn = bootstrapSource.optionalBoolean("LOGGED_IN")
            .equals("true", ignoreCase = true)
        return YouTubeMusicBootstrapConfig(
            apiKey = bootstrapSource.requireString(
                "YouTube Music bootstrap parse failed",
                "INNERTUBE_API_KEY",
                "innertubeApiKey"
            ),
            webRemixClientVersion = bootstrapSource.requireString(
                "YouTube Music bootstrap parse failed",
                "INNERTUBE_CLIENT_VERSION",
                "INNERTUBE_CONTEXT_CLIENT_VERSION",
                "innertubeContextClientVersion"
            ),
            visitorData = bootstrapSource.requireString(
                "YouTube Music bootstrap parse failed",
                "VISITOR_DATA",
                "visitorData"
            ),
            sessionIndex = bootstrapSource.optionalNumber("SESSION_INDEX").ifBlank { "0" },
            loggedIn = loggedIn,
            userSessionId = bootstrapSource.optionalString("USER_SESSION_ID")
                .ifBlank { derivedUserSessionId },
            cookieHeader = cookieHeader,
            authFingerprint = "",
            webUserAgent = userAgent,
            fetchedAtMs = now
        )
    }

    fun parseLibraryPlaylists(root: JSONObject): List<YouTubeMusicLibraryPlaylist> {
        val items = findLibraryGridRenderer(root)
            ?.optJSONArray("items")
            ?: return emptyList()

        return buildList {
            for (index in 0 until items.length()) {
                val renderer = items.optJSONObject(index)
                    ?.optJSONObject("musicTwoRowItemRenderer")
                    ?: continue
                val browseEndpoint = renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?: continue
                val browseId = browseEndpoint.optString("browseId", "").trim()
                val title = extractText(renderer.optJSONObject("title"))
                if (browseId.isBlank() || title.isBlank()) {
                    continue
                }
                val subtitle = extractText(renderer.optJSONObject("subtitle"))
                add(
                    YouTubeMusicLibraryPlaylist(
                        browseId = browseId,
                        playlistId = playlistIdFromBrowseId(browseId),
                        title = title,
                        subtitle = subtitle,
                        coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnailRenderer")),
                        trackCount = parseTrackCount(subtitle)
                    )
                )
            }
        }
    }

    fun extractLibraryContinuation(root: JSONObject): String? {
        return extractContinuationToken(findLibraryGridRenderer(root))
    }

    fun parseHomeShelfPages(root: JSONObject): List<ParsedYouTubeMusicHomeShelf> {
        val sections = findHomeSections(root) ?: return emptyList()

        return buildList {
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                val shelfTitle = carousel.optJSONObject("header")
                    ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.let { extractText(it.optJSONObject("title")) }
                    ?: continue
                val items = parseHomeItems(
                    contents = carousel.optJSONArray("contents")
                )
                if (items.isNotEmpty()) {
                    add(
                        ParsedYouTubeMusicHomeShelf(
                            title = shelfTitle,
                            items = items,
                            continuation = extractContinuationToken(carousel)
                        )
                    )
                }
            }
        }
    }

    fun extractHomeContinuation(root: JSONObject): String? {
        return extractContinuationToken(findHomeSectionListRenderer(root))
    }

    fun parseHomeShelfContinuationItems(root: JSONObject): List<YouTubeMusicHomeItem> {
        return parseHomeItems(findHomeShelfContinuationRenderer(root)?.optJSONArray("contents"))
    }

    fun extractHomeShelfContinuation(root: JSONObject): String? {
        return extractContinuationToken(findHomeShelfContinuationRenderer(root))
    }

    fun parseHomeSongMetadata(
        subtitle: String,
        fallbackAlbum: String,
        fallbackArtist: String = "YouTube Music"
    ): YouTubeMusicHomeSongMetadata {
        val metadataParts = subtitle
            .split('•', '·', '|')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot(::looksLikeHomeSongTypeLabel)
            .filterNot(::looksLikeDurationText)
            .filterNot(::looksLikeSearchStatText)
            .toList()

        val artist = metadataParts.firstOrNull().orEmpty().ifBlank { fallbackArtist }
        val album = metadataParts.drop(1).firstOrNull().orEmpty().ifBlank { fallbackAlbum }
        return YouTubeMusicHomeSongMetadata(
            artist = artist,
            album = album
        )
    }

    fun parsePlaylistDetail(
        root: JSONObject,
        browseId: String,
        fallbackTitle: String,
        fallbackSubtitle: String,
        fallbackCoverUrl: String
    ): YouTubeMusicPlaylistDetail {
        val header = findPlaylistHeaderRenderer(root)
        val playlistShelf = findPlaylistShelfRenderer(root)

        return YouTubeMusicPlaylistDetail(
            browseId = browseId,
            playlistId = playlistShelf?.optString("playlistId", "")
                ?.ifBlank { playlistIdFromBrowseId(browseId) }
                .orEmpty(),
            title = extractText(header?.optJSONObject("title")).ifBlank { fallbackTitle },
            subtitle = extractText(header?.optJSONObject("subtitle")).ifBlank { fallbackSubtitle },
            coverUrl = extractMusicThumbnailUrl(header?.optJSONObject("thumbnail")).ifBlank { fallbackCoverUrl },
            trackCount = parsePlaylistTrackCount(root),
            tracks = parsePlaylistTracks(root)
        )
    }

    fun parsePlaylistTracks(root: JSONObject): List<YouTubeMusicPlaylistTrack> {
        val contents = findPlaylistShelfRenderer(root)
            ?.optJSONArray("contents")
            ?: return emptyList()

        return buildList {
            for (index in 0 until contents.length()) {
                val renderer = contents.optJSONObject(index)
                    ?.optJSONObject("musicResponsiveListItemRenderer")
                    ?: continue
                val videoId = extractTrackVideoId(renderer)
                val title = extractColumnText(
                    columns = renderer.optJSONArray("flexColumns"),
                    index = 0,
                    rendererKey = "musicResponsiveListItemFlexColumnRenderer"
                )
                if (videoId.isBlank() || title.isBlank()) {
                    continue
                }
                var durationText = extractColumnText(
                    columns = renderer.optJSONArray("fixedColumns"),
                    index = 0,
                    rendererKey = "musicResponsiveListItemFixedColumnRenderer"
                )
                if (durationText.isBlank() || !durationText.contains(":")) {
                    val flex = renderer.optJSONArray("flexColumns")
                    if (flex != null) {
                        for (i in 0 until flex.length()) {
                            val text = extractColumnText(
                                columns = flex,
                                index = i,
                                rendererKey = "musicResponsiveListItemFlexColumnRenderer"
                            )
                            if (text.contains(":")) {
                                val parts = text.split(":")
                                if (parts.isNotEmpty() && parts.all { it.toLongOrNull() != null }) {
                                    durationText = text
                                    break
                                }
                            }
                        }
                    }
                }
                add(
                    YouTubeMusicPlaylistTrack(
                        videoId = videoId,
                        title = title,
                        artist = extractColumnText(
                            columns = renderer.optJSONArray("flexColumns"),
                            index = 1,
                            rendererKey = "musicResponsiveListItemFlexColumnRenderer"
                        ),
                        album = extractColumnText(
                            columns = renderer.optJSONArray("flexColumns"),
                            index = 2,
                            rendererKey = "musicResponsiveListItemFlexColumnRenderer"
                        ),
                        durationText = durationText,
                        durationMs = parseDurationTextToMs(durationText),
                        coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail"))
                    )
                )
            }
        }
    }

    fun extractPlaylistContinuation(root: JSONObject): String? {
        return extractContinuationToken(findPlaylistShelfRenderer(root))
    }

    fun parsePlaylistTrackCount(root: JSONObject): Int? {
        val header = findPlaylistHeaderRenderer(root)
        val headerCount = parseTrackCount(
            extractText(header?.optJSONObject("secondSubtitle")).ifBlank {
                extractText(header?.optJSONObject("subtitle"))
            }
        )
        if (headerCount != null) {
            return headerCount
        }

        val shelf = findPlaylistShelfRenderer(root) ?: return null
        val pageCount = shelf.optJSONArray("contents")?.length() ?: 0
        if (pageCount <= 0 || !extractContinuationToken(shelf).isNullOrBlank()) {
            return null
        }
        return pageCount
    }

    fun parseDurationTextToMs(durationText: String): Long {
        val parts = durationText.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) {
            return 0L
        }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> return 0L
        }
        return seconds * 1000L
    }

    private fun parseTrackCount(subtitle: String): Int? {
        val normalized = subtitle.trim()
        if (normalized.isBlank()) {
            return null
        }
        return Regex(
            pattern = "(\\d+)\\s*(?:首歌|首歌曲?|首|曲|集|songs?|tracks?|videos?|episodes?)",
            option = RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun findLibraryGridRenderer(root: JSONObject): JSONObject? {
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (sections != null) {
            for (index in 0 until sections.length()) {
                sections.optJSONObject(index)
                    ?.optJSONObject("gridRenderer")
                    ?.let { return it }
            }
        }
        return root.optJSONObject("continuationContents")?.optJSONObject("gridContinuation")
    }

    private fun findHomeSectionListRenderer(root: JSONObject): JSONObject? {
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
        if (tabs != null && tabs.length() > 0) {
            val sectionListRenderer = tabs.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
            if (sectionListRenderer != null) {
                return sectionListRenderer
            }
        }
        return root.optJSONObject("continuationContents")
            ?.optJSONObject("sectionListContinuation")
    }

    private fun findHomeSections(root: JSONObject): JSONArray? {
        return findHomeSectionListRenderer(root)?.optJSONArray("contents")
    }

    private fun findPlaylistHeaderRenderer(root: JSONObject): JSONObject? {
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return null
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            section.optJSONObject("musicResponsiveHeaderRenderer")?.let { return it }
            section.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                ?.optJSONObject("header")
                ?.optJSONObject("musicResponsiveHeaderRenderer")
                ?.let { return it }
        }
        return null
    }

    private fun findPlaylistShelfRenderer(root: JSONObject): JSONObject? {
        scanPlaylistSections(
            root.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
        )?.let { return it }

        // 一些歌单响应不会把曲目 shelf 放进 secondaryContents，需要回退到主内容区扫描
        scanPlaylistSections(
            root.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
        )?.let { return it }

        val continuationContents = root.optJSONObject("continuationContents")
        return continuationContents?.optJSONObject("musicPlaylistShelfContinuation")
            ?: continuationContents?.optJSONObject("musicShelfContinuation")
    }

    private fun findHomeShelfContinuationRenderer(root: JSONObject): JSONObject? {
        val continuationContents = root.optJSONObject("continuationContents")
        return continuationContents?.optJSONObject("musicShelfContinuation")
            ?: continuationContents?.optJSONObject("musicCarouselShelfContinuation")
            ?: continuationContents?.optJSONObject("musicPlaylistShelfContinuation")
    }

    private fun parseHomeItems(contents: JSONArray?): List<YouTubeMusicHomeItem> {
        if (contents == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until contents.length()) {
                val item = contents.optJSONObject(index) ?: continue
                val twoRow = item.optJSONObject("musicTwoRowItemRenderer")
                if (twoRow != null) {
                    val title = extractText(twoRow.optJSONObject("title"))
                    if (title.isBlank()) continue
                    val navigationEndpoint = twoRow.optJSONObject("navigationEndpoint")
                    val browseEndpoint = navigationEndpoint?.optJSONObject("browseEndpoint")
                    val browseId = navigationEndpoint
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("browseId", "")
                        .orEmpty()
                    val videoId = navigationEndpoint
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId", "")
                        .orEmpty()
                    add(
                        YouTubeMusicHomeItem(
                            title = title,
                            subtitle = extractText(twoRow.optJSONObject("subtitle")),
                            coverUrl = extractMusicThumbnailUrl(twoRow.optJSONObject("thumbnailRenderer")),
                            browseId = browseId,
                            videoId = videoId,
                            pageType = browseEndpoint
                                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                                ?.optJSONObject("browseEndpointContextMusicConfig")
                                ?.optString("pageType")
                                .orEmpty()
                        )
                    )
                    continue
                }

                val listItem = item.optJSONObject("musicResponsiveListItemRenderer")
                if (listItem != null) {
                    val title = extractColumnText(
                        columns = listItem.optJSONArray("flexColumns"),
                        index = 0,
                        rendererKey = "musicResponsiveListItemFlexColumnRenderer"
                    )
                    if (title.isBlank()) continue
                    add(
                        YouTubeMusicHomeItem(
                            title = title,
                            subtitle = extractColumnText(
                                columns = listItem.optJSONArray("flexColumns"),
                                index = 1,
                                rendererKey = "musicResponsiveListItemFlexColumnRenderer"
                            ),
                            coverUrl = extractMusicThumbnailUrl(listItem.optJSONObject("thumbnail")),
                            videoId = extractTrackVideoId(listItem)
                        )
                    )
                }
            }
        }
    }

    private fun scanPlaylistSections(sections: JSONArray?): JSONObject? {
        if (sections == null) {
            return null
        }
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            section.optJSONObject("musicPlaylistShelfRenderer")?.let { return it }
            section.optJSONObject("musicShelfRenderer")?.let { return it }
        }
        return null
    }

    fun hasSongSearchShelf(root: JSONObject): Boolean {
        return findSearchSongShelfRenderer(root) != null
    }

    fun parseSongSearchResults(
        root: JSONObject,
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
    ): List<YouTubeMusicSearchResult> {
        val items = parseSearchRendererItems(
            contents = findSearchSongContents(root),
            forcedType = YouTubeMusicSearchResultType.Song
        )
        return items
            .distinctBy { it.videoId }
            .take(limit.coerceAtLeast(1))
    }

    fun extractSearchContinuation(root: JSONObject): String? {
        return extractContinuationToken(findSearchSongShelfRenderer(root))
            ?: extractContinuationToken(
                root.optJSONObject("continuationContents")
                    ?.optJSONObject("musicShelfContinuation")
            )
    }

    private fun extractTrackVideoId(renderer: JSONObject): String {
        return firstNonBlank(
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId"),
            renderer.optJSONObject("playlistItemData")?.optString("videoId"),
            extractVideoIdFromTextRuns(
                renderer.optJSONArray("flexColumns")
                    ?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
            ),
            extractVideoIdFromMenu(renderer.optJSONObject("menu"))
        )
    }

    private fun extractVideoIdFromTextRuns(runs: JSONArray?): String {
        if (runs == null) {
            return ""
        }
        for (index in 0 until runs.length()) {
            val videoId = runs.optJSONObject(index)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                .orEmpty()
            if (videoId.isNotBlank()) {
                return videoId
            }
        }
        return ""
    }

    private fun extractVideoIdFromMenu(menu: JSONObject?): String {
        val items = menu?.optJSONObject("menuRenderer")?.optJSONArray("items") ?: return ""
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val navigationVideoId = item.optJSONObject("menuNavigationItemRenderer")
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                .orEmpty()
            if (navigationVideoId.isNotBlank()) {
                return navigationVideoId
            }

            val queueVideoId = item.optJSONObject("menuServiceItemRenderer")
                ?.optJSONObject("serviceEndpoint")
                ?.optJSONObject("queueAddEndpoint")
                ?.optJSONObject("queueTarget")
                ?.optString("videoId")
                .orEmpty()
            if (queueVideoId.isNotBlank()) {
                return queueVideoId
            }

            val onEmptyQueueVideoId = item.optJSONObject("menuServiceItemRenderer")
                ?.optJSONObject("serviceEndpoint")
                ?.optJSONObject("queueAddEndpoint")
                ?.optJSONObject("queueTarget")
                ?.optJSONObject("onEmptyQueue")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                .orEmpty()
            if (onEmptyQueueVideoId.isNotBlank()) {
                return onEmptyQueueVideoId
            }
        }
        return ""
    }

    private fun findSearchSectionListRenderer(root: JSONObject): JSONObject? {
        return root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
    }

    private fun findSearchSongShelfRenderer(root: JSONObject): JSONObject? {
        return findSearchShelfRenderers(root).firstOrNull()
    }

    private fun findSearchSongContents(root: JSONObject): JSONArray? {
        return findSearchSongShelfRenderer(root)?.optJSONArray("contents")
            ?: root.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
                ?.optJSONArray("contents")
    }

    private fun findSearchShelfRenderers(root: JSONObject): List<JSONObject> {
        val sections = findSearchSectionListRenderer(root)?.optJSONArray("contents") ?: return emptyList()
        return buildList {
            for (sectionIndex in 0 until sections.length()) {
                val section = sections.optJSONObject(sectionIndex) ?: continue
                section.optJSONObject("musicShelfRenderer")?.let(::add)

                val itemSectionContents = section.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents")
                    ?: continue
                for (itemIndex in 0 until itemSectionContents.length()) {
                    itemSectionContents.optJSONObject(itemIndex)
                        ?.optJSONObject("musicShelfRenderer")
                        ?.let(::add)
                }
            }
        }
    }

    private fun parseSearchRendererItems(
        contents: JSONArray?,
        forcedType: YouTubeMusicSearchResultType? = null
    ): List<YouTubeMusicSearchResult> {
        if (contents == null) {
            return emptyList()
        }
        return buildList {
            for (itemIndex in 0 until contents.length()) {
                val renderer = contents.optJSONObject(itemIndex)
                    ?.optJSONObject("musicResponsiveListItemRenderer")
                    ?: continue
                parseSearchResult(renderer, forcedType)?.let(::add)
            }
        }
    }

    private fun parseSearchResult(
        renderer: JSONObject,
        forcedType: YouTubeMusicSearchResultType? = null
    ): YouTubeMusicSearchResult? {
        val videoId = extractTrackVideoId(renderer)
        val title = extractColumnText(
            columns = renderer.optJSONArray("flexColumns"),
            index = 0,
            rendererKey = "musicResponsiveListItemFlexColumnRenderer"
        )
        val type = forcedType ?: resolveSearchResultType(renderer) ?: return null
        if (videoId.isBlank() || title.isBlank()) {
            return null
        }

        val metadata = parseSearchMetadata(renderer, type)
        val durationText = metadata.durationText.ifBlank { extractSearchDurationText(renderer) }
        return YouTubeMusicSearchResult(
            videoId = videoId,
            title = title,
            artist = metadata.artists.joinToString(" / ").ifBlank { "" },
            album = metadata.album,
            subtitle = extractColumnText(
                columns = renderer.optJSONArray("flexColumns"),
                index = 1,
                rendererKey = "musicResponsiveListItemFlexColumnRenderer"
            ),
            coverUrl = extractMusicThumbnailUrl(renderer.optJSONObject("thumbnail")),
            durationText = durationText,
            durationMs = parseDurationTextToMs(durationText),
            type = type
        )
    }

    private fun resolveSearchResultType(renderer: JSONObject): YouTubeMusicSearchResultType? {
        val musicVideoType = firstNonBlank(
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType"),
            renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType")
        ).uppercase(Locale.US)
        when {
            musicVideoType.contains("ATV") -> return YouTubeMusicSearchResultType.Song
            musicVideoType.contains("OMV") || musicVideoType.contains("UGC") -> {
                return YouTubeMusicSearchResultType.Video
            }
        }

        return when (normalizeSearchTypeToken(extractSearchMetadataParts(renderer).firstOrNull().orEmpty())) {
            "song", "songs", "歌曲", "曲" -> YouTubeMusicSearchResultType.Song
            "video", "videos", "视频", "mv" -> YouTubeMusicSearchResultType.Video
            else -> null
        }
    }

    private fun extractSearchMetadataParts(renderer: JSONObject): List<String> {
        return extractTextParts(
            renderer.optJSONArray("flexColumns")
                ?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        )
    }

    private fun parseSearchMetadata(
        renderer: JSONObject,
        type: YouTubeMusicSearchResultType
    ): ParsedYouTubeMusicSearchMetadata {
        val runs = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return ParsedYouTubeMusicSearchMetadata(
                artists = extractSearchMetadataParts(renderer)
                    .filterNot(::looksLikeDurationText)
                    .take(1),
                durationText = extractSearchDurationText(renderer)
            )

        val entries = mutableListOf<String>()
        var album = ""
        var durationText = ""
        for (index in 0 until runs.length()) {
            if (index % 2 == 1) {
                continue
            }
            val run = runs.optJSONObject(index) ?: continue
            val text = run.optString("text", "").trim()
            if (text.isBlank()) {
                continue
            }
            if (looksLikeSearchTypeLabel(text, type, index == 0 && runs.length() >= 3)) {
                continue
            }
            if (looksLikeDurationText(text)) {
                durationText = text
                continue
            }
            val browseId = run.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                .orEmpty()
            when {
                browseId.startsWith("MPRE") || browseId.contains("release_detail") -> album = text
                !looksLikeSearchStatText(text) -> entries += text
            }
        }

        if (album.isBlank() && entries.size >= 2) {
            album = entries.last()
            entries.removeLastOrNull()
        }

        return ParsedYouTubeMusicSearchMetadata(
            artists = entries,
            album = album,
            durationText = durationText
        )
    }

    private fun extractSearchDurationText(renderer: JSONObject): String {
        val fixedDuration = findDurationText(
            columns = renderer.optJSONArray("fixedColumns"),
            rendererKey = "musicResponsiveListItemFixedColumnRenderer"
        )
        if (fixedDuration.isNotBlank()) {
            return fixedDuration
        }
        return findDurationText(
            columns = renderer.optJSONArray("flexColumns"),
            rendererKey = "musicResponsiveListItemFlexColumnRenderer"
        )
    }

    private fun findDurationText(columns: JSONArray?, rendererKey: String): String {
        if (columns == null) {
            return ""
        }
        for (index in 0 until columns.length()) {
            val text = extractColumnText(columns, index, rendererKey)
            if (looksLikeDurationText(text)) {
                return text
            }
        }
        return ""
    }

    private fun looksLikeDurationText(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.contains(':')) {
            return false
        }
        val parts = trimmed.split(':')
        return parts.isNotEmpty() && parts.all { it.trim().toLongOrNull() != null }
    }

    private fun looksLikeSearchStatText(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.US)
        if (normalized.isBlank()) {
            return false
        }
        return normalized.contains("播放") ||
            normalized.contains("观看") ||
            normalized.contains("views") ||
            normalized.contains("view") ||
            normalized.contains("listeners") ||
            normalized.contains("listener") ||
            normalized.contains("monthly") ||
            normalized.contains("观众") ||
            normalized.contains("订阅者") ||
            normalized.contains("subscriber")
    }

    private fun normalizeSearchTypeToken(token: String): String {
        return token.trim()
            .lowercase(Locale.US)
            .replace(" ", "")
    }

    private fun looksLikeSearchTypeLabel(
        text: String,
        type: YouTubeMusicSearchResultType,
        canSkip: Boolean
    ): Boolean {
        if (!canSkip) {
            return false
        }
        return when (normalizeSearchTypeToken(text)) {
            "song", "songs", "歌曲", "曲" -> type == YouTubeMusicSearchResultType.Song
            "video", "videos", "视频", "mv" -> type == YouTubeMusicSearchResultType.Video
            else -> false
        }
    }

    private fun looksLikeHomeSongTypeLabel(text: String): Boolean {
        return when (normalizeSearchTypeToken(text)) {
            "song", "songs", "歌曲", "曲" -> true
            "video", "videos", "视频", "mv" -> true
            else -> false
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun extractContinuationToken(renderer: JSONObject?): String? {
        val continuations = renderer?.optJSONArray("continuations") ?: return null
        for (index in 0 until continuations.length()) {
            val token = continuations.optJSONObject(index)
                ?.optJSONObject("nextContinuationData")
                ?.optString("continuation")
                .orEmpty()
            if (token.isNotBlank()) {
                return token
            }
        }
        return null
    }

    private fun findRequired(source: String, vararg patterns: String): String {
        return findOptional(source, *patterns).ifBlank {
            throw IOException(
                "YouTube Music bootstrap parse failed: ${patterns.firstOrNull().orEmpty()}"
            )
        }
    }

    private fun findOptional(source: String, vararg patterns: String): String {
        return patterns.asSequence()
            .map { pattern ->
                Regex(pattern).find(source)?.groupValues?.getOrNull(1).orEmpty()
            }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseDataSyncId(dataSyncId: String): Pair<String, String> {
        if (dataSyncId.isBlank()) {
            return "" to ""
        }
        val (first, second) = dataSyncId.split("||", limit = 2).let { parts ->
            parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
        }
        return if (second.isNotBlank()) {
            first to second
        } else {
            "" to first
        }
    }

    private fun extractColumnText(columns: JSONArray?, index: Int, rendererKey: String): String {
        return extractText(
            columns?.optJSONObject(index)
                ?.optJSONObject(rendererKey)
                ?.optJSONObject("text")
        )
    }

    private fun extractTextParts(node: JSONObject?): List<String> {
        if (node == null) {
            return emptyList()
        }
        val runs = node.optJSONArray("runs")
        if (runs != null) {
            return buildList {
                for (index in 0 until runs.length()) {
                    val text = runs.optJSONObject(index)?.optString("text").orEmpty().trim()
                    if (text.isBlank()) {
                        continue
                    }
                    if (text.all { it == '•' || it == '·' || it == '|' }) {
                        continue
                    }
                    add(text)
                }
            }
        }
        return node.optString("simpleText", "")
            .split('•', '·', '|')
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun extractText(node: JSONObject?): String {
        if (node == null) {
            return ""
        }
        val runs = node.optJSONArray("runs")
        if (runs != null) {
            return buildString {
                for (index in 0 until runs.length()) {
                    append(runs.optJSONObject(index)?.optString("text").orEmpty())
                }
            }.trim()
        }
        return node.optString("simpleText", "").trim()
    }

    private fun extractMusicThumbnailUrl(node: JSONObject?): String {
        if (node == null) {
            return ""
        }
        val thumbnailContainer = when {
            node.has("musicThumbnailRenderer") -> node.optJSONObject("musicThumbnailRenderer")
            node.has("croppedSquareThumbnailRenderer") -> node.optJSONObject("croppedSquareThumbnailRenderer")
            else -> node
        }
        val thumbnails = thumbnailContainer?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: thumbnailContainer?.optJSONArray("thumbnails")
            ?: return ""
        if (thumbnails.length() == 0) {
            return ""
        }
        val rawUrl = thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
        return upgradeYouTubeThumbnailUrl(rawUrl)
    }

    fun parseLyricsBrowseId(root: JSONObject): String? {
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs")
            ?: return null
        for (index in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(index) ?: continue
            val tabRenderer = tab.optJSONObject("tabRenderer") ?: continue
            val endpoint = tabRenderer.optJSONObject("endpoint") ?: continue
            val browseId = endpoint.optJSONObject("browseEndpoint")
                ?.optString("browseId").orEmpty()
            if (browseId.startsWith("MPLYt")) {
                return browseId
            }
        }
        return null
    }

    fun parseLyrics(root: JSONObject): YouTubeMusicLyrics? {
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return null

        // 优先尝试解析带时间戳的歌词 (timedLyricsRenderer)
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            val timedRenderer = section.optJSONObject("musicDescriptionShelfRenderer")
            if (timedRenderer != null) {
                val lyricsText = extractText(timedRenderer.optJSONObject("description"))
                val source = extractText(timedRenderer.optJSONObject("footer"))
                if (lyricsText.isNotBlank()) {
                    return YouTubeMusicLyrics(
                        lyrics = lyricsText,
                        source = source
                    )
                }
            }
        }

        return null
    }

    private fun playlistIdFromBrowseId(browseId: String): String {
        return if (browseId.startsWith("VL")) {
            browseId.removePrefix("VL")
        } else {
            browseId
        }
    }
}

/**
 * 将 YouTube Music 缩略图 URL 升级为完整尺寸
 * YouTube 缩略图 URL 通常以 `=w60-h60-...` 结尾来限制尺寸，
 * 此函数将其替换为 `=w1200-h1200` 以获取高清封面
 */
fun upgradeYouTubeThumbnailUrl(url: String): String {
    if (url.isBlank()) return url
    // lh3.googleusercontent.com 和 yt3.ggpht.com 样式的 URL 使用 = 参数来控制尺寸
    val sizeParamRegex = Regex("=w\\d+(-h\\d+)?(-[a-zA-Z0-9-]+)*$")
    return if (sizeParamRegex.containsMatchIn(url)) {
        url.replace(sizeParamRegex, "=w1200-h1200")
    } else if (url.contains("lh3.googleusercontent.com") || url.contains("yt3.ggpht.com")) {
        // 没有尺寸参数但属于 Google 图片服务的 URL，附加尺寸参数
        if (url.contains('=')) url else "$url=w1200-h1200"
    } else {
        url
    }
}

internal object YouTubeMusicPlayerParser {
    fun requirePlayable(root: JSONObject) {
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty().trim()
        if (status.isBlank() || status == "OK") {
            return
        }
        val reason = buildList {
            playability?.optString("reason")?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            val messages = playability?.optJSONArray("messages")
            if (messages != null) {
                for (index in 0 until messages.length()) {
                    messages.optString(index)?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.distinct().joinToString(" | ")
        val suffix = reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        throw IOException("YouTube Music player not playable ($status)$suffix")
    }

    fun parsePlayableAudio(root: JSONObject): YouTubeMusicPlayableAudio? {
        val adaptiveFormats = root.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
            ?: return null
        val fallbackDurationMs = root.optJSONObject("videoDetails")
            ?.optString("lengthSeconds")
            ?.toLongOrNull()
            ?.times(1000L)
            ?: 0L

        return (0 until adaptiveFormats.length())
            .asSequence()
            .mapNotNull { adaptiveFormats.optJSONObject(it) }
            .filter { format ->
                format.optString("mimeType")
                    .substringBefore(';')
                    .trim()
                    .startsWith("audio/")
            }
            .mapNotNull { format ->
                val resolvedUrl = extractPlayableUrl(format) ?: return@mapNotNull null
                YouTubeMusicPlayableAudio(
                    url = resolvedUrl,
                    durationMs = format.optString("approxDurationMs").toLongOrNull()
                        ?: fallbackDurationMs,
                    mimeType = format.optString("mimeType").ifBlank { null }?.substringBefore(';'),
                    contentLength = format.optString("contentLength").toLongOrNull(),
                    bitrate = format.optInt("bitrate", 0)
                )
            }
            .sortedWith(
                compareByDescending<YouTubeMusicPlayableAudio> { it.contentLength != null }
                    .thenByDescending { it.bitrate }
                    .thenByDescending { it.contentLength ?: -1L }
                    .thenByDescending { it.durationMs }
            )
            .firstOrNull()
    }

    private fun extractPlayableUrl(format: JSONObject): String? {
        val directUrl = format.optString("url").trim()
        if (directUrl.isNotBlank()) {
            return directUrl
        }

        val signatureCipher = format.optString("signatureCipher")
            .ifBlank { format.optString("cipher") }
            .trim()
        if (signatureCipher.isBlank()) {
            return null
        }

        val fields = signatureCipher
            .split('&')
            .mapNotNull { segment ->
                val delimiterIndex = segment.indexOf('=')
                if (delimiterIndex <= 0) {
                    null
                } else {
                    val key = segment.substring(0, delimiterIndex)
                    val value = URLDecoder.decode(
                        segment.substring(delimiterIndex + 1),
                        Charsets.UTF_8.name()
                    )
                    key to value
                }
            }
            .toMap()

        // 没有签名参数时可以直接复用 url；否则交给 NewPipe 兜底解签
        if (!fields["s"].isNullOrBlank()) {
            return null
        }
        return fields["url"]?.takeIf { it.isNotBlank() }
    }
}

class YouTubeMusicClient(
    private val authRepo: YouTubeAuthRepository,
    private val okHttpClient: OkHttpClient,
    private val authAutoRefreshManager: YouTubeAuthAutoRefreshManager? = null
) {
    @Volatile
    private var bootstrapCache: YouTubeMusicBootstrapConfig? = null

    suspend fun debugBootstrap(
        hl: String = "",
        gl: String = "",
        forceRefresh: Boolean = false
    ): YouTubeMusicDebugProbeResult = withContext(Dispatchers.IO) {
        val locale = resolveDebugLocale(hl = hl, gl = gl)
        val bootstrap = bootstrap(forceRefresh = forceRefresh)
        val authHealth = authRepo.getAuthHealthOnce()
        val raw = JSONObject()
            .put("probe", "bootstrap")
            .put("locale", debugLocaleJson(locale))
            .put("auth", debugAuthJson())
            .put("bootstrap", debugBootstrapJson(bootstrap))
            .put(
                "health",
                JSONObject()
                    .put("state", authHealth.state.name)
                    .put("activeCookieKeys", JSONArray(authHealth.activeCookieKeys))
                    .put("loginCookieKeys", JSONArray(authHealth.loginCookieKeys))
            )
        YouTubeMusicDebugProbeResult(
            summary = "bootstrap ready, clientVersion=${bootstrap.webRemixClientVersion}, sessionIndex=${bootstrap.sessionIndex}",
            rawJson = raw.toString(2)
        )
    }

    suspend fun debugHomeFeedRaw(
        hl: String = "",
        gl: String = "",
        forceRefresh: Boolean = false
    ): YouTubeMusicDebugProbeResult = withContext(Dispatchers.IO) {
        val locale = resolveDebugLocale(hl = hl, gl = gl)
        val bootstrap = bootstrap(forceRefresh = forceRefresh)
        val payload = JSONObject().put("browseId", "FEmusic_home")
        val root = postMusicBrowse(
            bootstrap = bootstrap,
            payload = payload,
            requestLocale = locale
        )
        val shelves = YouTubeMusicParser.parseHomeShelfPages(root)
        val parsed = JSONObject()
            .put("shelfCount", shelves.size)
            .put(
                "shelves",
                JSONArray().apply {
                    shelves.forEach { shelf ->
                        put(
                            JSONObject()
                                .put("title", shelf.title)
                                .put("itemCount", shelf.items.size)
                                .put("continuation", shelf.continuation)
                        )
                    }
                }
            )
        YouTubeMusicDebugProbeResult(
            summary = "home feed ok, shelves=${shelves.size}",
            rawJson = buildDebugEnvelope(
                probe = "home_feed",
                endpoint = "browse",
                requestUrl = musicBrowseUrl(bootstrap),
                requestPayload = payload,
                requestLocale = locale,
                bootstrap = bootstrap,
                response = root,
                parsed = parsed
            ).toString(2)
        )
    }

    suspend fun debugLibraryPlaylistsRaw(
        hl: String = "",
        gl: String = "",
        forceRefresh: Boolean = false
    ): YouTubeMusicDebugProbeResult = withContext(Dispatchers.IO) {
        val locale = resolveDebugLocale(hl = hl, gl = gl)
        val bootstrap = bootstrap(forceRefresh = forceRefresh)
        val payload = JSONObject().put("browseId", YOUTUBE_MUSIC_BROWSE_ID_LIBRARY_PLAYLISTS)
        val root = postMusicBrowse(
            bootstrap = bootstrap,
            payload = payload,
            requestLocale = locale
        )
        val playlists = YouTubeMusicParser.parseLibraryPlaylists(root)
        val parsed = JSONObject()
            .put("playlistCount", playlists.size)
            .put(
                "playlists",
                JSONArray().apply {
                    playlists.forEach { playlist ->
                        put(
                            JSONObject()
                                .put("title", playlist.title)
                                .put("browseId", playlist.browseId)
                                .put("playlistId", playlist.playlistId)
                                .put("trackCount", playlist.trackCount)
                        )
                    }
                }
            )
        YouTubeMusicDebugProbeResult(
            summary = "library playlists ok, playlists=${playlists.size}",
            rawJson = buildDebugEnvelope(
                probe = "library_playlists",
                endpoint = "browse",
                requestUrl = musicBrowseUrl(bootstrap),
                requestPayload = payload,
                requestLocale = locale,
                bootstrap = bootstrap,
                response = root,
                parsed = parsed
            ).toString(2)
        )
    }

    suspend fun debugBrowseRaw(
        browseId: String,
        hl: String = "",
        gl: String = "",
        forceRefresh: Boolean = false
    ): YouTubeMusicDebugProbeResult = withContext(Dispatchers.IO) {
        val locale = resolveDebugLocale(hl = hl, gl = gl)
        val bootstrap = bootstrap(forceRefresh = forceRefresh)
        val payload = JSONObject().put("browseId", browseId)
        val root = postMusicBrowse(
            bootstrap = bootstrap,
            payload = payload,
            requestLocale = locale
        )
        val parsed = JSONObject()
            .put("hasContents", root.optJSONObject("contents") != null)
            .put("hasContinuationContents", root.optJSONObject("continuationContents") != null)
            .put("topLevelKeys", JSONArray(root.keys().asSequence().toList()))
        YouTubeMusicDebugProbeResult(
            summary = "browse ok, browseId=$browseId",
            rawJson = buildDebugEnvelope(
                probe = "browse",
                endpoint = "browse",
                requestUrl = musicBrowseUrl(bootstrap),
                requestPayload = payload,
                requestLocale = locale,
                bootstrap = bootstrap,
                response = root,
                parsed = parsed
            ).toString(2)
        )
    }

    suspend fun debugPlayerRaw(
        videoId: String,
        hl: String = "",
        gl: String = "",
        forceRefresh: Boolean = false
    ): YouTubeMusicDebugProbeResult = withContext(Dispatchers.IO) {
        val locale = resolveDebugLocale(hl = hl, gl = gl)
        val bootstrap = bootstrap(forceRefresh = forceRefresh)
        val root = postMusicPlayer(
            bootstrap = bootstrap,
            videoId = videoId,
            requestLocale = locale
        )
        val playability = root.optJSONObject("playabilityStatus")
        val adaptiveFormats = root.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
        val playableAudio = runCatching { YouTubeMusicPlayerParser.parsePlayableAudio(root) }.getOrNull()
        val parsed = JSONObject()
            .put("playabilityStatus", playability?.optString("status").orEmpty())
            .put("playabilityReason", playability?.optString("reason").orEmpty())
            .put("adaptiveFormatCount", adaptiveFormats?.length() ?: 0)
            .put(
                "selectedPlayableAudio",
                playableAudio?.let {
                    JSONObject()
                        .put("url", it.url)
                        .put("durationMs", it.durationMs)
                        .put("mimeType", it.mimeType)
                        .put("contentLength", it.contentLength)
                        .put("bitrate", it.bitrate)
                } ?: JSONObject.NULL
            )
        YouTubeMusicDebugProbeResult(
            summary = "player ok, playability=${playability?.optString("status").orEmpty()}, formats=${adaptiveFormats?.length() ?: 0}",
            rawJson = buildDebugEnvelope(
                probe = "player",
                endpoint = "player",
                requestUrl = musicPlayerUrl(bootstrap),
                requestPayload = JSONObject()
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true),
                requestLocale = locale,
                bootstrap = bootstrap,
                response = root,
                parsed = parsed
            ).toString(2)
        )
    }

    suspend fun debugLyricsRaw(
        videoId: String,
        hl: String = "",
        gl: String = "",
        forceRefresh: Boolean = false
    ): YouTubeMusicDebugProbeResult = withContext(Dispatchers.IO) {
        val locale = resolveDebugLocale(hl = hl, gl = gl)
        val bootstrap = bootstrap(forceRefresh = forceRefresh)
        val nextPayload = JSONObject()
            .put("videoId", videoId)
            .put("isAudioOnly", true)
        val nextRoot = postMusicNext(
            bootstrap = bootstrap,
            videoId = videoId,
            requestLocale = locale
        )
        val lyricsBrowseId = YouTubeMusicParser.parseLyricsBrowseId(nextRoot)
        val browsePayload = lyricsBrowseId?.let { JSONObject().put("browseId", it) }
        val browseRoot = browsePayload?.let {
            postMusicBrowse(
                bootstrap = bootstrap,
                payload = it,
                requestLocale = locale
            )
        }
        val lyrics = browseRoot?.let(YouTubeMusicParser::parseLyrics)
        val parsed = JSONObject()
            .put("lyricsBrowseId", lyricsBrowseId ?: JSONObject.NULL)
            .put("lyricsFound", lyrics != null)
            .put("lyricsLength", lyrics?.lyrics?.length ?: 0)
            .put("lyricsSource", lyrics?.source ?: "")
        val raw = JSONObject()
            .put("probe", "lyrics")
            .put("auth", debugAuthJson())
            .put("bootstrap", debugBootstrapJson(bootstrap))
            .put("locale", debugLocaleJson(locale))
            .put(
                "requests",
                JSONObject()
                    .put(
                        "next",
                        JSONObject()
                            .put("url", musicNextUrl(bootstrap))
                            .put("payload", nextPayload)
                    )
                    .put(
                        "browse",
                        browsePayload?.let {
                            JSONObject()
                                .put("url", musicBrowseUrl(bootstrap))
                                .put("payload", it)
                        } ?: JSONObject.NULL
                    )
            )
            .put(
                "responses",
                JSONObject()
                    .put("next", nextRoot)
                    .put("browse", browseRoot ?: JSONObject.NULL)
            )
            .put("parsed", parsed)
        YouTubeMusicDebugProbeResult(
            summary = if (lyrics != null) {
                "lyrics ok, browseId=$lyricsBrowseId, chars=${lyrics.lyrics.length}"
            } else {
                "lyrics missing, browseId=${lyricsBrowseId ?: "none"}"
            },
            rawJson = raw.toString(2)
        )
    }

    suspend fun search(
        query: String,
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
    ): List<YouTubeMusicSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext emptyList()
        }
        authAutoRefreshManager?.refreshIfNeeded(reason = "search", force = false)
        val requestedLimit = limit.coerceAtLeast(1)
        var bootstrap = bootstrap()
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        val items = mutableListOf<YouTubeMusicSearchResult>()
        val seenVideoIds = linkedSetOf<String>()
        var continuation: String? = null
        var page = 0

        while (page < YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT && items.size < requestedLimit) {
            val payload = JSONObject()
                .put("query", query)
                .put("params", YouTubeMusicSearchParams.songs())
            if (!continuation.isNullOrBlank()) {
                payload.put("continuation", continuation)
            }
            val root = try {
                val response = postMusicSearchWithRetry(
                    bootstrap = bootstrap,
                    payload = payload,
                    preferredLocale = requestLocale,
                    expectSongShelf = continuation.isNullOrBlank()
                )
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
            } catch (error: IOException) {
                if (page == 0) {
                    throw error
                }
                break
            }
            YouTubeMusicParser.parseSongSearchResults(
                root = root,
                limit = requestedLimit - items.size
            ).forEach { result ->
                if (seenVideoIds.add(result.videoId)) {
                    items += result
                }
            }
            continuation = YouTubeMusicParser.extractSearchContinuation(root)
            if (continuation.isNullOrBlank()) {
                break
            }
            page++
        }

        items.take(requestedLimit)
    }

    suspend fun getLibraryPlaylists(): List<YouTubeMusicLibraryPlaylist> = withContext(Dispatchers.IO) {
        authAutoRefreshManager?.refreshIfNeeded(reason = "library_playlists", force = false)
        var bootstrap = bootstrap()
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        val items = mutableListOf<YouTubeMusicLibraryPlaylist>()
        var continuation: String? = null
        var page = 0

        while (page < YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT) {
            val payload = if (continuation.isNullOrBlank()) {
                JSONObject().put("browseId", YOUTUBE_MUSIC_BROWSE_ID_LIBRARY_PLAYLISTS)
            } else {
                JSONObject().put("continuation", continuation)
            }
            val root = try {
                val response = postMusicBrowseWithRetry(bootstrap, payload, requestLocale)
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
            } catch (error: IOException) {
                if (page == 0) {
                    throw error
                }
                break
            }
            items += YouTubeMusicParser.parseLibraryPlaylists(root)
            continuation = YouTubeMusicParser.extractLibraryContinuation(root)
            if (continuation.isNullOrBlank()) {
                break
            }
            page++
        }

        val playlists = items.distinctBy { it.browseId }.toMutableList()
        playlists.indices.forEach { index ->
            if (playlists[index].trackCount != null) {
                return@forEach
            }
            val resolvedTrackCount = try {
                val response = resolvePlaylistTrackCount(
                    bootstrap = bootstrap,
                    browseId = playlists[index].browseId,
                    requestLocale = requestLocale
                )
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
            } catch (_: IOException) {
                null
            } ?: return@forEach
            playlists[index] = playlists[index].copy(
                trackCount = YouTubeMusicParser.parsePlaylistTrackCount(resolvedTrackCount)
            )
        }
        if (playlists.isEmpty() && authRepo.getAuthOnce().hasLoginCookies()) {
            val refreshResult = authAutoRefreshManager?.refreshIfNeeded(
                reason = "library_playlists_empty",
                force = true
            )
            if (refreshResult?.refreshed == true) {
                bootstrapCache = null
                return@withContext getLibraryPlaylists()
            }
        }
        playlists
    }

    /** 获取 YouTube Music 首页推荐 */
    suspend fun getHomeFeed(): List<YouTubeMusicHomeShelf> = withContext(Dispatchers.IO) {
        authAutoRefreshManager?.refreshIfNeeded(reason = "home_feed", force = false)
        var bootstrap = bootstrap()
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        val result = mutableListOf<YouTubeMusicHomeShelf>()
        var continuation: String? = null
        var page = 0

        while (page < YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT) {
            val payload = if (continuation.isNullOrBlank()) {
                JSONObject().put("browseId", "FEmusic_home")
            } else {
                JSONObject().put("continuation", continuation)
            }
            val response = postMusicBrowseWithRetry(bootstrap, payload, requestLocale)
            bootstrap = response.bootstrap
            requestLocale = response.requestLocale

            val parsedShelves = YouTubeMusicParser.parseHomeShelfPages(response.root)
            parsedShelves.forEach { parsedShelf ->
                var shelfContinuation = parsedShelf.continuation
                var shelfPage = 0
                val maxItems = if (parsedShelf.items.all { it.videoId.isBlank() }) {
                    YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT
                } else {
                    Int.MAX_VALUE
                }
                val items = parsedShelf.items.toMutableList()

                while (
                    !shelfContinuation.isNullOrBlank() &&
                    shelfPage < YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT &&
                    items.size < maxItems
                ) {
                    val shelfResponse = try {
                        postMusicBrowseWithRetry(
                            bootstrap = bootstrap,
                            payload = JSONObject().put("continuation", shelfContinuation),
                            preferredLocale = requestLocale
                        )
                    } catch (_: IOException) {
                        break
                    }
                    bootstrap = shelfResponse.bootstrap
                    requestLocale = shelfResponse.requestLocale
                    items += YouTubeMusicParser.parseHomeShelfContinuationItems(shelfResponse.root)
                    shelfContinuation = YouTubeMusicParser.extractHomeShelfContinuation(shelfResponse.root)
                    shelfPage++
                }

                val distinctItems = items.distinctBy { item ->
                    listOf(item.title, item.browseId, item.videoId).joinToString("#")
                }
                if (distinctItems.isNotEmpty()) {
                    result += YouTubeMusicHomeShelf(
                        title = parsedShelf.title,
                        items = distinctItems.take(maxItems)
                    )
                }
            }

            continuation = YouTubeMusicParser.extractHomeContinuation(response.root)
            if (continuation.isNullOrBlank()) {
                break
            }
            page++
        }

        if (result.isEmpty() && authRepo.getAuthOnce().hasLoginCookies()) {
            val refreshResult = authAutoRefreshManager?.refreshIfNeeded(
                reason = "home_feed_empty",
                force = true
            )
            if (refreshResult?.refreshed == true) {
                bootstrapCache = null
                return@withContext getHomeFeed()
            }
        }
        result
    }

    suspend fun getPlaylistDetail(
        browseId: String,
        fallbackTitle: String = "",
        fallbackSubtitle: String = "",
        fallbackCoverUrl: String = ""
    ): YouTubeMusicPlaylistDetail = withContext(Dispatchers.IO) {
        authAutoRefreshManager?.refreshIfNeeded(reason = "playlist_detail", force = false)
        var bootstrap = bootstrap()
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        var detail: YouTubeMusicPlaylistDetail? = null
        val tracks = mutableListOf<YouTubeMusicPlaylistTrack>()
        var continuation: String? = null
        var page = 0

        while (page < YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT) {
            val payload = if (continuation.isNullOrBlank()) {
                JSONObject().put("browseId", browseId)
            } else {
                JSONObject().put("continuation", continuation)
            }
            val root = try {
                val response = postMusicBrowseWithRetry(bootstrap, payload, requestLocale)
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
            } catch (error: IOException) {
                if (page == 0) {
                    throw error
                }
                break
            }
            if (detail == null) {
                detail = YouTubeMusicParser.parsePlaylistDetail(
                    root = root,
                    browseId = browseId,
                    fallbackTitle = fallbackTitle,
                    fallbackSubtitle = fallbackSubtitle,
                    fallbackCoverUrl = fallbackCoverUrl
                )
            }
            tracks += YouTubeMusicParser.parsePlaylistTracks(root)
            continuation = YouTubeMusicParser.extractPlaylistContinuation(root)
            if (continuation.isNullOrBlank()) {
                break
            }
            page++
        }

        val baseDetail = detail ?: YouTubeMusicPlaylistDetail(
            browseId = browseId,
            playlistId = if (browseId.startsWith("VL")) browseId.removePrefix("VL") else browseId,
            title = fallbackTitle,
            subtitle = fallbackSubtitle,
            coverUrl = fallbackCoverUrl,
            trackCount = null,
            tracks = emptyList()
        )
        val distinctTracks = tracks.distinctBy { it.videoId }
        baseDetail.copy(
            trackCount = baseDetail.trackCount ?: distinctTracks.size.takeIf { it > 0 },
            tracks = distinctTracks
        )
    }

    suspend fun getPlayableAudio(videoId: String): YouTubeMusicPlayableAudio = withContext(Dispatchers.IO) {
        authAutoRefreshManager?.refreshIfNeeded(reason = "playable_audio", force = false)
        var bootstrap = bootstrap()
        var lastError: IOException? = null

        for (requestLocale in YouTubeMusicLocaleResolver.requestCandidates()) {
            for (attempt in 0 until YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
                try {
                    val root = postMusicPlayer(
                        bootstrap = bootstrap,
                        videoId = videoId,
                        requestLocale = requestLocale
                    )
                    YouTubeMusicPlayerParser.requirePlayable(root)
                    return@withContext YouTubeMusicPlayerParser.parsePlayableAudio(root)
                        ?: throw IOException("YouTube Music player missing playable audio formats")
                } catch (error: IOException) {
                    lastError = error
                    if (attempt == YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS - 1) {
                        break
                    }
                    bootstrapCache = null
                    bootstrap = bootstrap(forceRefresh = true)
                }
            }
        }

        throw lastError ?: IOException("YouTube Music player request failed")
    }

    suspend fun getLyrics(videoId: String): YouTubeMusicLyrics? = withContext(Dispatchers.IO) {
        authAutoRefreshManager?.refreshIfNeeded(reason = "lyrics", force = false)
        val bootstrap = bootstrap()
        val requestLocale = YouTubeMusicLocaleResolver.preferred()

        // 第一步：调用 next 端点获取歌词 browseId
        val nextRoot = postMusicNext(bootstrap, videoId, requestLocale)
        val lyricsBrowseId = YouTubeMusicParser.parseLyricsBrowseId(nextRoot)
            ?: return@withContext null

        // 第二步：调用 browse 端点获取歌词内容
        val browseRoot = postMusicBrowse(
            bootstrap = bootstrap,
            payload = JSONObject().put("browseId", lyricsBrowseId),
            requestLocale = requestLocale
        )
        YouTubeMusicParser.parseLyrics(browseRoot)
    }

    fun clearBootstrapCache() {
        bootstrapCache = null
    }

    private fun resolveDebugLocale(
        hl: String,
        gl: String
    ): YouTubeMusicRequestLocale {
        val preferred = YouTubeMusicLocaleResolver.preferred()
        val resolvedHl = hl.trim().ifBlank { preferred.hl }
        val resolvedGl = gl.trim().ifBlank { preferred.gl }.uppercase(Locale.US)
        return YouTubeMusicRequestLocale(
            hl = resolvedHl,
            gl = resolvedGl
        )
    }

    private fun debugAuthJson(): JSONObject {
        val health = authRepo.getAuthHealthOnce()
        val auth = authRepo.getAuthOnce().normalized()
        return JSONObject()
            .put("state", health.state.name)
            .put("activeCookieKeys", JSONArray(health.activeCookieKeys))
            .put("loginCookieKeys", JSONArray(health.loginCookieKeys))
            .put("cookieCount", auth.cookies.size)
            .put("hasCookieHeader", auth.cookieHeader.isNotBlank())
            .put("origin", auth.origin)
            .put("xGoogAuthUser", auth.xGoogAuthUser)
            .put("userAgent", auth.resolveRequestUserAgent())
    }

    private fun debugBootstrapJson(bootstrap: YouTubeMusicBootstrapConfig): JSONObject {
        return JSONObject()
            .put("apiKey", bootstrap.apiKey)
            .put("webRemixClientVersion", bootstrap.webRemixClientVersion)
            .put("visitorData", bootstrap.visitorData)
            .put("sessionIndex", bootstrap.sessionIndex)
            .put("loggedIn", bootstrap.loggedIn)
            .put("userSessionId", bootstrap.userSessionId)
            .put("webUserAgent", bootstrap.webUserAgent)
            .put("cookieHeaderLength", bootstrap.cookieHeader.length)
            .put("fetchedAtMs", bootstrap.fetchedAtMs)
    }

    private fun debugLocaleJson(locale: YouTubeMusicRequestLocale): JSONObject {
        return JSONObject()
            .put("hl", locale.hl)
            .put("gl", locale.gl)
            .put("acceptLanguage", locale.acceptLanguage)
    }

    private fun buildDebugEnvelope(
        probe: String,
        endpoint: String,
        requestUrl: String,
        requestPayload: JSONObject,
        requestLocale: YouTubeMusicRequestLocale,
        bootstrap: YouTubeMusicBootstrapConfig,
        response: JSONObject,
        parsed: JSONObject
    ): JSONObject {
        return JSONObject()
            .put("probe", probe)
            .put("auth", debugAuthJson())
            .put("bootstrap", debugBootstrapJson(bootstrap))
            .put("request", JSONObject()
                .put("endpoint", endpoint)
                .put("url", requestUrl)
                .put("locale", debugLocaleJson(requestLocale))
                .put("payload", requestPayload)
            )
            .put("response", response)
            .put("parsed", parsed)
    }

    private fun musicBrowseUrl(bootstrap: YouTubeMusicBootstrapConfig): String {
        return "$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/browse?prettyPrint=false&key=${bootstrap.apiKey}"
    }

    private fun musicPlayerUrl(bootstrap: YouTubeMusicBootstrapConfig): String {
        return "$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/player?prettyPrint=false&key=${bootstrap.apiKey}"
    }

    private fun musicNextUrl(bootstrap: YouTubeMusicBootstrapConfig): String {
        return "$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/next?prettyPrint=false&key=${bootstrap.apiKey}"
    }

    private fun musicSearchUrl(
        bootstrap: YouTubeMusicBootstrapConfig,
        continuation: String? = null
    ): String {
        return buildString {
            append("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/search?prettyPrint=false&key=${bootstrap.apiKey}")
            continuation?.takeIf { it.isNotBlank() }?.let {
                val encodedContinuation = URLEncoder.encode(it, Charsets.UTF_8.name())
                append("&ctoken=")
                append(encodedContinuation)
                append("&continuation=")
                append(encodedContinuation)
            }
        }
    }

    private suspend fun bootstrap(forceRefresh: Boolean = false): YouTubeMusicBootstrapConfig {
        authAutoRefreshManager?.refreshIfNeeded(
            reason = "music_bootstrap",
            force = forceRefresh
        )
        val auth = authRepo.getAuthOnce().normalized()
        val authHealth = authRepo.getAuthHealthOnce()
        val cookieHeader = if (authHealth.activeCookieKeys.isEmpty()) {
            ""
        } else {
            auth.effectiveCookieHeader().trim()
        }
        val cacheUserAgent = auth.resolveRequestUserAgent().ifBlank { YOUTUBE_MUSIC_DEFAULT_WEB_UA }
        val authFingerprint = auth.buildAuthCacheFingerprint(
            userAgent = cacheUserAgent,
            origin = YOUTUBE_MUSIC_MUSIC_ORIGIN
        )

        val cached = bootstrapCache
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            cached != null &&
            cached.authFingerprint == authFingerprint &&
            now - cached.fetchedAtMs < YOUTUBE_MUSIC_BOOTSTRAP_TTL_MS
        ) {
            return cached
        }

        var workingAuth = auth
        var workingCookieHeader = cookieHeader
        var userAgent = cacheUserAgent
        val requestLocale = YouTubeMusicLocaleResolver.preferred()
        fun fetchHomeHtml(): String {
            return executeText(
                Request.Builder()
                    .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
                    .applyCookieHeader(workingCookieHeader)
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", requestLocale.acceptLanguage)
                    .build()
            )
        }
        suspend fun refreshWorkingAuth(reason: String) {
            authAutoRefreshManager?.refreshIfNeeded(
                reason = reason,
                force = true
            )
            workingAuth = authRepo.getAuthOnce().normalized()
            val refreshedHealth = authRepo.getAuthHealthOnce()
            workingCookieHeader = if (refreshedHealth.activeCookieKeys.isEmpty()) {
                ""
            } else {
                workingAuth.effectiveCookieHeader().trim()
            }
            userAgent = workingAuth.resolveRequestUserAgent().ifBlank { YOUTUBE_MUSIC_DEFAULT_WEB_UA }
        }
        val homeHtml = try {
            fetchHomeHtml()
        } catch (error: IOException) {
            if (isYouTubeAuthRecoverableFailure(error)) {
                refreshWorkingAuth("music_bootstrap_http_recoverable")
                fetchHomeHtml()
            } else {
                throw error
            }
        }
        val parsedConfig = try {
            YouTubeMusicParser.parseBootstrapConfig(
                html = homeHtml,
                cookieHeader = workingCookieHeader,
                userAgent = userAgent
            )
        } catch (error: IOException) {
            if (workingCookieHeader.isBlank()) {
                throw error
            }
            refreshWorkingAuth("music_bootstrap_parse_recoverable")
            YouTubeMusicParser.parseBootstrapConfig(
                html = fetchHomeHtml(),
                cookieHeader = workingCookieHeader,
                userAgent = userAgent
            )
        }
        val resolvedFingerprint = workingAuth.buildAuthCacheFingerprint(
            userAgent = userAgent,
            origin = YOUTUBE_MUSIC_MUSIC_ORIGIN
        )
        return parsedConfig.copy(
            sessionIndex = workingAuth.resolveXGoogAuthUser(
                fallback = parsedConfig.sessionIndex
            ),
            authFingerprint = resolvedFingerprint
        ).also { bootstrapCache = it }
    }

    private fun postMusicBrowse(
        bootstrap: YouTubeMusicBootstrapConfig,
        payload: JSONObject,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val body = JSONObject().put("context", buildMusicContext(bootstrap, requestLocale))
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            body.put(key, payload.get(key))
        }

        val requestHeaders = buildMusicInnertubeRequestHeaders(
            bootstrap = bootstrap,
            requestLocale = requestLocale,
            includeVisitorId = false
        )
        return executeJson(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/browse?prettyPrint=false&key=${bootstrap.apiKey}")
                .apply {
                    requestHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                }
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    private fun postMusicSearch(
        bootstrap: YouTubeMusicBootstrapConfig,
        payload: JSONObject,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val body = JSONObject().put("context", buildMusicContext(bootstrap, requestLocale))
        copyJsonFields(from = payload, to = body)

        val requestHeaders = buildMusicInnertubeRequestHeaders(
            bootstrap = bootstrap,
            requestLocale = requestLocale,
            includeVisitorId = true
        )
        return executeJson(
            Request.Builder()
                .url(
                    musicSearchUrl(
                        bootstrap = bootstrap,
                        continuation = payload.optString("continuation").ifBlank { null }
                    )
                )
                .apply {
                    requestHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                }
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    private fun postMusicPlayer(
        bootstrap: YouTubeMusicBootstrapConfig,
        videoId: String,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val body = JSONObject()
            .put("context", buildMusicContext(bootstrap, requestLocale))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val requestHeaders = buildMusicInnertubeRequestHeaders(
            bootstrap = bootstrap,
            requestLocale = requestLocale,
            includeVisitorId = true
        )
        return executeJson(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/player?prettyPrint=false&key=${bootstrap.apiKey}")
                .apply {
                    requestHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                }
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    private fun postMusicNext(
        bootstrap: YouTubeMusicBootstrapConfig,
        videoId: String,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val body = JSONObject()
            .put("context", buildMusicContext(bootstrap, requestLocale))
            .put("videoId", videoId)
            .put("isAudioOnly", true)

        val requestHeaders = buildMusicInnertubeRequestHeaders(
            bootstrap = bootstrap,
            requestLocale = requestLocale,
            includeVisitorId = false
        )
        return executeJson(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/next?prettyPrint=false&key=${bootstrap.apiKey}")
                .apply {
                    requestHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                }
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    private fun buildMusicInnertubeRequestHeaders(
        bootstrap: YouTubeMusicBootstrapConfig,
        requestLocale: YouTubeMusicRequestLocale,
        includeVisitorId: Boolean
    ): Map<String, String> {
        val auth = authRepo.getAuthOnce().normalized()
        val headers = auth.buildYouTubeInnertubeRequestHeaders(
            original = linkedMapOf(
                "Cookie" to bootstrap.cookieHeader,
                "User-Agent" to bootstrap.webUserAgent,
                "Accept-Language" to requestLocale.acceptLanguage,
                "Content-Type" to "application/json",
                "X-Goog-AuthUser" to bootstrap.sessionIndex,
                "X-YouTube-Client-Name" to YOUTUBE_MUSIC_CLIENT_NAME_NUM_WEB_REMIX,
                "X-YouTube-Client-Version" to bootstrap.webRemixClientVersion
            ),
            authorizationOrigin = YOUTUBE_MUSIC_MUSIC_ORIGIN,
            includeAuthorization = true,
            userSessionId = bootstrap.userSessionId.takeIf { bootstrap.loggedIn }.orEmpty()
        )
        return LinkedHashMap(headers).apply {
            put("Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
            put("X-Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
            put("Referer", "$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
            if (includeVisitorId) {
                put("X-Goog-Visitor-Id", bootstrap.visitorData)
            }
        }
    }

    private suspend fun resolvePlaylistTrackCount(
        bootstrap: YouTubeMusicBootstrapConfig,
        browseId: String,
        requestLocale: YouTubeMusicRequestLocale
    ): YouTubeMusicBrowseResponse {
        if (browseId.isBlank()) {
            return YouTubeMusicBrowseResponse(
                bootstrap = bootstrap,
                root = JSONObject(),
                requestLocale = requestLocale
            )
        }
        return postMusicBrowseWithRetry(
            bootstrap = bootstrap,
            payload = JSONObject().put("browseId", browseId),
            preferredLocale = requestLocale
        )
    }

    private suspend fun postMusicBrowseWithRetry(
        bootstrap: YouTubeMusicBootstrapConfig,
        payload: JSONObject,
        preferredLocale: YouTubeMusicRequestLocale
    ): YouTubeMusicBrowseResponse {
        var activeBootstrap = bootstrap
        var lastError: IOException? = null
        for (requestLocale in YouTubeMusicLocaleResolver.requestCandidates(preferredLocale)) {
            for (attempt in 0 until YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
                try {
                    val root = postMusicBrowse(
                        bootstrap = activeBootstrap,
                        payload = payload,
                        requestLocale = requestLocale
                    )
                    // 某些地区/语言组合会返回只有 microformat 的空壳 browse，需要切到通用 locale 重试
                    if (YouTubeMusicLocaleResolver.shouldRetryWithSafeFallback(payload, root)) {
                        lastError = IOException(
                            "YouTube Music browse response missing contents for ${requestLocale.hl}/${requestLocale.gl}"
                        )
                        break
                    }
                    return YouTubeMusicBrowseResponse(
                        bootstrap = activeBootstrap,
                        root = root,
                        requestLocale = requestLocale
                    )
                } catch (error: IOException) {
                    lastError = error
                    if (attempt == YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS - 1) {
                        break
                    }
                    if (isYouTubeAuthRecoverableFailure(error)) {
                        authAutoRefreshManager?.refreshIfNeeded(
                            reason = "browse_http_recoverable",
                            force = true
                        )
                    }
                    bootstrapCache = null
                    activeBootstrap = bootstrap(forceRefresh = true)
                }
            }
        }
        throw lastError ?: IOException("YouTube Music request failed")
    }

    private suspend fun postMusicSearchWithRetry(
        bootstrap: YouTubeMusicBootstrapConfig,
        payload: JSONObject,
        preferredLocale: YouTubeMusicRequestLocale,
        expectSongShelf: Boolean
    ): YouTubeMusicBrowseResponse {
        var activeBootstrap = bootstrap
        var lastError: IOException? = null
        for (requestLocale in YouTubeMusicLocaleResolver.requestCandidates(preferredLocale)) {
            for (attempt in 0 until YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
                try {
                    val root = postMusicSearch(
                        bootstrap = activeBootstrap,
                        payload = payload,
                        requestLocale = requestLocale
                    )
                    if (YouTubeMusicLocaleResolver.shouldRetryWithSafeFallback(payload, root)) {
                        lastError = IOException(
                            "YouTube Music search response missing contents for ${requestLocale.hl}/${requestLocale.gl}"
                        )
                        break
                    }
                    if (expectSongShelf && !YouTubeMusicParser.hasSongSearchShelf(root)) {
                        lastError = IOException(
                            "YouTube Music songs search missing song shelf for ${requestLocale.hl}/${requestLocale.gl}"
                        )
                        break
                    }
                    return YouTubeMusicBrowseResponse(
                        bootstrap = activeBootstrap,
                        root = root,
                        requestLocale = requestLocale
                    )
                } catch (error: IOException) {
                    lastError = error
                    if (attempt == YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS - 1) {
                        break
                    }
                    if (isYouTubeAuthRecoverableFailure(error)) {
                        authAutoRefreshManager?.refreshIfNeeded(
                            reason = "search_http_recoverable",
                            force = true
                        )
                    }
                    bootstrapCache = null
                    activeBootstrap = bootstrap(forceRefresh = true)
                }
            }
        }
        throw lastError ?: IOException("YouTube Music search failed")
    }

    private fun copyJsonFields(from: JSONObject, to: JSONObject) {
        val keys = from.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            to.put(key, from.get(key))
        }
    }

    private fun buildMusicContext(
        bootstrap: YouTubeMusicBootstrapConfig,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        return JSONObject()
            .put(
                "client",
                JSONObject()
                    .put("clientName", YOUTUBE_MUSIC_CLIENT_NAME_WEB_REMIX)
                    .put("clientVersion", bootstrap.webRemixClientVersion)
                    .put("hl", requestLocale.hl)
                    .put("gl", requestLocale.gl)
                    .put("visitorData", bootstrap.visitorData)
                    .put("utcOffsetMinutes", utcOffsetMinutes())
                    .put("userAgent", bootstrap.webUserAgent)
                    .put("platform", "DESKTOP")
            )
            .put("user", JSONObject().put("lockedSafetyMode", false))
    }

    private fun Request.Builder.applyCookieHeader(cookieHeader: String): Request.Builder {
        if (cookieHeader.isBlank()) {
            return this
        }
        return header("Cookie", cookieHeader)
    }

    private fun executeJson(request: Request): JSONObject {
        return JSONObject(executeText(request))
    }

    private fun executeText(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("YouTube Music request failed: ${response.code} ${body.take(160)}")
            }
            return body
        }
    }

    private fun utcOffsetMinutes(): Int {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)
    }
}
