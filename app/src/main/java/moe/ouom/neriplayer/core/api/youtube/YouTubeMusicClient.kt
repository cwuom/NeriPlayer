package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
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

internal data class YouTubeMusicBootstrapConfig(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val sessionIndex: String,
    val cookieHeader: String,
    val webUserAgent: String,
    val fetchedAtMs: Long
)

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
                val videoId = renderer.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.ifBlank {
                        renderer.optJSONObject("playlistItemData")?.optString("videoId", "")
                    }
                    .orEmpty()
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
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (sections != null) {
            for (index in 0 until sections.length()) {
                val section = sections.optJSONObject(index) ?: continue
                section.optJSONObject("musicPlaylistShelfRenderer")?.let { return it }
                section.optJSONObject("musicShelfRenderer")?.let { return it }
            }
        }
        val continuationContents = root.optJSONObject("continuationContents")
        return continuationContents?.optJSONObject("musicPlaylistShelfContinuation")
            ?: continuationContents?.optJSONObject("musicShelfContinuation")
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
        return thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
    }

    private fun playlistIdFromBrowseId(browseId: String): String {
        return if (browseId.startsWith("VL")) {
            browseId.removePrefix("VL")
        } else {
            browseId
        }
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
                val response = postMusicBrowseWithRetry(bootstrap, payload)
                bootstrap = response.first
                response.second
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
                val response = resolvePlaylistTrackCount(bootstrap, playlists[index].browseId)
                bootstrap = response.first
                response.second
            }.getOrNull() ?: return@forEach
            playlists[index] = playlists[index].copy(trackCount = resolvedTrackCount)
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
                val response = postMusicBrowseWithRetry(bootstrap, payload)
                bootstrap = response.first
                response.second
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
        val homeHtml = executeText(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_MUSIC_ORIGIN/")
                .header("Cookie", cookieHeader)
                .header("User-Agent", userAgent)
                .header("Accept-Language", languageHeader())
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
        payload: JSONObject
    ): JSONObject {
        val body = JSONObject().put("context", buildMusicContext(bootstrap))
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
                .header("Accept-Language", languageHeader())
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
        browseId: String
    ): Pair<YouTubeMusicBootstrapConfig, Int?> {
        if (browseId.isBlank()) {
            return bootstrap to null
        }
        val response = postMusicBrowseWithRetry(bootstrap, JSONObject().put("browseId", browseId))
        return response.first to YouTubeMusicParser.parsePlaylistTrackCount(response.second)
    }

    private fun postMusicBrowseWithRetry(
        bootstrap: YouTubeMusicBootstrapConfig,
        payload: JSONObject
    ): Pair<YouTubeMusicBootstrapConfig, JSONObject> {
        var activeBootstrap = bootstrap
        var lastError: IOException? = null
        repeat(YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                return activeBootstrap to postMusicBrowse(activeBootstrap, payload)
            } catch (error: IOException) {
                lastError = error
                if (attempt == YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS - 1) {
                    throw error
                }
                bootstrapCache = null
                activeBootstrap = bootstrap(forceRefresh = true)
            }
        }
        throw lastError ?: IOException("YouTube Music request failed")
    }

    private fun buildMusicContext(bootstrap: YouTubeMusicBootstrapConfig): JSONObject {
        return JSONObject()
            .put(
                "client",
                JSONObject()
                    .put("clientName", YOUTUBE_MUSIC_CLIENT_NAME_WEB_REMIX)
                    .put("clientVersion", bootstrap.webRemixClientVersion)
                    .put("hl", localeHl())
                    .put("gl", localeGl())
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

    private fun languageHeader(): String = buildString {
        append(localeHl())
        append(",")
        append(localeGl().lowercase(Locale.US))
        append(";q=0.9,en;q=0.8")
    }

    private fun localeHl(): String {
        val locale = Locale.getDefault()
        val language = locale.language.ifBlank { "en" }
        val country = locale.country
        return if (country.isBlank()) language else "$language-$country"
    }

    private fun localeGl(): String {
        return Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"
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
