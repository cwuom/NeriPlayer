package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.appendYouTubeConsentCookie
import moe.ouom.neriplayer.data.YouTubeAuthRepository
import moe.ouom.neriplayer.data.effectiveCookieHeader
import moe.ouom.neriplayer.data.resolveRequestUserAgent
import moe.ouom.neriplayer.data.resolveXGoogAuthUser
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
private const val YOUTUBE_MUSIC_SAFE_FALLBACK_HL = "en-US"
private const val YOUTUBE_MUSIC_SAFE_FALLBACK_GL = "US"

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

internal data class YouTubeMusicBootstrapConfig(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val sessionIndex: String,
    val cookieHeader: String,
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
        val country = locale.country
        if (country.isBlank()) {
            return safeFallback
        }
        val language = locale.language.ifBlank { safeFallback.hl.substringBefore('-') }
        return YouTubeMusicRequestLocale(
            hl = "$language-$country",
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

internal object YouTubeMusicParser {
    fun parseBootstrapConfig(
        html: String,
        cookieHeader: String,
        userAgent: String
    ): YouTubeMusicBootstrapConfig {
        val now = System.currentTimeMillis()
        return YouTubeMusicBootstrapConfig(
            apiKey = findRequired(html, "\"INNERTUBE_API_KEY\":\"([^\"]+)\""),
            webRemixClientVersion = findRequired(html, "\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\""),
            visitorData = findRequired(html, "\"VISITOR_DATA\":\"([^\"]+)\""),
            sessionIndex = findOptional(html, "\"SESSION_INDEX\":\"?([0-9]+)\"?").ifBlank { "0" },
            cookieHeader = cookieHeader,
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
                val durationText = extractColumnText(
                    columns = renderer.optJSONArray("fixedColumns"),
                    index = 0,
                    rendererKey = "musicResponsiveListItemFixedColumnRenderer"
                )
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

        // 一些歌单响应不会把曲目 shelf 放进 secondaryContents，需要回退到主内容区扫描。
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

    private fun findRequired(source: String, pattern: String): String {
        return findOptional(source, pattern).ifBlank {
            throw IOException("YouTube Music bootstrap parse failed: $pattern")
        }
    }

    private fun findOptional(source: String, pattern: String): String {
        return Regex(pattern).find(source)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractColumnText(columns: JSONArray?, index: Int, rendererKey: String): String {
        return extractText(
            columns?.optJSONObject(index)
                ?.optJSONObject(rendererKey)
                ?.optJSONObject("text")
        )
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
 * 将 YouTube Music 缩略图 URL 升级为完整尺寸。
 * YouTube 缩略图 URL 通常以 `=w60-h60-...` 结尾来限制尺寸，
 * 此函数将其替换为 `=w1200-h1200` 以获取高清封面。
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

        // 没有签名参数时可以直接复用 url；否则交给 NewPipe 兜底解签。
        if (!fields["s"].isNullOrBlank()) {
            return null
        }
        return fields["url"]?.takeIf { it.isNotBlank() }
    }
}

class YouTubeMusicClient(
    private val authRepo: YouTubeAuthRepository,
    private val okHttpClient: OkHttpClient
) {
    @Volatile
    private var bootstrapCache: YouTubeMusicBootstrapConfig? = null

    suspend fun getLibraryPlaylists(): List<YouTubeMusicLibraryPlaylist> = withContext(Dispatchers.IO) {
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
            val resolvedTrackCount = runCatching {
                val response = resolvePlaylistTrackCount(
                    bootstrap = bootstrap,
                    browseId = playlists[index].browseId,
                    requestLocale = requestLocale
                )
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
            }.getOrNull() ?: return@forEach
            playlists[index] = playlists[index].copy(
                trackCount = YouTubeMusicParser.parsePlaylistTrackCount(resolvedTrackCount)
            )
        }
        playlists
    }

    suspend fun getPlaylistDetail(
        browseId: String,
        fallbackTitle: String = "",
        fallbackSubtitle: String = "",
        fallbackCoverUrl: String = ""
    ): YouTubeMusicPlaylistDetail = withContext(Dispatchers.IO) {
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
        var bootstrap = bootstrap()
        var lastError: IOException? = null

        for (requestLocale in YouTubeMusicLocaleResolver.requestCandidates()) {
            var attempt = 0
            while (attempt < YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
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
                    attempt++
                    if (attempt >= YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
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

    private fun bootstrap(forceRefresh: Boolean = false): YouTubeMusicBootstrapConfig {
        val auth = authRepo.getAuthOnce().normalized()
        val cookieHeader = appendYouTubeConsentCookie(auth.effectiveCookieHeader())
        if (cookieHeader.isBlank()) {
            throw IOException("YouTube Music auth cookies missing")
        }

        val cached = bootstrapCache
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            cached != null &&
            cached.cookieHeader == cookieHeader &&
            now - cached.fetchedAtMs < YOUTUBE_MUSIC_BOOTSTRAP_TTL_MS
        ) {
            return cached
        }

        val userAgent = auth.resolveRequestUserAgent().ifBlank { YOUTUBE_MUSIC_DEFAULT_WEB_UA }
        val requestLocale = YouTubeMusicLocaleResolver.preferred()
        val homeHtml = executeText(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
                .header("Cookie", cookieHeader)
                .header("User-Agent", userAgent)
                .header("Accept-Language", requestLocale.acceptLanguage)
                .build()
        )
        val parsedConfig = YouTubeMusicParser.parseBootstrapConfig(
            html = homeHtml,
            cookieHeader = cookieHeader,
            userAgent = userAgent
        )
        return parsedConfig.copy(
            sessionIndex = auth.resolveXGoogAuthUser().ifBlank { parsedConfig.sessionIndex }
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

        return executeJson(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/browse?prettyPrint=false&key=${bootstrap.apiKey}")
                .header("Cookie", bootstrap.cookieHeader)
                .header("User-Agent", bootstrap.webUserAgent)
                .header("Accept-Language", requestLocale.acceptLanguage)
                .header("Content-Type", "application/json")
                .header("Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .header("X-Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .header("Referer", "$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
                .header("X-Goog-AuthUser", bootstrap.sessionIndex)
                .header("X-Goog-Visitor-Id", bootstrap.visitorData)
                .header("X-YouTube-Client-Name", YOUTUBE_MUSIC_CLIENT_NAME_NUM_WEB_REMIX)
                .header("X-YouTube-Client-Version", bootstrap.webRemixClientVersion)
                .applySidAuthorization(bootstrap.cookieHeader, YOUTUBE_MUSIC_MUSIC_ORIGIN)
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

        return executeJson(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/player?prettyPrint=false&key=${bootstrap.apiKey}")
                .header("Cookie", bootstrap.cookieHeader)
                .header("User-Agent", bootstrap.webUserAgent)
                .header("Accept-Language", requestLocale.acceptLanguage)
                .header("Content-Type", "application/json")
                .header("Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .header("X-Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .header("Referer", "$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
                .header("X-Goog-AuthUser", bootstrap.sessionIndex)
                .header("X-Goog-Visitor-Id", bootstrap.visitorData)
                .header("X-YouTube-Client-Name", YOUTUBE_MUSIC_CLIENT_NAME_NUM_WEB_REMIX)
                .header("X-YouTube-Client-Version", bootstrap.webRemixClientVersion)
                .applySidAuthorization(bootstrap.cookieHeader, YOUTUBE_MUSIC_MUSIC_ORIGIN)
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

        return executeJson(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/youtubei/v1/next?prettyPrint=false&key=${bootstrap.apiKey}")
                .header("Cookie", bootstrap.cookieHeader)
                .header("User-Agent", bootstrap.webUserAgent)
                .header("Accept-Language", requestLocale.acceptLanguage)
                .header("Content-Type", "application/json")
                .header("Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .header("X-Origin", YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .header("Referer", "$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
                .header("X-Goog-AuthUser", bootstrap.sessionIndex)
                .header("X-Goog-Visitor-Id", bootstrap.visitorData)
                .header("X-YouTube-Client-Name", YOUTUBE_MUSIC_CLIENT_NAME_NUM_WEB_REMIX)
                .header("X-YouTube-Client-Version", bootstrap.webRemixClientVersion)
                .applySidAuthorization(bootstrap.cookieHeader, YOUTUBE_MUSIC_MUSIC_ORIGIN)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
    }

    private fun resolvePlaylistTrackCount(
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

    private fun postMusicBrowseWithRetry(
        bootstrap: YouTubeMusicBootstrapConfig,
        payload: JSONObject,
        preferredLocale: YouTubeMusicRequestLocale
    ): YouTubeMusicBrowseResponse {
        var activeBootstrap = bootstrap
        var lastError: IOException? = null
        for (requestLocale in YouTubeMusicLocaleResolver.requestCandidates(preferredLocale)) {
            var attempt = 0
            while (attempt < YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
                try {
                    val root = postMusicBrowse(
                        bootstrap = activeBootstrap,
                        payload = payload,
                        requestLocale = requestLocale
                    )
                    // 某些地区/语言组合会返回只有 microformat 的空壳 browse，需要切到通用 locale 重试。
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
                    attempt++
                    if (attempt >= YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) {
                        break
                    }
                    bootstrapCache = null
                    activeBootstrap = bootstrap(forceRefresh = true)
                }
            }
        }
        throw lastError ?: IOException("YouTube Music request failed")
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

    private fun Request.Builder.applySidAuthorization(
        cookieHeader: String,
        origin: String
    ): Request.Builder {
        val cookies = cookieHeader
            .split(';')
            .map(String::trim)
            .filter { it.isNotBlank() && it.contains('=') }
            .associate {
                val delimiterIndex = it.indexOf('=')
                it.substring(0, delimiterIndex) to it.substring(delimiterIndex + 1)
            }
        val sid = cookies["__Secure-3PAPISID"]
            ?: cookies["SAPISID"]
            ?: cookies["__Secure-1PAPISID"]
            ?: cookies["APISID"]
            ?: return this
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val digest = sha1Hex("$timestamp $sid $origin")
        return header("Authorization", "SAPISIDHASH ${timestamp}_$digest")
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

    private fun sha1Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(Locale.US, byte.toInt() and 0xff)) }
        }
    }
}
