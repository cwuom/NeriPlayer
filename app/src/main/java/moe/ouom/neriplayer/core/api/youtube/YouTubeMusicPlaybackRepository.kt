package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlin.jvm.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.YouTubeAuthBundle
import moe.ouom.neriplayer.data.appendYouTubeConsentCookie
import moe.ouom.neriplayer.data.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.effectiveCookieHeader
import moe.ouom.neriplayer.data.resolveAuthorizationHeader
import moe.ouom.neriplayer.data.resolveBootstrapUserAgent
import moe.ouom.neriplayer.data.resolveRequestUserAgent
import moe.ouom.neriplayer.data.resolveXGoogAuthUser
import moe.ouom.neriplayer.data.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

private const val YOUTUBE_PLAYER_ANDROID_CLIENT_ID = "3"
private const val YOUTUBE_PLAYER_ANDROID_CLIENT_NAME = "ANDROID"
private const val YOUTUBE_PLAYER_ANDROID_CLIENT_VERSION = "21.03.36"
private const val YOUTUBE_PLAYER_ANDROID_USER_AGENT =
    "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
private const val YOUTUBE_PLAYER_ANDROID_OS_VERSION = "16"
private const val YOUTUBE_PLAYER_ANDROID_SDK_VERSION = 36

private const val YOUTUBE_PLAYER_IOS_CLIENT_ID = "5"
private const val YOUTUBE_PLAYER_IOS_CLIENT_NAME = "IOS"
private const val YOUTUBE_PLAYER_IOS_CLIENT_VERSION = "21.03.2"
private const val YOUTUBE_PLAYER_IOS_USER_AGENT =
    "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
private const val YOUTUBE_PLAYER_IOS_DEVICE_MODEL = "iPhone16,2"
private const val YOUTUBE_PLAYER_IOS_OS_VERSION = "18.7.2.22H124"

private const val YOUTUBE_PLAYER_API_BASE_URL = "https://youtubei.googleapis.com/youtubei/v1"
private const val YOUTUBE_PLAYER_API_FORMAT_VERSION = "2"

private val YOUTUBE_PLAYER_REQUEST_LOCALE = YouTubeMusicRequestLocale(
    hl = "en-US",
    gl = "US"
)

data class YouTubePlayableAudio(
    val url: String,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null
)

internal data class YouTubeAudioMetadata(
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null
)

private data class YouTubePlaybackBootstrap(
    val apiKey: String,
    val visitorData: String,
    val cookieHeader: String,
    val sessionIndex: String,
    val userAgent: String,
    val fetchedAtMs: Long
)

private data class CachedPlayableAudio(
    val audio: YouTubePlayableAudio,
    val cachedAtMs: Long
)

private data class YouTubePlayerAudioCandidate(
    val url: String,
    val mimeType: String?,
    val bitrate: Int,
    val audioSampleRate: Int,
    val contentLength: Long?,
    val durationMs: Long
)

internal data class YouTubePlayerPlayabilityStatus(
    val status: String,
    val reason: String
)

private data class PlayerAudioResolution(
    val playableAudio: YouTubePlayableAudio? = null,
    val metadata: YouTubeAudioMetadata? = null
)

private data class YouTubePlayerClientProfile(
    val clientId: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val endpointPath: String,
    val responseField: String? = null,
    val platform: String = "MOBILE",
    val clientScreen: String = "WATCH",
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val wrapPlayerRequest: Boolean = false
)

internal object YouTubeMusicPlaybackParser {
    fun parsePlayableAudio(root: JSONObject): YouTubePlayableAudio? {
        val candidates = collectAudioCandidates(root, requirePlayableUrl = true)
        val selected = candidates
            .sortedWith(
                compareByDescending<YouTubePlayerAudioCandidate> { it.bitrate }
                    .thenByDescending { it.audioSampleRate }
                    .thenByDescending { mimePreferenceScore(it.mimeType) }
                    .thenByDescending { it.contentLength ?: 0L }
            )
            .firstOrNull()
            ?: return null

        return YouTubePlayableAudio(
            url = selected.url,
            durationMs = selected.durationMs.takeIf { it > 0L } ?: parseDurationMs(root),
            mimeType = selected.mimeType,
            contentLength = selected.contentLength
        )
    }

    fun parsePreferredAudioMetadata(root: JSONObject): YouTubeAudioMetadata? {
        val candidates = collectAudioCandidates(root, requirePlayableUrl = false)
        val selected = candidates
            .sortedWith(
                compareByDescending<YouTubePlayerAudioCandidate> { it.bitrate }
                    .thenByDescending { it.audioSampleRate }
                    .thenByDescending { mimePreferenceScore(it.mimeType) }
                    .thenByDescending { it.contentLength ?: 0L }
            )
            .firstOrNull()

        val durationMs = selected?.durationMs?.takeIf { it > 0L } ?: parseDurationMs(root)
        val mimeType = selected?.mimeType
        val contentLength = selected?.contentLength
        if (durationMs <= 0L && mimeType.isNullOrBlank() && contentLength == null) {
            return null
        }
        return YouTubeAudioMetadata(
            durationMs = durationMs,
            mimeType = mimeType,
            contentLength = contentLength
        )
    }

    fun parsePlayabilityStatus(root: JSONObject): YouTubePlayerPlayabilityStatus {
        val playabilityStatus = root.optJSONObject("playabilityStatus")
        return YouTubePlayerPlayabilityStatus(
            status = playabilityStatus?.optString("status").orEmpty(),
            reason = playabilityStatus?.optString("reason").orEmpty()
        )
    }

    private fun collectAudioCandidates(
        root: JSONObject,
        requirePlayableUrl: Boolean
    ): List<YouTubePlayerAudioCandidate> {
        val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
        val formatArrays = listOfNotNull(
            streamingData.optJSONArray("adaptiveFormats"),
            streamingData.optJSONArray("formats")
        )

        return buildList {
            formatArrays.forEach { formats ->
                for (index in 0 until formats.length()) {
                    val format = formats.optJSONObject(index) ?: continue
                    val mimeType = normalizeMimeType(format.optString("mimeType"))
                    if (mimeType?.startsWith("audio/") != true) {
                        continue
                    }
                    val url = resolveFormatUrl(format)
                    if (requirePlayableUrl && url.isBlank()) {
                        continue
                    }
                    add(
                        YouTubePlayerAudioCandidate(
                            url = url,
                            mimeType = mimeType,
                            bitrate = parseIntLike(
                                format.opt("bitrate"),
                                format.opt("averageBitrate")
                            ),
                            audioSampleRate = parseIntLike(format.opt("audioSampleRate")),
                            contentLength = parseLongLike(format.opt("contentLength"))
                                .takeIf { it > 0L },
                            durationMs = parseLongLike(format.opt("approxDurationMs"))
                        )
                    )
                }
            }
        }
    }

    private fun resolveFormatUrl(format: JSONObject): String {
        val directUrl = format.optString("url").trim()
        if (directUrl.isNotBlank()) {
            return directUrl
        }

        val cipher = format.optString("signatureCipher")
            .ifBlank { format.optString("cipher") }
            .trim()
        if (cipher.isBlank()) {
            return ""
        }

        val params = parseUrlEncodedQuery(cipher)
        val url = params["url"]?.decodeUrlComponent().orEmpty()
        if (url.isBlank()) {
            return ""
        }

        val signature = params["sig"].orEmpty().ifBlank { params["signature"].orEmpty() }
        if (signature.isBlank()) {
            return if (params.containsKey("s")) "" else url
        }

        val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
        return appendQueryParameter(url, signatureParameter, signature)
    }

    private fun parseDurationMs(root: JSONObject): Long {
        return parseLongLike(
            root.optJSONObject("videoDetails")?.opt("lengthSeconds")
        ).takeIf { it > 0L }?.times(1000L)
            ?: parseLongLike(
                root.optJSONObject("microformat")
                    ?.optJSONObject("playerMicroformatRenderer")
                    ?.opt("lengthSeconds")
            ).takeIf { it > 0L }?.times(1000L)
            ?: 0L
    }

    private fun normalizeMimeType(rawMimeType: String): String? {
        return rawMimeType
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun mimePreferenceScore(mimeType: String?): Int {
        return when (mimeType?.lowercase(Locale.US)) {
            "audio/mp4", "audio/m4a", "audio/aac" -> 2
            "audio/webm" -> 1
            else -> 0
        }
    }

    private fun parseUrlEncodedQuery(rawQuery: String): Map<String, String> {
        return rawQuery.split('&')
            .mapNotNull { segment ->
                val key = segment.substringBefore('=').decodeUrlComponent()
                if (key.isBlank()) {
                    null
                } else {
                    key to segment.substringAfter('=', "").decodeUrlComponent()
                }
            }
            .toMap()
    }

    private fun appendQueryParameter(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return if (Regex("(^|[?&])${Regex.escape(key)}=").containsMatchIn(url)) {
            url
        } else {
            buildString(url.length + key.length + value.length + 2) {
                append(url)
                append(separator)
                append(key)
                append('=')
                append(value.encodeUrlComponent())
            }
        }
    }

    private fun parseLongLike(vararg values: Any?): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun parseIntLike(vararg values: Any?): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun String.decodeUrlComponent(): String {
        return URLDecoder.decode(this, Charsets.UTF_8.name())
    }

    private fun String.encodeUrlComponent(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name())
    }
}

class YouTubeMusicPlaybackRepository(
    private val okHttpClient: OkHttpClient,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() }
) {
    private val downloader = NewPipeOkHttpDownloader(okHttpClient, authProvider)
    private val playableAudioCache = linkedMapOf<String, CachedPlayableAudio>()

    @Volatile
    private var bootstrapCache: YouTubePlaybackBootstrap? = null

    suspend fun getBestPlayableAudio(videoId: String): YouTubePlayableAudio? = withContext(Dispatchers.IO) {
        getCachedPlayableAudio(videoId)?.let { return@withContext it }
        resolvePlayableAudio(videoId, logFailure = true)
    }

    suspend fun getBestPlayableAudioUrl(videoId: String): String? {
        return getBestPlayableAudio(videoId)?.url
    }

    suspend fun prefetchPlayableAudioUrl(videoId: String) = withContext(Dispatchers.IO) {
        if (getCachedPlayableAudio(videoId) != null) {
            return@withContext
        }
        resolvePlayableAudio(videoId, logFailure = false)
    }

    private fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(initializationLock) {
            if (initialized) {
                return
            }
            val locale = Locale.getDefault()
            NewPipe.init(
                downloader,
                Localization(
                    locale.language.ifBlank { "en" },
                    locale.country.ifBlank { "US" }
                )
            )
            initialized = true
        }
    }

    private fun resolvePlayableAudio(
        videoId: String,
        logFailure: Boolean
    ): YouTubePlayableAudio? {
        val playerResolution = resolvePlayerAudioViaPlayerApi(
            videoId = videoId,
            logFailure = logFailure
        )
        playerResolution?.playableAudio?.let { playableAudio ->
            cachePlayableAudio(videoId, playableAudio)
            return playableAudio
        }

        ensureInitialized()
        return resolvePlayableAudioViaNewPipe(
            videoId = videoId,
            logFailure = logFailure
        )?.mergeMetadataFrom(playerResolution?.metadata)
            ?.also { playableAudio ->
            cachePlayableAudio(videoId, playableAudio)
        }
    }

    private fun resolvePlayerAudioViaPlayerApi(
        videoId: String,
        logFailure: Boolean
    ): PlayerAudioResolution? {
        val auth = authProvider().normalized()
        if (!auth.hasLoginCookies()) {
            return null
        }

        return runCatching {
            fetchPlayerAudioViaPlayerApi(videoId = videoId, auth = auth)
        }.onFailure { error ->
            if (logFailure) {
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "player API resolve failed for $videoId (authUsable=${auth.isUsable()}, hasLoginCookies=${auth.hasLoginCookies()})",
                    error
                )
            }
        }.getOrNull()
    }

    private fun fetchPlayerAudioViaPlayerApi(
        videoId: String,
        auth: YouTubeAuthBundle
    ): PlayerAudioResolution? {
        var bootstrap = bootstrap(auth)
        var lastError: IOException? = null
        var bestMetadata: YouTubeAudioMetadata? = null

        repeat(PLAYER_REQUEST_MAX_ATTEMPTS) { attempt ->
            for (profile in playerClientProfiles()) {
                try {
                    val root = postPlayerRequest(
                        videoId = videoId,
                        auth = auth,
                        bootstrap = bootstrap,
                        profile = profile
                    )
                    val playability = YouTubeMusicPlaybackParser.parsePlayabilityStatus(root)
                    val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)
                    val metadata = YouTubeMusicPlaybackParser.parsePreferredAudioMetadata(root)
                    bestMetadata = bestMetadata.mergePreferred(metadata)
                    if (playability.status == "OK" && playableAudio != null) {
                        return PlayerAudioResolution(
                            playableAudio = playableAudio,
                            metadata = bestMetadata
                        )
                    }

                    val description = buildString {
                        append("YouTube player unavailable via ")
                        append(profile.clientName)
                        if (playability.status.isNotBlank()) {
                            append(": ")
                            append(playability.status)
                        }
                        if (playability.reason.isNotBlank()) {
                            append(" (")
                            append(playability.reason)
                            append(')')
                        }
                    }
                    lastError = IOException(description)
                } catch (error: IOException) {
                    lastError = error
                }
            }

            if (attempt < PLAYER_REQUEST_MAX_ATTEMPTS - 1) {
                bootstrap = bootstrap(auth, forceRefresh = true)
            }
        }

        if (bestMetadata != null) {
            return PlayerAudioResolution(metadata = bestMetadata)
        }
        throw lastError ?: IOException("YouTube Music player request failed")
    }

    private fun postPlayerRequest(
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        profile: YouTubePlayerClientProfile
    ): JSONObject {
        val body = buildPlayerRequestBody(videoId, bootstrap, profile)
        val requestHeaders = linkedMapOf(
            "Cookie" to bootstrap.cookieHeader,
            "User-Agent" to profile.userAgent,
            "Accept-Language" to YOUTUBE_PLAYER_REQUEST_LOCALE.acceptLanguage,
            "Content-Type" to "application/json",
            "X-Goog-AuthUser" to auth.resolveXGoogAuthUser().ifBlank { bootstrap.sessionIndex },
            "X-Goog-Visitor-Id" to bootstrap.visitorData,
            "X-Goog-Api-Format-Version" to YOUTUBE_PLAYER_API_FORMAT_VERSION,
            "X-YouTube-Client-Name" to profile.clientId,
            "X-YouTube-Client-Version" to profile.clientVersion
        )
        auth.resolveAuthorizationHeader(origin = YOUTUBE_WEB_ORIGIN)
            .takeIf { it.isNotBlank() }
            ?.let { requestHeaders["Authorization"] = it }

        val request = Request.Builder()
            .url(
                "$YOUTUBE_PLAYER_API_BASE_URL/${profile.endpointPath}" +
                    "?prettyPrint=false&id=$videoId&key=${bootstrap.apiKey}" +
                    if (profile.responseField != null) "&\$fields=${profile.responseField}" else ""
            )
            .apply {
                requestHeaders.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val root = executeJson(request)
        return profile.responseField
            ?.let { root.optJSONObject(it) ?: root }
            ?: root
    }

    private fun buildPlayerRequestBody(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        profile: YouTubePlayerClientProfile
    ): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("clientScreen", profile.clientScreen)
            .put("platform", profile.platform)
            .put("hl", YOUTUBE_PLAYER_REQUEST_LOCALE.hl)
            .put("gl", YOUTUBE_PLAYER_REQUEST_LOCALE.gl)
            .put("visitorData", bootstrap.visitorData)
            .put("utcOffsetMinutes", utcOffsetMinutes())
        profile.deviceMake?.let { clientContext.put("deviceMake", it) }
        profile.deviceModel?.let { clientContext.put("deviceModel", it) }
        profile.osName?.let { clientContext.put("osName", it) }
        profile.osVersion?.let { clientContext.put("osVersion", it) }
        profile.androidSdkVersion?.let { clientContext.put("androidSdkVersion", it) }

        return JSONObject()
            .put(
                "context",
                JSONObject()
                    .put("client", clientContext)
                    .put("user", JSONObject().put("lockedSafetyMode", false))
            ).apply {
                if (profile.wrapPlayerRequest) {
                    put(
                        "playerRequest",
                        JSONObject()
                            .put("videoId", videoId)
                            .put("contentCheckOk", true)
                            .put("racyCheckOk", true)
                    )
                    put("disablePlayerResponse", false)
                } else {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }
            }
    }

    private fun bootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean = false
    ): YouTubePlaybackBootstrap {
        val cookieHeader = appendYouTubeConsentCookie(auth.effectiveCookieHeader())
        if (cookieHeader.isBlank()) {
            throw IOException("YouTube Music auth cookies missing")
        }

        val now = System.currentTimeMillis()
        val cached = bootstrapCache
        if (!forceRefresh &&
            cached != null &&
            cached.cookieHeader == cookieHeader &&
            now - cached.fetchedAtMs < PLAYABLE_BOOTSTRAP_TTL_MS
        ) {
            return cached
        }

        val userAgent = auth.resolveBootstrapUserAgent()
        val homeHtml = fetchBootstrapHtml(
            auth = auth,
            userAgent = userAgent,
            cookieHeader = cookieHeader
        )
        return YouTubePlaybackBootstrap(
            apiKey = findRequired(homeHtml, "\"INNERTUBE_API_KEY\":\"([^\"]+)\""),
            visitorData = findRequired(homeHtml, "\"VISITOR_DATA\":\"([^\"]+)\""),
            cookieHeader = cookieHeader,
            sessionIndex = auth.resolveXGoogAuthUser().ifBlank {
                findOptional(homeHtml, "\"SESSION_INDEX\":\"?([0-9]+)\"?").ifBlank { "0" }
            },
            userAgent = userAgent,
            fetchedAtMs = now
        )
            .also { bootstrapCache = it }
    }

    private fun fetchBootstrapHtml(
        auth: YouTubeAuthBundle,
        userAgent: String,
        cookieHeader: String
    ): String {
        var lastError: IOException? = null
        for (origin in BOOTSTRAP_PAGE_ORIGINS) {
            val requestHeaders = auth.buildYouTubePageRequestHeaders(
                original = linkedMapOf(
                    "Accept-Language" to YOUTUBE_PLAYER_REQUEST_LOCALE.acceptLanguage
                ),
                userAgent = userAgent
            )
            val request = Request.Builder()
                .url("$origin/")
                .apply {
                    requestHeaders.forEach { (name, value) ->
                        header(name, value)
                    }
                    header("Cookie", cookieHeader)
                }
                .build()
            try {
                return executeText(request)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("YouTube Music bootstrap request failed")
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

    private fun resolvePlayableAudioViaNewPipe(
        videoId: String,
        logFailure: Boolean
    ): YouTubePlayableAudio? {
        return runCatching {
            val streamInfo = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )
            selectPlayableAudio(streamInfo)
        }.onFailure { error ->
            if (logFailure) {
                val auth = authProvider().normalized()
                NPLogger.e(
                    "YouTubeMusicPlayback",
                    "extract stream failed for $videoId (authUsable=${auth.isUsable()}, hasLoginCookies=${auth.hasLoginCookies()})",
                    error
                )
            }
        }.getOrNull()
    }

    private fun selectPlayableAudio(streamInfo: StreamInfo): YouTubePlayableAudio? {
        val selectedStream = streamInfo.audioStreams
            .asSequence()
            .filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .thenByDescending { it.averageBitrate }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull { it.content.isNotBlank() }
            ?: return null

        val resolvedDurationMs = streamInfo.duration
            .takeIf { it > 0L }
            ?.times(1000L)
            ?: 0L

        return YouTubePlayableAudio(
            url = selectedStream.content,
            durationMs = resolvedDurationMs,
            mimeType = null,
            contentLength = null
        )
    }

    private fun YouTubeAudioMetadata?.mergePreferred(
        incoming: YouTubeAudioMetadata?
    ): YouTubeAudioMetadata? {
        if (incoming == null) {
            return this
        }
        if (this == null) {
            return incoming
        }
        return when {
            incoming.contentLength != null && this.contentLength == null -> incoming
            incoming.durationMs > this.durationMs -> incoming
            incoming.mimeType == "audio/mp4" && this.mimeType != "audio/mp4" -> incoming
            else -> this
        }
    }

    private fun YouTubePlayableAudio.mergeMetadataFrom(
        metadata: YouTubeAudioMetadata?
    ): YouTubePlayableAudio {
        if (metadata == null) {
            return this
        }
        return copy(
            durationMs = durationMs.takeIf { it > 0L } ?: metadata.durationMs,
            mimeType = mimeType ?: metadata.mimeType,
            contentLength = contentLength ?: metadata.contentLength
        )
    }

    private fun playerClientProfiles(): List<YouTubePlayerClientProfile> {
        return listOf(
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_ANDROID_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_ANDROID_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_ANDROID_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_ANDROID_USER_AGENT,
                endpointPath = "player",
                osName = "Android",
                osVersion = YOUTUBE_PLAYER_ANDROID_OS_VERSION,
                androidSdkVersion = YOUTUBE_PLAYER_ANDROID_SDK_VERSION
            ),
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_IOS_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_IOS_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_IOS_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_IOS_USER_AGENT,
                endpointPath = "player",
                deviceMake = "Apple",
                deviceModel = YOUTUBE_PLAYER_IOS_DEVICE_MODEL,
                osName = "iOS",
                osVersion = YOUTUBE_PLAYER_IOS_OS_VERSION
            ),
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_ANDROID_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_ANDROID_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_ANDROID_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_ANDROID_USER_AGENT,
                endpointPath = "reel/reel_item_watch",
                responseField = "playerResponse",
                osName = "Android",
                osVersion = YOUTUBE_PLAYER_ANDROID_OS_VERSION,
                androidSdkVersion = YOUTUBE_PLAYER_ANDROID_SDK_VERSION,
                wrapPlayerRequest = true
            )
        )
    }

    private fun getCachedPlayableAudio(videoId: String): YouTubePlayableAudio? {
        synchronized(playableAudioCache) {
            val cached = playableAudioCache[videoId] ?: return null
            if (System.currentTimeMillis() - cached.cachedAtMs > PLAYABLE_URL_CACHE_TTL_MS) {
                playableAudioCache.remove(videoId)
                return null
            }
            return cached.audio
        }
    }

    private fun cachePlayableAudio(videoId: String, audio: YouTubePlayableAudio) {
        synchronized(playableAudioCache) {
            playableAudioCache.remove(videoId)
            playableAudioCache[videoId] = CachedPlayableAudio(
                audio = audio,
                cachedAtMs = System.currentTimeMillis()
            )
            while (playableAudioCache.size > PLAYABLE_URL_CACHE_MAX_SIZE) {
                val eldestKey = playableAudioCache.entries.firstOrNull()?.key ?: break
                playableAudioCache.remove(eldestKey)
            }
        }
    }

    private fun utcOffsetMinutes(): Int {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)
    }

    private fun findRequired(source: String, pattern: String): String {
        return findOptional(source, pattern).ifBlank {
            throw IOException("YouTube bootstrap parse failed: $pattern")
        }
    }

    private fun findOptional(source: String, pattern: String): String {
        return Regex(pattern).find(source)?.groupValues?.getOrNull(1).orEmpty()
    }

    private companion object {
        val BOOTSTRAP_PAGE_ORIGINS: List<String> = listOf(
            YOUTUBE_MUSIC_ORIGIN,
            YOUTUBE_WEB_ORIGIN
        )
        const val PLAYABLE_URL_CACHE_TTL_MS: Long = 8L * 60L * 1000L
        const val PLAYABLE_BOOTSTRAP_TTL_MS: Long = 10L * 60L * 1000L
        const val PLAYABLE_URL_CACHE_MAX_SIZE: Int = 64
        const val PLAYER_REQUEST_MAX_ATTEMPTS: Int = 2
        val initializationLock = Any()

        @Volatile
        var initialized: Boolean = false
    }
}
