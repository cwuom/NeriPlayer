package moe.ouom.neriplayer.platform.youtube.api.client

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
 * File: moe.ouom.neriplayer.platform.youtube.api.client/YouTubeMusicClient
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import moe.ouom.neriplayer.platform.youtube.api.auth.hasEffectiveAuth
import moe.ouom.neriplayer.platform.youtube.api.auth.hasLoginCookies
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeAuthProvider
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeAuthRefresher
import moe.ouom.neriplayer.platform.youtube.api.auth.shouldStartYouTubeWebAuthRecovery
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemsPage
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicDebugProbeResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLibraryPlaylist
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLyrics
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlayableAudio
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicVideoMetadata
import moe.ouom.neriplayer.platform.youtube.api.parser.YouTubeMusicParser
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicBootstrapConfig
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicBrowseResponse
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeMusicLocaleResolver
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeMusicSearchParams
import moe.ouom.neriplayer.platform.youtube.api.protocol.hasEffectiveLogin
import moe.ouom.neriplayer.platform.youtube.api.protocol.parseYouTubeMusicVideoMetadata
import moe.ouom.neriplayer.platform.youtube.api.protocol.shouldRefreshYouTubeAuthAfterBootstrapFailure
import moe.ouom.neriplayer.platform.youtube.api.protocol.shouldRefreshYouTubeAuthAfterEmptyResponse
import moe.ouom.neriplayer.platform.youtube.api.protocol.shouldRetryYouTubeMusicAuthRefresh
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_ERROR_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_TEXT_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.platform.youtube.api.transport.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.effectiveCookieHeader
import moe.ouom.neriplayer.platform.youtube.api.transport.readErrorPreviewWithLimit
import moe.ouom.neriplayer.platform.youtube.api.transport.readTextWithLimit
import moe.ouom.neriplayer.platform.youtube.api.transport.resolveBootstrapUserAgent
import moe.ouom.neriplayer.platform.youtube.api.transport.resolveRequestUserAgent
import moe.ouom.neriplayer.platform.youtube.api.transport.resolveXGoogAuthUser
import moe.ouom.neriplayer.common.logging.NPLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "NERI-YTMusicClient"
private const val YOUTUBE_MUSIC_BROWSE_ID_LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
private const val YOUTUBE_MUSIC_BROWSE_ID_FOLLOWED_ARTISTS = "FEmusic_library_corpus_artists"
private const val YOUTUBE_MUSIC_MUSIC_ORIGIN = "https://music.youtube.com"
private const val YOUTUBE_MUSIC_BOOTSTRAP_TTL_MS = 10L * 60L * 1000L
private const val YOUTUBE_MUSIC_CLIENT_NAME_NUM_WEB_REMIX = "67"
private const val YOUTUBE_MUSIC_CLIENT_NAME_WEB_REMIX = "WEB_REMIX"
private const val YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT = 80
private const val YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS = 2
private const val YOUTUBE_MUSIC_HOME_SONG_ITEM_LIMIT = 12
private const val YOUTUBE_MUSIC_HOME_MAX_SHELVES = 8
private const val YOUTUBE_MUSIC_HOME_PAGE_LIMIT = 2
private const val YOUTUBE_MUSIC_HOME_SHELF_CONTINUATION_LIMIT = 2
private const val YOUTUBE_MUSIC_LIBRARY_TRACK_COUNT_RESOLVE_LIMIT = 8
private val YOUTUBE_MUSIC_BOOTSTRAP_PAGE_ORIGINS = listOf(
    YOUTUBE_MUSIC_MUSIC_ORIGIN,
    YOUTUBE_WEB_ORIGIN
)

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

        // 没有签名参数时可以直接复用 url; 否则交给 NewPipe 兜底解签
        if (!fields["s"].isNullOrBlank()) {
            return null
        }
        return fields["url"]?.takeIf { it.isNotBlank() }
    }
}

internal suspend fun collectYouTubeMusicPlaylistDetail(
    browseId: String,
    fallbackTitle: String = "",
    fallbackSubtitle: String = "",
    fallbackCoverUrl: String = "",
    pageLimit: Int = YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT,
    fetchRoot: suspend (JSONObject) -> JSONObject
): YouTubeMusicPlaylistDetail {
    var detail: YouTubeMusicPlaylistDetail? = null
    val tracks = mutableListOf<YouTubeMusicPlaylistTrack>()
    var continuation: String? = null
    var page = 0
    var reachedEnd = false
    var interruptedAfterPartialLoad = false

    while (page < pageLimit) {
        val payload = if (continuation.isNullOrBlank()) {
            JSONObject().put("browseId", browseId)
        } else {
            JSONObject().put("continuation", continuation)
        }
        val root = try {
            fetchRoot(payload)
        } catch (error: IOException) {
            if (page == 0) {
                throw error
            }
            interruptedAfterPartialLoad = true
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
        val playlistPage = YouTubeMusicParser.parsePlaylistPage(root)
        tracks += playlistPage.tracks
        continuation = playlistPage.continuation
        if (continuation.isNullOrBlank()) {
            reachedEnd = true
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
    val loadedTrackCount = distinctTracks.size.takeIf { it > 0 }
    val declaredTrackCount = baseDetail.trackCount
    val resolvedTrackCount = when {
        declaredTrackCount != null && loadedTrackCount != null -> maxOf(declaredTrackCount, loadedTrackCount)
        declaredTrackCount != null -> declaredTrackCount
        else -> loadedTrackCount
    }
    return baseDetail.copy(
        trackCount = resolvedTrackCount,
        tracks = distinctTracks,
        fullyLoaded = reachedEnd && !interruptedAfterPartialLoad
    )
}

class YouTubeMusicClient(
    private val authRepo: YouTubeAuthProvider,
    private val okHttpClient: OkHttpClient,
    private val authAutoRefreshManager: YouTubeAuthRefresher? = null
) {
    @Volatile
    private var bootstrapCache: YouTubeMusicBootstrapConfig? = null

    suspend fun getVideoMetadata(videoId: String): YouTubeMusicVideoMetadata? =
        withContext(Dispatchers.IO) {
            val normalizedVideoId = videoId.trim()
            if (normalizedVideoId.isBlank()) {
                return@withContext null
            }
            val request = Request.Builder()
                .url(
                    "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" +
                        URLEncoder.encode(normalizedVideoId, Charsets.UTF_8.name()) +
                        "&format=json"
                )
                .header("Accept", "application/json")
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    parseYouTubeMusicVideoMetadata(response.body.string())
                }
            }.getOrNull()
        }

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
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT,
        filter: YouTubeMusicSearchFilter = YouTubeMusicSearchFilter.Song
    ): List<YouTubeMusicSearchResult> {
        require(filter != YouTubeMusicSearchFilter.Creator) {
            "Use searchCreators for YouTube Music creator results"
        }
        val resultType = when (filter) {
            YouTubeMusicSearchFilter.Song -> YouTubeMusicSearchResultType.Song
            YouTubeMusicSearchFilter.Video -> YouTubeMusicSearchResultType.Video
            YouTubeMusicSearchFilter.Creator -> error("Creator results require searchCreators")
        }
        return collectSearchResults(
            query = query,
            limit = limit,
            filter = filter,
            keyOf = YouTubeMusicSearchResult::videoId,
            parse = { root, resultLimit ->
                when (resultType) {
                    YouTubeMusicSearchResultType.Song -> {
                        YouTubeMusicParser.parseSongSearchResults(root, resultLimit)
                    }
                    YouTubeMusicSearchResultType.Video -> {
                        YouTubeMusicParser.parseVideoSearchResults(root, resultLimit)
                    }
                }
            }
        )
    }

    suspend fun searchCreators(
        query: String,
        limit: Int = YOUTUBE_MUSIC_SEARCH_ITEM_LIMIT
    ): List<YouTubeMusicCreatorSummary> {
        return collectSearchResults(
            query = query,
            limit = limit,
            filter = YouTubeMusicSearchFilter.Creator,
            keyOf = YouTubeMusicCreatorSummary::browseId,
            parse = YouTubeMusicParser::parseCreatorSearchResults
        )
    }

    private suspend fun <T> collectSearchResults(
        query: String,
        limit: Int,
        filter: YouTubeMusicSearchFilter,
        keyOf: (T) -> String,
        parse: (JSONObject, Int) -> List<T>
    ): List<T> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext emptyList()
        }
        NPLogger.d(TAG, "search start: query=$query, filter=$filter, limit=$limit")
        val requestedLimit = limit.coerceAtLeast(1)
        var bootstrap = bootstrap()
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        val items = mutableListOf<T>()
        val seenKeys = linkedSetOf<String>()
        var continuation: String? = null
        var page = 0

        while (page < YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT && items.size < requestedLimit) {
            val payload = JSONObject()
                .put("query", query)
                .put("params", YouTubeMusicSearchParams.forFilter(filter))
            if (!continuation.isNullOrBlank()) {
                payload.put("continuation", continuation)
            }
            val root = try {
                val response = postMusicSearchWithRetry(
                    bootstrap = bootstrap,
                    payload = payload,
                    preferredLocale = requestLocale,
                    expectSearchShelf = continuation.isNullOrBlank()
                )
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
            } catch (error: IOException) {
                if (page == 0) {
                    NPLogger.e(TAG, "search failed on first page: query=$query", error)
                    throw error
                }
                NPLogger.w(
                    TAG,
                    "search continuation stopped: query=$query, page=$page, message=${error.message}"
                )
                break
            }
            parse(root, requestedLimit - items.size).forEach { result ->
                if (seenKeys.add(keyOf(result))) {
                    items += result
                }
            }
            continuation = YouTubeMusicParser.extractSearchContinuation(root)
            if (continuation.isNullOrBlank()) {
                break
            }
            page++
        }

        val results = items.take(requestedLimit)
        NPLogger.d(
            TAG,
            "search success: query=$query, filter=$filter, count=${results.size}, pages=${page + 1}"
        )
        results
    }

    suspend fun getCreatorDetail(
        creator: YouTubeMusicCreatorSummary
    ): YouTubeMusicCreatorDetail = withContext(Dispatchers.IO) {
        require(creator.browseId.isNotBlank()) { "YouTube Music creator browseId is required" }
        authAutoRefreshManager?.refreshIfNeeded(reason = "creator_detail", force = false)
        val bootstrap = if (authRepo.getAuthOnce().hasLoginCookies()) {
            authenticatedBootstrap(reason = "creator_detail")
        } else {
            bootstrap()
        }
        val requestLocale = YouTubeMusicLocaleResolver.preferred()
        val payload = JSONObject().put("browseId", creator.browseId)
        val response = postMusicBrowseWithRetry(
            bootstrap = bootstrap,
            payload = payload,
            preferredLocale = requestLocale
        )
        YouTubeMusicParser.parseCreatorDetail(response.root, creator)
    }

    suspend fun getCreatorItems(
        endpoint: YouTubeMusicCreatorBrowseEndpoint,
        fallbackTitle: String
    ): YouTubeMusicCreatorItemsPage = withContext(Dispatchers.IO) {
        require(endpoint.browseId.isNotBlank()) {
            "YouTube Music creator items browseId is required"
        }
        authAutoRefreshManager?.refreshIfNeeded(reason = "creator_items", force = false)
        val bootstrap = if (authRepo.getAuthOnce().hasLoginCookies()) {
            authenticatedBootstrap(reason = "creator_items")
        } else {
            bootstrap()
        }
        val payload = JSONObject().put("browseId", endpoint.browseId)
        if (endpoint.params.isNotBlank()) {
            payload.put("params", endpoint.params)
        }
        val response = postMusicBrowseWithRetry(
            bootstrap = bootstrap,
            payload = payload,
            preferredLocale = YouTubeMusicLocaleResolver.preferred()
        )
        YouTubeMusicParser.parseCreatorItemsPage(
            root = response.root,
            fallbackTitle = fallbackTitle
        )
    }

    suspend fun getCreatorItemsContinuation(
        continuation: String
    ): YouTubeMusicCreatorItemsPage = withContext(Dispatchers.IO) {
        require(continuation.isNotBlank()) {
            "YouTube Music creator items continuation is required"
        }
        authAutoRefreshManager?.refreshIfNeeded(
            reason = "creator_items_continuation",
            force = false
        )
        val bootstrap = if (authRepo.getAuthOnce().hasLoginCookies()) {
            authenticatedBootstrap(reason = "creator_items_continuation")
        } else {
            bootstrap()
        }
        val response = postMusicBrowseWithRetry(
            bootstrap = bootstrap,
            payload = JSONObject().put("continuation", continuation),
            preferredLocale = YouTubeMusicLocaleResolver.preferred()
        )
        YouTubeMusicParser.parseCreatorItemsContinuation(response.root)
    }

    suspend fun getLibraryPlaylists(
        resolveMissingTrackCounts: Boolean = true
    ): List<YouTubeMusicLibraryPlaylist> = getLibraryPlaylistsInternal(
        resolveMissingTrackCounts = resolveMissingTrackCounts,
        authRefreshRetryCount = 0
    )

    suspend fun getFollowedArtists(): List<YouTubeMusicCreatorSummary> = withContext(Dispatchers.IO) {
        requireFollowedArtistsAuth()
        var bootstrap = authenticatedBootstrap(reason = "followed_artists")
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        val artists = linkedMapOf<String, YouTubeMusicCreatorSummary>()
        val seenContinuations = mutableSetOf<String>()
        var continuation: String? = null

        repeat(YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT) {
            requireFollowedArtistsAuth()
            val payload = continuation?.let { JSONObject().put("continuation", it) }
                ?: JSONObject().put("browseId", YOUTUBE_MUSIC_BROWSE_ID_FOLLOWED_ARTISTS)
            val response = postMusicBrowseWithRetry(bootstrap, payload, requestLocale)
            bootstrap = response.bootstrap
            requestLocale = response.requestLocale
            requireFollowedArtistsAuth()
            val page = YouTubeMusicParser.parseFollowedArtistsPage(response.root)
                ?: throw IOException("YouTube Music followed artists response missing library contents")
            page.artists.forEach { artist -> artists.putIfAbsent(artist.browseId, artist) }
            val nextContinuation = page.continuation?.takeIf(String::isNotBlank)
            if (nextContinuation == null) {
                return@withContext artists.values.toList()
            }
            if (!seenContinuations.add(nextContinuation)) {
                throw IOException("YouTube Music followed artists continuation repeated")
            }
            continuation = nextContinuation
        }
        throw IOException("YouTube Music followed artists pagination did not reach an end")
    }

    private fun requireFollowedArtistsAuth() {
        if (!authRepo.getAuthOnce().hasEffectiveAuth()) {
            throw IOException("YouTube Music login is required to fetch followed artists")
        }
    }

    private suspend fun getLibraryPlaylistsInternal(
        resolveMissingTrackCounts: Boolean,
        authRefreshRetryCount: Int
    ): List<YouTubeMusicLibraryPlaylist> = withContext(Dispatchers.IO) {
        NPLogger.d(TAG, "getLibraryPlaylists start")
        var bootstrap = authenticatedBootstrap(reason = "library_playlists")
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
                    NPLogger.e(TAG, "getLibraryPlaylists failed on first page", error)
                    throw error
                }
                NPLogger.w(
                    TAG,
                    "getLibraryPlaylists continuation stopped: page=$page, message=${error.message}"
                )
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
        if (resolveMissingTrackCounts) {
            playlists.indices.forEach { index ->
                if (index >= YOUTUBE_MUSIC_LIBRARY_TRACK_COUNT_RESOLVE_LIMIT) {
                    return@forEach
                }
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
                } catch (error: IOException) {
                    NPLogger.w(
                        TAG,
                        "resolve track count failed: browseId=${playlists[index].browseId}, title=${playlists[index].title}, message=${error.message}"
                    )
                    null
                } ?: return@forEach
                playlists[index] = playlists[index].copy(
                    trackCount = YouTubeMusicParser.parsePlaylistTrackCount(resolvedTrackCount)
                )
            }
        }
        val auth = authRepo.getAuthOnce()
        if (playlists.isEmpty() && shouldRefreshYouTubeAuthAfterEmptyResponse(
                bootstrapLoggedIn = bootstrap.loggedIn,
                hasLoginCookies = auth.hasLoginCookies()
            )
        ) {
            val refreshResult = authAutoRefreshManager?.refreshIfNeeded(
                reason = "library_playlists_empty",
                force = true
            )
            if (
                refreshResult?.refreshed == true &&
                shouldRetryYouTubeMusicAuthRefresh(authRefreshRetryCount)
            ) {
                NPLogger.w(TAG, "getLibraryPlaylists empty, retry after auth refresh")
                bootstrapCache = null
                return@withContext getLibraryPlaylistsInternal(
                    resolveMissingTrackCounts = resolveMissingTrackCounts,
                    authRefreshRetryCount = authRefreshRetryCount + 1
                )
            }
        }
        NPLogger.d(TAG, "getLibraryPlaylists success: count=${playlists.size}, pages=${page + 1}")
        playlists
    }

    suspend fun getHomePlaylistRecommendations(
        limit: Int = YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT
    ): List<YouTubeMusicLibraryPlaylist> = withContext(Dispatchers.IO) {
        val shelves = getHomeFeed(
            fillShelfContinuations = false,
            requireLogin = true
        )
        val playlists = YouTubeMusicParser.parseHomePlaylistRecommendations(
            shelves = shelves,
            limit = limit
        )
        NPLogger.d(TAG, "getHomePlaylistRecommendations success: count=${playlists.size}")
        playlists
    }

    suspend fun hasPersonalizedContent(): Boolean = withContext(Dispatchers.IO) {
        if (!hasYouTubeMusicCookieContext()) {
            NPLogger.w(TAG, "hasPersonalizedContent false: missing YouTube auth context")
            return@withContext false
        }

        val bootstrap = authenticatedBootstrap(reason = "personalized_probe")
        if (!bootstrap.hasEffectiveLogin(authRepo.getAuthOnce())) {
            NPLogger.w(TAG, "hasPersonalizedContent false: no effective YouTube login")
            return@withContext false
        }
        if (!bootstrap.loggedIn) {
            NPLogger.d(TAG, "hasPersonalizedContent continues with saved YouTube cookie context")
        }

        val libraryPlaylists = getLibraryPlaylists(resolveMissingTrackCounts = false)
        if (libraryPlaylists.isNotEmpty()) {
            NPLogger.d(TAG, "hasPersonalizedContent true: libraryPlaylists=${libraryPlaylists.size}")
            return@withContext true
        }

        val shelves = getHomeFeed(
            fillShelfContinuations = false,
            requireLogin = true
        )
        val hasFeedItems = shelves.any { shelf -> shelf.items.isNotEmpty() }
        NPLogger.d(TAG, "hasPersonalizedContent feed fallback: shelves=${shelves.size}, hasItems=$hasFeedItems")
        hasFeedItems
    }

    /** 获取 YouTube Music 首页推荐 */
    suspend fun getHomeFeed(
        fillShelfContinuations: Boolean = true,
        requireLogin: Boolean = false
    ): List<YouTubeMusicHomeShelf> = getHomeFeedInternal(
        fillShelfContinuations = fillShelfContinuations,
        requireLogin = requireLogin,
        authRefreshRetryCount = 0
    )

    private suspend fun getHomeFeedInternal(
        fillShelfContinuations: Boolean,
        requireLogin: Boolean,
        authRefreshRetryCount: Int
    ): List<YouTubeMusicHomeShelf> = withContext(Dispatchers.IO) {
        NPLogger.d(TAG, "getHomeFeed start")
        if (requireLogin) {
            warnIfMissingYouTubeMusicCookieContext(reason = "home_feed")
        }
        var bootstrap = if (requireLogin) {
            authenticatedBootstrap(reason = "home_feed")
        } else {
            bootstrap()
        }
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        val result = mutableListOf<YouTubeMusicHomeShelf>()
        var continuation: String? = null
        var page = 0

        while (
            page < YOUTUBE_MUSIC_HOME_PAGE_LIMIT &&
            result.size < YOUTUBE_MUSIC_HOME_MAX_SHELVES
        ) {
            val payload = if (continuation.isNullOrBlank()) {
                JSONObject().put("browseId", "FEmusic_home")
            } else {
                JSONObject().put("continuation", continuation)
            }
            val response = postMusicBrowseWithRetry(bootstrap, payload, requestLocale)
            bootstrap = response.bootstrap
            requestLocale = response.requestLocale

            val parsedShelves = YouTubeMusicParser.parseHomeShelfPages(response.root)
            for (parsedShelf in parsedShelves) {
                if (result.size >= YOUTUBE_MUSIC_HOME_MAX_SHELVES) {
                    break
                }
                var shelfContinuation = parsedShelf.continuation
                var shelfPage = 0
                val isPlaylistShelf = parsedShelf.items.all { it.videoId.isBlank() }
                val maxItems = if (isPlaylistShelf) {
                    YOUTUBE_MUSIC_HOME_PLAYLIST_ITEM_LIMIT
                } else {
                    YOUTUBE_MUSIC_HOME_SONG_ITEM_LIMIT
                }
                val items = parsedShelf.items.toMutableList()

                while (
                    fillShelfContinuations &&
                    !shelfContinuation.isNullOrBlank() &&
                    shelfPage < YOUTUBE_MUSIC_HOME_SHELF_CONTINUATION_LIMIT &&
                    items.size < maxItems
                ) {
                    val shelfResponse = try {
                        postMusicBrowseWithRetry(
                            bootstrap = bootstrap,
                            payload = JSONObject().put("continuation", shelfContinuation),
                            preferredLocale = requestLocale
                        )
                    } catch (error: IOException) {
                        NPLogger.w(
                            TAG,
                            "getHomeFeed shelf continuation stopped: shelf=${parsedShelf.title}, page=$shelfPage, message=${error.message}"
                        )
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

        val auth = authRepo.getAuthOnce()
        if (result.isEmpty() && shouldRefreshYouTubeAuthAfterEmptyResponse(
                bootstrapLoggedIn = bootstrap.loggedIn,
                hasLoginCookies = auth.hasLoginCookies()
            )
        ) {
            val refreshResult = authAutoRefreshManager?.refreshIfNeeded(
                reason = "home_feed_empty",
                force = true
            )
            if (
                refreshResult?.refreshed == true &&
                shouldRetryYouTubeMusicAuthRefresh(authRefreshRetryCount)
            ) {
                NPLogger.w(TAG, "getHomeFeed empty, retry after auth refresh")
                bootstrapCache = null
                return@withContext getHomeFeedInternal(
                    fillShelfContinuations = fillShelfContinuations,
                    requireLogin = requireLogin,
                    authRefreshRetryCount = authRefreshRetryCount + 1
                )
            }
        }
        val shelves = result.take(YOUTUBE_MUSIC_HOME_MAX_SHELVES)
        NPLogger.d(TAG, "getHomeFeed success: shelves=${shelves.size}, pages=${page + 1}")
        shelves
    }

    suspend fun getPlaylistDetail(
        browseId: String,
        fallbackTitle: String = "",
        fallbackSubtitle: String = "",
        fallbackCoverUrl: String = ""
    ): YouTubeMusicPlaylistDetail = fetchPlaylistDetail(
        browseId = browseId,
        fallbackTitle = fallbackTitle,
        fallbackSubtitle = fallbackSubtitle,
        fallbackCoverUrl = fallbackCoverUrl,
        pageLimit = YOUTUBE_MUSIC_CONTINUATION_PAGE_LIMIT
    )

    suspend fun getPlaylistDetailPreview(
        browseId: String,
        fallbackTitle: String = "",
        fallbackSubtitle: String = "",
        fallbackCoverUrl: String = ""
    ): YouTubeMusicPlaylistDetail = fetchPlaylistDetail(
        browseId = browseId,
        fallbackTitle = fallbackTitle,
        fallbackSubtitle = fallbackSubtitle,
        fallbackCoverUrl = fallbackCoverUrl,
        pageLimit = 1
    )

    private suspend fun fetchPlaylistDetail(
        browseId: String,
        fallbackTitle: String,
        fallbackSubtitle: String,
        fallbackCoverUrl: String,
        pageLimit: Int
    ): YouTubeMusicPlaylistDetail = withContext(Dispatchers.IO) {
        authAutoRefreshManager?.refreshIfNeeded(reason = "playlist_detail", force = false)
        val resolvedBrowseId = normalizePlaylistBrowseId(browseId)
        var bootstrap = if (authRepo.getAuthOnce().hasLoginCookies()) {
            authenticatedBootstrap(reason = "playlist_detail")
        } else {
            bootstrap()
        }
        var requestLocale = YouTubeMusicLocaleResolver.preferred()
        collectYouTubeMusicPlaylistDetail(
            browseId = resolvedBrowseId,
            fallbackTitle = fallbackTitle,
            fallbackSubtitle = fallbackSubtitle,
            fallbackCoverUrl = fallbackCoverUrl,
            pageLimit = pageLimit
        ) { payload ->
                val response = postMusicBrowseWithRetry(bootstrap, payload, requestLocale)
                bootstrap = response.bootstrap
                requestLocale = response.requestLocale
                response.root
        }
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

        // 第一步: 调用 next 端点获取歌词 browseId
        val nextRoot = postMusicNext(bootstrap, videoId, requestLocale)
        val lyricsBrowseId = YouTubeMusicParser.parseLyricsBrowseId(nextRoot)
            ?: return@withContext null

        // 第二步: 调用 browse 端点获取歌词内容
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

    private fun normalizePlaylistBrowseId(browseId: String): String {
        val trimmed = browseId.trim()
        if (trimmed.isBlank() ||
            trimmed.startsWith("VL") ||
            trimmed.startsWith("MP") ||
            trimmed.startsWith("FE")
        ) {
            return trimmed
        }
        return "VL$trimmed"
    }

    private fun hasYouTubeMusicCookieContext(): Boolean {
        val auth = authRepo.getAuthOnce().normalized()
        return auth.hasSavedAuthMaterial()
    }

    private fun warnIfMissingYouTubeMusicCookieContext(reason: String) {
        if (hasYouTubeMusicCookieContext()) {
            return
        }
        NPLogger.w(TAG, "$reason has no saved YouTube Music auth context")
    }

    private suspend fun authenticatedBootstrap(reason: String): YouTubeMusicBootstrapConfig {
        val hasCookieContext = hasYouTubeMusicCookieContext()
        if (!hasCookieContext) {
            NPLogger.w(TAG, "$reason continues without saved YouTube Music auth context")
        }
        var config = bootstrap()
        var auth = authRepo.getAuthOnce()
        var hasEffectiveLogin = config.hasEffectiveLogin(auth)
        if (hasEffectiveLogin || !hasCookieContext) {
            if (!config.loggedIn && hasEffectiveLogin) {
                NPLogger.d(TAG, "$reason bootstrap did not expose LOGGED_IN, using saved YouTube auth")
            }
            return config
        }

        NPLogger.w(TAG, "$reason bootstrap is not logged in, refresh auth and retry")
        authAutoRefreshManager?.refreshIfNeeded(
            reason = "${reason}_bootstrap_not_logged_in",
            force = true
        )
        bootstrapCache = null
        config = bootstrap(forceRefresh = true)
        auth = authRepo.getAuthOnce()
        hasEffectiveLogin = config.hasEffectiveLogin(auth)
        if (!hasEffectiveLogin) {
            NPLogger.w(TAG, "$reason still has no effective YouTube login after refresh")
        } else if (!config.loggedIn) {
            NPLogger.d(
                TAG,
                "$reason bootstrap still did not expose LOGGED_IN after refresh, using saved YouTube auth"
            )
        }
        return config
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
            .put("hasAuthorization", auth.authorization.isNotBlank())
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
        val auth = authRepo.getAuthOnce().normalized()
        val cookieHeader = auth.effectiveCookieHeader().trim()
        val cacheUserAgent = auth.resolveBootstrapUserAgent()
        val authFingerprint = auth.buildBootstrapAuthFingerprint(
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
        fun fetchBootstrapConfig(): YouTubeMusicBootstrapConfig {
            val pageAuth = workingAuth.copy(
                cookieHeader = workingCookieHeader,
                cookies = emptyMap()
            )
            val requestHeaders = pageAuth.buildYouTubePageRequestHeaders(
                original = linkedMapOf(
                    "Accept-Language" to requestLocale.acceptLanguage
                ),
                userAgent = userAgent,
                includeAuthUser = true
            )
            val requestCookieHeader = requestHeaders["Cookie"].orEmpty()
            var lastError: IOException? = null
            for (origin in YOUTUBE_MUSIC_BOOTSTRAP_PAGE_ORIGINS) {
                val html = try {
                    executeText(
                        Request.Builder()
                            .url("$origin/")
                            .apply {
                                requestHeaders.forEach { (name, value) ->
                                    header(name, value)
                                }
                            }
                            .build()
                    )
                } catch (error: IOException) {
                    lastError = error
                    continue
                }
                try {
                    return YouTubeMusicParser.parseBootstrapConfig(
                        html = html,
                        cookieHeader = requestCookieHeader,
                        userAgent = userAgent
                    )
                } catch (error: IOException) {
                    lastError = error
                }
            }
            throw lastError ?: IOException("YouTube Music bootstrap request failed")
        }
        suspend fun refreshWorkingAuth(reason: String) {
            NPLogger.d(TAG, "bootstrap auth refresh requested: reason=$reason")
            authAutoRefreshManager?.refreshIfNeeded(
                reason = reason,
                force = true
            )
            workingAuth = authRepo.getAuthOnce().normalized()
            workingCookieHeader = workingAuth.effectiveCookieHeader().trim()
            userAgent = workingAuth.resolveBootstrapUserAgent()
        }
        val parsedConfig = try {
            fetchBootstrapConfig()
        } catch (error: IOException) {
            if (!shouldRefreshYouTubeAuthAfterBootstrapFailure(
                    error = error,
                    hasCookieHeader = workingCookieHeader.isNotBlank()
                )
            ) {
                NPLogger.e(TAG, "bootstrap failed without recoverable auth context", error)
                throw error
            }
            val refreshReason = "music_bootstrap_http_recoverable"
            NPLogger.w(
                TAG,
                "bootstrap retry after auth refresh: reason=$refreshReason, message=${error.message}"
            )
            refreshWorkingAuth(refreshReason)
            fetchBootstrapConfig()
        }
        val resolvedFingerprint = workingAuth.buildBootstrapAuthFingerprint(
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
            userSessionId = bootstrap.userSessionId
                .takeIf { it.isNotBlank() && bootstrap.cookieHeader.isNotBlank() }
                .orEmpty()
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
                    // 某些地区/语言组合会返回只有 microformat 的空壳 browse, 需要切到通用 locale 重试
                    if (YouTubeMusicLocaleResolver.shouldRetryWithSafeFallback(payload, root)) {
                        NPLogger.w(
                            TAG,
                            "browse fallback locale because response is empty: ${requestLocale.hl}/${requestLocale.gl}"
                        )
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
                    NPLogger.w(
                        TAG,
                        "browse attempt failed: locale=${requestLocale.hl}/${requestLocale.gl}, attempt=${attempt + 1}/$YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS, message=${error.message}"
                    )
                    if (attempt == YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS - 1) {
                        break
                    }
                    if (shouldStartYouTubeWebAuthRecovery(error)) {
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
        expectSearchShelf: Boolean
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
                        NPLogger.w(
                            TAG,
                            "search fallback locale because response is empty: ${requestLocale.hl}/${requestLocale.gl}"
                        )
                        lastError = IOException(
                            "YouTube Music search response missing contents for ${requestLocale.hl}/${requestLocale.gl}"
                        )
                        break
                    }
                    if (expectSearchShelf && !YouTubeMusicParser.hasSearchShelf(root)) {
                        NPLogger.w(
                            TAG,
                            "search fallback locale because filtered shelf is missing: ${requestLocale.hl}/${requestLocale.gl}"
                        )
                        lastError = IOException(
                            "YouTube Music filtered search missing shelf for ${requestLocale.hl}/${requestLocale.gl}"
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
                    NPLogger.w(
                        TAG,
                        "search attempt failed: locale=${requestLocale.hl}/${requestLocale.gl}, attempt=${attempt + 1}/$YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS, message=${error.message}"
                    )
                    if (attempt == YOUTUBE_MUSIC_MAX_REQUEST_ATTEMPTS - 1) {
                        break
                    }
                    if (shouldStartYouTubeWebAuthRecovery(error)) {
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
            .put(
                "request",
                JSONObject()
                    .put("internalExperimentFlags", JSONArray())
                    .put("sessionIndex", bootstrap.sessionIndex)
            )
    }

    private fun executeJson(request: Request): JSONObject {
        return JSONObject(executeText(request))
    }

    private fun executeText(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val preview = response.body.readErrorPreviewWithLimit(YOUTUBE_ERROR_RESPONSE_MAX_BYTES)
                throw IOException("YouTube Music request failed: ${response.code} $preview")
            }
            return response.body.readTextWithLimit(YOUTUBE_TEXT_RESPONSE_MAX_BYTES)
        }
    }

    private fun utcOffsetMinutes(): Int {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)
    }
}
