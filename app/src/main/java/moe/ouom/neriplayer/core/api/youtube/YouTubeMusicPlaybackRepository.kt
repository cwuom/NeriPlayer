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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeMusicPlaybackRepository
 * Updated: 2026/3/23
 */


import android.content.Context
import androidx.media3.common.MimeTypes
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.jvm.Volatile
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import moe.ouom.neriplayer.data.auth.youtube.isYouTubeAuthRecoverableFailure
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthAutoRefreshManager
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.auth.youtube.evaluateYouTubeAuthHealth
import moe.ouom.neriplayer.data.platform.youtube.YOUTUBE_WEB_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.appendYouTubeConsentCookie
import moe.ouom.neriplayer.data.platform.youtube.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.data.platform.youtube.effectiveCookieHeader
import moe.ouom.neriplayer.data.platform.youtube.isYouTubeGoogleVideoHost
import moe.ouom.neriplayer.data.platform.youtube.resolveAuthorizationHeader
import moe.ouom.neriplayer.data.platform.youtube.resolveBootstrapUserAgent
import moe.ouom.neriplayer.data.platform.youtube.resolveXGoogAuthUser
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
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID = "67"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME = "WEB_REMIX"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_VERSION = "1.20260403.09.00"
private const val YOUTUBE_PLAYER_TV_CLIENT_ID = "7"
private const val YOUTUBE_PLAYER_TV_CLIENT_NAME = "TVHTML5"
private const val YOUTUBE_PLAYER_TV_CLIENT_VERSION = "7.20260114.12.00"
private const val YOUTUBE_PLAYER_TV_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
        "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
private const val YOUTUBE_PLAYER_TV_DOWNGRADED_CLIENT_VERSION = "5.20260114"
private const val YOUTUBE_PLAYER_TV_DOWNGRADED_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
private const val YOUTUBE_PLAYER_WEB_REMIX_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
private const val YOUTUBE_PLAYER_WEB_REMIX_ACCEPT_HEADER =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
        "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_FORM_FACTOR = "UNKNOWN_FORM_FACTOR"
private const val YOUTUBE_PLAYER_WEB_REMIX_PLAYER_TYPE = "UNIPLAYER"
private const val YOUTUBE_PLAYER_WEB_REMIX_UI_THEME = "USER_INTERFACE_THEME_LIGHT"
private const val YOUTUBE_PLAYER_WEB_REMIX_CLIENT_SCREEN = "WATCH_FULL_SCREEN"
private const val YOUTUBE_PLAYER_WEB_REMIX_CONNECTION_TYPE = "CONN_CELLULAR_4G"
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS = 771
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS = 897
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_PIXEL_DENSITY = 1
private const val YOUTUBE_PLAYER_WEB_REMIX_SCREEN_DENSITY_FLOAT = 1.375
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH = 2048
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_HEIGHT = 1152
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH = 2048
private const val YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT = 1104
private const val YOUTUBE_PLAYER_WEB_REMIX_INNER_WIDTH = 757
private const val YOUTUBE_PLAYER_WEB_REMIX_COLOR_DEPTH = 32
private const val YOUTUBE_PLAYER_WEB_REMIX_BROWSER_CONNECTION = 31
private const val YOUTUBE_PLAYER_WEB_REMIX_HISTORY_LENGTH = 5
private const val YOUTUBE_PLAYER_PLAYBACK_LACT_MILLISECONDS = "9"
// 首播更看重尽快落到可播链路，别在 fallback 前白等太久的 PO token
private const val WEB_REMIX_PO_TOKEN_PREFETCH_JOIN_TIMEOUT_MS = 150L
private const val PLAYBACK_WARM_AUTH_REFRESH_AGE_MS = 12L * 60L * 60L * 1000L

private const val YOUTUBE_PLAYER_API_FORMAT_VERSION = "2"
private const val STREAMING_CIPHER_LOG_THRESHOLD_MS = 250L

private fun playbackElapsedMs(startedAtMs: Long): Long = System.currentTimeMillis() - startedAtMs

@VisibleForTesting
internal object NewPipeFallbackTracker {
    private const val FAILURE_THRESHOLD = 2
    private val signatureFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val throttlingFailures = ConcurrentHashMap<String, AtomicInteger>()

    fun maybeSkipSignature(playerJsUrl: String): Boolean {
        val key = playerJsUrl.ifBlank { "<unknown-signature>" }
        return signatureFailures[key]?.get() ?: 0 >= FAILURE_THRESHOLD
    }

    fun maybeSkipThrottling(playerJsUrl: String): Boolean {
        val key = playerJsUrl.ifBlank { "<unknown-throttling>" }
        return throttlingFailures[key]?.get() ?: 0 >= FAILURE_THRESHOLD
    }

    fun recordSignatureFailure(playerJsUrl: String) {
        val key = playerJsUrl.ifBlank { "<unknown-signature>" }
        signatureFailures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
    }

    fun recordThrottlingFailure(playerJsUrl: String) {
        val key = playerJsUrl.ifBlank { "<unknown-throttling>" }
        throttlingFailures.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
    }

    fun reset() {
        signatureFailures.clear()
        throttlingFailures.clear()
    }
}

enum class YouTubePlayableStreamType {
    DIRECT,
    HLS
}

data class YouTubePlayableAudio(
    val url: String,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null
)

internal data class YouTubeAudioMetadata(
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null
)

private data class YouTubePlaybackBootstrap(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val playerJsUrl: String,
    val cookieHeader: String,
    val authFingerprint: String,
    val sessionIndex: String,
    val userAgent: String,
    val remoteHost: String,
    val signatureTimestamp: Int?,
    val appInstallData: String,
    val coldConfigData: String,
    val coldHashData: String,
    val hotHashData: String,
    val deviceExperimentId: String,
    val rolloutToken: String,
    val dataSyncId: String,
    val delegatedSessionId: String,
    val userSessionId: String,
    val loggedIn: Boolean,
    val fetchedAtMs: Long
)

private data class YouTubeWebRemixRequestMetadata(
    val originalUrl: String,
    val watchUrl: String,
    val playlistId: String,
    val cpn: String,
    val clientScreenNonce: String
)

private data class CachedPlayableAudio(
    val audio: YouTubePlayableAudio,
    val cachedAtMs: Long
)

private data class InFlightPlayableAudioRequest(
    val videoId: String,
    val preferredQualityKey: String,
    val requireDirect: Boolean,
    val preferM4a: Boolean,
    val forceRefresh: Boolean
)

private data class InFlightBootstrapRequest(
    val authFingerprint: String,
    val forceRefresh: Boolean
)

private data class ChallengeCandidateResult<T>(
    val source: String,
    val value: T?,
    val elapsedMs: Long
)

private data class YouTubePlayerAudioCandidate(
    val format: JSONObject,
    val mimeType: String?,
    val bitrate: Int,
    val audioSampleRate: Int,
    val contentLength: Long?,
    val durationMs: Long
)

interface YouTubeStreamingCipherResolver {
    fun resolveSignature(encryptedSignature: String): String?
    fun resolveStreamingUrl(url: String): String
}

private fun playableAudioMimePreferenceScore(mimeType: String?): Int {
    return when (mimeType?.lowercase(Locale.US)) {
        MimeTypes.APPLICATION_M3U8.lowercase(Locale.US) -> 3
        "audio/mp4", "audio/m4a", "audio/aac" -> 2
        "audio/webm" -> 1
        else -> 0
    }
}

private suspend fun <T> awaitFirstChallengeSuccess(
    candidates: List<Deferred<ChallengeCandidateResult<T>>>
): ChallengeCandidateResult<T>? = coroutineScope {
    val pending = candidates.toMutableList()
    while (pending.isNotEmpty()) {
        val candidate = select<ChallengeCandidateResult<T>> {
            pending.forEach { deferred ->
                deferred.onAwait { it }
            }
        }
        pending.removeAll { it.isCompleted }
        if (candidate.value != null) {
            pending.forEach { deferred -> deferred.cancel() }
            return@coroutineScope candidate
        }
    }
    null
}

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

private enum class YouTubeMusicPlaybackQuality {
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH;

    companion object {
        fun fromSetting(settingKey: String?): YouTubeMusicPlaybackQuality {
            return when (settingKey?.lowercase(Locale.US)) {
                "low",
                "standard" -> LOW
                "medium" -> MEDIUM
                "high",
                "higher" -> HIGH
                "very_high",
                "very-high",
                "exhigh",
                "lossless",
                "hires",
                "jyeffect",
                "sky",
                "jymaster" -> VERY_HIGH
                else -> VERY_HIGH
            }
        }
    }
}

internal object YouTubeMusicPlaybackParser {
    fun parsePlayableAudio(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false,
        cipherResolver: YouTubeStreamingCipherResolver? = null
    ): YouTubePlayableAudio? {
        val candidates = selectCandidate(
            candidates = collectAudioCandidates(root),
            preferredQualityKey = preferredQualityKey,
            preferM4a = preferM4a
        )
        val durationFallbackMs = parseDurationMs(root)
        for (candidate in candidates) {
            val playableUrl = resolveFormatUrl(candidate.format, cipherResolver)
            if (playableUrl.isBlank()) {
                continue
            }
            return YouTubePlayableAudio(
                url = playableUrl,
                durationMs = candidate.durationMs.takeIf { it > 0L } ?: durationFallbackMs,
                mimeType = candidate.mimeType,
                contentLength = candidate.contentLength,
                bitrateKbps = candidate.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 },
                sampleRateHz = candidate.audioSampleRate.takeIf { it > 0 }
            )
        }
        return null
    }

    fun parsePreferredAudioMetadata(
        root: JSONObject,
        preferredQualityKey: String? = null,
        preferM4a: Boolean = false
    ): YouTubeAudioMetadata? {
        val candidates = collectAudioCandidates(root)
        val selected = selectCandidate(candidates, preferredQualityKey, preferM4a).firstOrNull()

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
        root: JSONObject
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
                    add(
                        YouTubePlayerAudioCandidate(
                            format = format,
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

    private fun resolveFormatUrl(
        format: JSONObject,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        val directUrl = format.optString("url").trim()
        if (directUrl.isNotBlank()) {
            return resolveStreamingUrl(directUrl, cipherResolver)
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
            if (!params.containsKey("s")) {
                return resolveStreamingUrl(url, cipherResolver)
            }
            val decryptedSignature = params["s"]
                ?.takeIf { it.isNotBlank() }
                ?.let { cipherResolver?.resolveSignature(it) }
                .orEmpty()
            if (decryptedSignature.isBlank()) {
                return ""
            }
            val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
            return resolveStreamingUrl(
                appendQueryParameter(url, signatureParameter, decryptedSignature),
                cipherResolver
            )
        }

        val signatureParameter = params["sp"].orEmpty().ifBlank { "sig" }
        return resolveStreamingUrl(
            appendQueryParameter(url, signatureParameter, signature),
            cipherResolver
        )
    }

    private fun resolveStreamingUrl(
        url: String,
        cipherResolver: YouTubeStreamingCipherResolver?
    ): String {
        if (url.isBlank()) {
            return ""
        }
        return cipherResolver?.resolveStreamingUrl(url).orEmpty().ifBlank { url }
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

    private fun selectCandidate(
        candidates: List<YouTubePlayerAudioCandidate>,
        preferredQualityKey: String?,
        preferM4a: Boolean = false
    ): List<YouTubePlayerAudioCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val comparator = if (preferM4a) {
            compareByDescending<YouTubePlayerAudioCandidate> { mimePreferenceScore(it.mimeType) }
                .thenByDescending { it.bitrate }
                .thenByDescending { it.audioSampleRate }
                .thenByDescending { it.contentLength ?: 0L }
        } else {
            audioCandidateComparator()
        }
        val sortedDescending = candidates.sortedWith(comparator)
        val sortedAscending = sortedDescending.asReversed()
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending
            YouTubeMusicPlaybackQuality.MEDIUM -> prioritizeThresholdCandidate(
                sortedAscending = sortedAscending,
                thresholdBitrate = 96_000
            )
            YouTubeMusicPlaybackQuality.HIGH -> prioritizeThresholdCandidate(
                sortedAscending = sortedAscending,
                thresholdBitrate = 128_000
            )
            YouTubeMusicPlaybackQuality.VERY_HIGH -> sortedDescending
        }
    }

    private fun prioritizeThresholdCandidate(
        sortedAscending: List<YouTubePlayerAudioCandidate>,
        thresholdBitrate: Int
    ): List<YouTubePlayerAudioCandidate> {
        val preferredIndex = sortedAscending.indexOfFirst { it.bitrate >= thresholdBitrate }
        if (preferredIndex < 0) {
            return sortedAscending.asReversed()
        }
        return buildList(sortedAscending.size) {
            addAll(sortedAscending.subList(preferredIndex, sortedAscending.size))
            addAll(sortedAscending.subList(0, preferredIndex).asReversed())
        }
    }

    private fun audioCandidateComparator(): Comparator<YouTubePlayerAudioCandidate> {
        return compareByDescending<YouTubePlayerAudioCandidate> { it.bitrate }
            .thenByDescending { it.audioSampleRate }
            .thenByDescending { mimePreferenceScore(it.mimeType) }
            .thenByDescending { it.contentLength ?: 0L }
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

internal data class YouTubeHlsAudioPlaylist(
    val uri: String,
    val contentLength: Long? = null,
    val estimatedBitrate: Int = 0,
    val audioItag: Int? = null
)

internal object YouTubeMusicHlsManifestParser {
    fun selectAudioPlaylist(
        masterManifest: String,
        masterManifestUrl: String? = null,
        preferredQualityKey: String? = null,
        durationMs: Long = 0L
    ): YouTubeHlsAudioPlaylist? {
        val candidates = collectAudioPlaylists(
            masterManifest = masterManifest,
            masterManifestUrl = masterManifestUrl,
            durationMs = durationMs
        )
        if (candidates.isEmpty()) {
            return null
        }
        val sortedDescending = candidates.sortedWith(
            compareByDescending<YouTubeHlsAudioPlaylist> { it.estimatedBitrate }
                .thenByDescending { it.contentLength ?: 0L }
                .thenByDescending { it.audioItag ?: 0 }
        )
        val sortedAscending = sortedDescending.asReversed()
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending.first()
            YouTubeMusicPlaybackQuality.MEDIUM -> {
                sortedAscending.firstOrNull { it.estimatedBitrate >= 96_000 }
                    ?: sortedDescending.first()
            }
            YouTubeMusicPlaybackQuality.HIGH -> {
                sortedAscending.firstOrNull { it.estimatedBitrate >= 128_000 }
                    ?: sortedDescending.first()
            }
            YouTubeMusicPlaybackQuality.VERY_HIGH -> sortedDescending.first()
        }
    }

    fun collectAudioPlaylists(
        masterManifest: String,
        masterManifestUrl: String? = null,
        durationMs: Long = 0L
    ): List<YouTubeHlsAudioPlaylist> {
        return masterManifest
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .mapNotNull { line ->
                val attributes = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                if (!attributes["TYPE"].equals("AUDIO", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val rawUri = attributes["URI"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val audioItag = parseAudioItag(rawUri)
                val contentLength = parseContentLength(rawUri)
                YouTubeHlsAudioPlaylist(
                    uri = resolveRelativeUri(masterManifestUrl, rawUri),
                    contentLength = contentLength,
                    estimatedBitrate = estimateBitrate(audioItag, contentLength, durationMs),
                    audioItag = audioItag
                )
            }
            .distinctBy(YouTubeHlsAudioPlaylist::uri)
            .toList()
    }

    private fun parseAttributes(rawAttributes: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")
        pattern.findAll(rawAttributes).forEach { match ->
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            result[key] = rawValue.trim().removeSurrounding("\"")
        }
        return result
    }

    private fun resolveRelativeUri(baseUri: String?, candidate: String): String {
        if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)
        ) {
            return candidate
        }
        val resolvedBaseUri = baseUri?.takeIf { it.isNotBlank() } ?: return candidate
        return runCatching { URI(resolvedBaseUri).resolve(candidate).toString() }
            .getOrElse { candidate }
    }

    private fun parseAudioItag(url: String): Int? {
        val decoded = runCatching { URLDecoder.decode(url, Charsets.UTF_8.name()) }
            .getOrElse { url }
        return Regex("""(?:^|[;/?&])itag=(\d+)""")
            .findAll(decoded)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""/itag/(\d+)""")
                .find(decoded)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun parseContentLength(url: String): Long? {
        val decoded = runCatching { URLDecoder.decode(url, Charsets.UTF_8.name()) }
            .getOrElse { url }
        return Regex("""(?:^|[;/?&])clen=(\d+)""")
            .findAll(decoded)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    private fun estimateBitrate(audioItag: Int?, contentLength: Long?, durationMs: Long): Int {
        audioItagToBitrate(audioItag)?.let { return it }
        if (contentLength != null && durationMs > 0L) {
            return ((contentLength * 8_000L) / durationMs).toInt()
        }
        return 0
    }

    private fun audioItagToBitrate(itag: Int?): Int? {
        return when (itag) {
            139, 233, 249 -> 48_000
            140, 234, 250 -> 128_000
            141, 251 -> 256_000
            else -> null
        }
    }
}

private fun extractStreamQueryParameter(url: String, key: String): String? {
    val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
    return rawQuery.split('&')
        .asSequence()
        .mapNotNull { segment ->
            val resolvedKey = URLDecoder.decode(
                segment.substringBefore('='),
                Charsets.UTF_8.name()
            )
            if (resolvedKey.isBlank()) {
                null
            } else {
                resolvedKey to URLDecoder.decode(
                    segment.substringAfter('=', ""),
                    Charsets.UTF_8.name()
                )
            }
        }
        .firstOrNull { (resolvedKey, _) -> resolvedKey == key }
        ?.second
}

private fun replaceStreamQueryParameter(url: String, key: String, value: String): String {
    val pattern = Regex("([?&])${Regex.escape(key)}=[^&]*")
    return if (pattern.containsMatchIn(url)) {
        val match = pattern.find(url) ?: return url
        buildString(url.length + value.length) {
            append(url, 0, match.range.first)
            append(match.groupValues[1])
            append(key)
            append('=')
            append(URLEncoder.encode(value, Charsets.UTF_8.name()))
            append(url, match.range.last + 1, url.length)
        }
    } else {
        val separator = if (url.contains('?')) '&' else '?'
        buildString(url.length + key.length + value.length + 2) {
            append(url)
            append(separator)
            append(key)
            append('=')
            append(URLEncoder.encode(value, Charsets.UTF_8.name()))
        }
    }
}

private fun isYouTubeGoogleVideoStream(url: String): Boolean {
    val host = runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase(Locale.US)
        .orEmpty()
    if (!isYouTubeGoogleVideoHost(host)) {
        return false
    }
    return extractStreamQueryParameter(url, "source")
        ?.equals("youtube", ignoreCase = true) == true
}

class YouTubeMusicPlaybackRepository(
    private val okHttpClient: OkHttpClient,
    private val settings: SettingsRepository? = null,
    private val authProvider: () -> YouTubeAuthBundle = { YouTubeAuthBundle() },
    private val authAutoRefreshManager: YouTubeAuthAutoRefreshManager? = null,
    private val streamingCipherResolverFactory: ((String) -> YouTubeStreamingCipherResolver)? = null,
    applicationContext: Context? = null,
    poTokenProvider: YouTubePoTokenProvider? = null
) {
    private val downloader = NewPipeOkHttpDownloader(okHttpClient, authProvider)
    private val playableAudioCache = linkedMapOf<String, CachedPlayableAudio>()
    private val inFlightPlayableAudio = linkedMapOf<InFlightPlayableAudioRequest, Deferred<YouTubePlayableAudio?>>()
    private val inFlightBootstrapRequests = linkedMapOf<InFlightBootstrapRequest, Deferred<YouTubePlaybackBootstrap>>()
    private val inFlightPlayableAudioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ejsChallengeSolver = applicationContext?.let {
        YouTubeEjsChallengeSolver(it, okHttpClient)
    }
    private val poTokenProvider = poTokenProvider ?: applicationContext?.let {
        YouTubeWebPoTokenProvider(it, authProvider)
    }

    @Volatile
    private var bootstrapCache: YouTubePlaybackBootstrap? = null
    private val bootstrapRequestLock = Any()
    private val warmBootstrapLock = Any()

    @Volatile
    private var inFlightWarmBootstrap: Deferred<Unit>? = null

    @Volatile
    private var authCacheGeneration: Long = 0L
    @Volatile
    private var lastAuthFingerprint: String? = null

    suspend fun getBestPlayableAudio(
        videoId: String,
        preferredQualityOverride: String? = null,
        forceRefresh: Boolean = false,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false
    ): YouTubePlayableAudio? = withContext(Dispatchers.IO) {
        val resolveStartedAtMs = System.currentTimeMillis()
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = resolvePreferredQualityKey(preferredQualityOverride)
        val cacheKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        NPLogger.d(
            "YouTubeMusicPlayback",
            "getBestPlayableAudio: videoId=$videoId, quality=$preferredQualityKey, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a"
        )
        if (!forceRefresh) {
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect
            )?.let { cached ->
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "getBestPlayableAudio cache hit: videoId=$videoId, type=${cached.streamType}, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                )
                return@withContext cached
            }
        }
        resolvePlayableAudioShared(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = true,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = forceRefresh
        )
    }

    suspend fun prefetchPlayableAudioUrl(
        videoId: String,
        preferredQualityOverride: String? = null,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false
    ) = withContext(Dispatchers.IO) {
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = resolvePreferredQualityKey(preferredQualityOverride)
        val cacheKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        if (
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect
            ) != null
        ) {
            return@withContext
        }
        startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = false,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = false
        ).await()
    }

    fun kickoffPlayableAudioPrefetch(
        videoId: String,
        preferredQualityOverride: String,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false
    ) {
        syncAuthBoundCachesIfNeeded(authProvider().normalized())
        val preferredQualityKey = preferredQualityOverride.ifBlank { "very_high" }
        val cacheKey = if (preferM4a) "${preferredQualityKey}_m4a" else preferredQualityKey
        if (
            getCachedPlayableAudio(
                videoId = videoId,
                preferredQualityKey = cacheKey,
                requireDirect = requireDirect
            ) != null
        ) {
            return
        }
        startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = false,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = false
        )
    }

    suspend fun warmBootstrap() = withContext(Dispatchers.IO) {
        val initialAuth = authProvider().normalized()
        val shouldForceWarmRefresh = evaluateYouTubeAuthHealth(initialAuth).let { health ->
            health.activeCookieKeys.isNotEmpty() &&
                health.ageMs >= PLAYBACK_WARM_AUTH_REFRESH_AGE_MS
        }
        authAutoRefreshManager?.refreshIfNeeded(
            reason = "playback_warm_bootstrap",
            force = shouldForceWarmRefresh
        )
        val auth = authProvider().normalized()
        syncAuthBoundCachesIfNeeded(auth)
        if (!auth.hasLoginCookies()) {
            return@withContext
        }
        try {
            coroutineScope {
                val bootstrapDeferred = async {
                    bootstrap(auth = auth, forceRefresh = false)
                }
                val poTokenWarmJob = poTokenProvider?.let { provider ->
                    launch {
                        runCatching { provider.warmSession() }
                            .onFailure { error ->
                                if (error is CancellationException) {
                                    throw error
                                }
                                NPLogger.w(
                                    "YouTubeMusicPlayback",
                                    "Warm WebPo session failed",
                                    error
                                )
                            }
                    }
                }
                val bootstrap = bootstrapDeferred.await()
                val ejsWarmJob = ejsChallengeSolver?.let { solver ->
                    launch {
                        runCatching { solver.warmPlayerScript(bootstrap.playerJsUrl) }
                            .onFailure { error ->
                                if (error is CancellationException) {
                                    throw error
                                }
                                NPLogger.w(
                                    "YouTubeMusicPlayback",
                                    "Warm EJS player script failed",
                                    error
                                )
                            }
                    }
                }
                poTokenWarmJob?.join()
                ejsWarmJob?.join()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Warm bootstrap failed",
                error
            )
        }
    }

    fun warmBootstrapAsync() {
        val warmTask = synchronized(warmBootstrapLock) {
            inFlightWarmBootstrap
                ?.takeUnless { it.isCompleted || it.isCancelled }
                ?: run {
                    lateinit var created: Deferred<Unit>
                    created = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                        try {
                            warmBootstrap()
                        } finally {
                            synchronized(warmBootstrapLock) {
                                if (inFlightWarmBootstrap === created) {
                                    inFlightWarmBootstrap = null
                                }
                            }
                        }
                    }
                    inFlightWarmBootstrap = created
                    created
                }
        }
        if (!warmTask.isActive && !warmTask.isCompleted && !warmTask.isCancelled) {
            warmTask.start()
        }
    }

    fun clearAuthBoundCaches(cancelInFlightPlayableAudio: Boolean = true) {
        authCacheGeneration += 1L
        bootstrapCache = null
        lastAuthFingerprint = null
        synchronized(playableAudioCache) {
            playableAudioCache.clear()
        }
        synchronized(bootstrapRequestLock) {
            val deferreds = inFlightBootstrapRequests.values.toList()
            inFlightBootstrapRequests.clear()
            deferreds.forEach { deferred ->
                deferred.cancel(CancellationException("YouTube auth updated"))
            }
        }
        synchronized(warmBootstrapLock) {
            inFlightWarmBootstrap?.cancel(CancellationException("YouTube auth updated"))
            inFlightWarmBootstrap = null
        }
        synchronized(inFlightPlayableAudio) {
            val deferreds = inFlightPlayableAudio.values.toList()
            inFlightPlayableAudio.clear()
            if (cancelInFlightPlayableAudio) {
                deferreds.forEach { deferred ->
                    deferred.cancel(CancellationException("YouTube auth updated"))
                }
            }
        }
        poTokenProvider?.clearSession()
    }

    internal fun shouldClearAuthBoundCachesForFingerprintChange(
        previousFingerprint: String?,
        nextFingerprint: String
    ): Boolean {
        return previousFingerprint != null && previousFingerprint != nextFingerprint
    }

    private fun syncAuthBoundCachesIfNeeded(auth: YouTubeAuthBundle) {
        val fingerprint = auth.buildBootstrapAuthFingerprint(
            origin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        val previousFingerprint = lastAuthFingerprint
        if (previousFingerprint == fingerprint) {
            return
        }
        if (shouldClearAuthBoundCachesForFingerprintChange(previousFingerprint, fingerprint)) {
            clearAuthBoundCaches()
        }
        lastAuthFingerprint = fingerprint
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
            val preferred = YouTubeMusicLocaleResolver.preferred(locale)
            NewPipe.init(
                downloader,
                Localization(
                    preferred.hl.substringBefore('-'),
                    preferred.gl
                )
            )
            initialized = true
        }
    }

    private suspend fun resolvePlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean
    ): YouTubePlayableAudio? {
        val authGeneration = authCacheGeneration
        val playerResolution = resolvePlayerAudioViaPlayerApi(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = logFailure,
            preferM4a = preferM4a,
            forceRefresh = forceRefresh
        )
        playerResolution?.playableAudio?.let { playableAudio ->
            if (authGeneration == authCacheGeneration) {
                cachePlayableAudio(videoId, cacheKey, playableAudio)
            }
            return playableAudio
        }

        ensureInitialized()
        return resolvePlayableAudioViaNewPipe(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            logFailure = logFailure,
            preferM4a = preferM4a
        )?.mergeMetadataFrom(playerResolution?.metadata)
            ?.also { playableAudio ->
            if (authGeneration == authCacheGeneration) {
                cachePlayableAudio(videoId, cacheKey, playableAudio)
            }
        }
    }

    private suspend fun resolvePlayableAudioShared(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean
    ): YouTubePlayableAudio? {
        return startPlayableAudioResolution(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            logFailure = logFailure,
            preferM4a = preferM4a,
            cacheKey = cacheKey,
            forceRefresh = forceRefresh
        ).await()
    }

    private fun startPlayableAudioResolution(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        cacheKey: String,
        forceRefresh: Boolean
    ): Deferred<YouTubePlayableAudio?> {
        val request = InFlightPlayableAudioRequest(
            videoId = videoId,
            preferredQualityKey = preferredQualityKey,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            forceRefresh = forceRefresh
        )
        val deferred = synchronized(inFlightPlayableAudio) {
            inFlightPlayableAudio[request]?.also {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "join in-flight playable audio resolve: videoId=$videoId, quality=$preferredQualityKey, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a"
                )
            } ?: run {
                val created: Deferred<YouTubePlayableAudio?> = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                    val startedAtMs = System.currentTimeMillis()
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "start playable audio resolve: videoId=$videoId, quality=$preferredQualityKey, forceRefresh=$forceRefresh, requireDirect=$requireDirect, preferM4a=$preferM4a"
                    )
                    try {
                        val resolved = resolvePlayableAudio(
                            videoId = videoId,
                            preferredQualityKey = preferredQualityKey,
                            requireDirect = requireDirect,
                            logFailure = logFailure,
                            preferM4a = preferM4a,
                            cacheKey = cacheKey,
                            forceRefresh = forceRefresh
                        )
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "finish playable audio resolve: videoId=$videoId, success=${resolved != null}, type=${resolved?.streamType}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                        )
                        resolved
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "playable audio resolve failed: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
                        )
                        throw error
                    }
                }
                created.invokeOnCompletion {
                    synchronized(inFlightPlayableAudio) {
                        if (inFlightPlayableAudio[request] === created) {
                            inFlightPlayableAudio.remove(request)
                        }
                    }
                }
                inFlightPlayableAudio[request] = created
                created
            }
        }
        if (!deferred.isActive && !deferred.isCompleted && !deferred.isCancelled) {
            deferred.start()
        }
        return deferred
    }

    private suspend fun resolvePlayerAudioViaPlayerApi(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean,
        logFailure: Boolean,
        preferM4a: Boolean,
        forceRefresh: Boolean
    ): PlayerAudioResolution? {
        val auth = authProvider().normalized()
        if (!auth.hasLoginCookies()) {
            return null
        }

        return try {
            fetchPlayerAudioViaPlayerApi(
                videoId = videoId,
                preferredQualityKey = preferredQualityKey,
                auth = auth,
                requireDirect = requireDirect,
                preferM4a = preferM4a,
                forceRefresh = forceRefresh
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (logFailure) {
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "player API resolve failed for $videoId (authUsable=${auth.isUsable()}, hasLoginCookies=${auth.hasLoginCookies()})",
                    error
                )
            }
            null
        }
    }

    private suspend fun fetchPlayerAudioViaPlayerApi(
        videoId: String,
        preferredQualityKey: String,
        auth: YouTubeAuthBundle,
        requireDirect: Boolean = false,
        preferM4a: Boolean = false,
        forceRefresh: Boolean = false
    ): PlayerAudioResolution {
        val resolveStartedAtMs = System.currentTimeMillis()
        val bootstrapStartedAtMs = System.currentTimeMillis()
        var bootstrap = bootstrap(auth, forceRefresh = forceRefresh)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "player bootstrap ready: videoId=$videoId, forceRefresh=$forceRefresh, elapsedMs=${playbackElapsedMs(bootstrapStartedAtMs)}"
        )
        var lastError: IOException? = null
        var bestMetadata: YouTubeAudioMetadata? = null

        repeat(PLAYER_REQUEST_MAX_ATTEMPTS) { attempt ->
            val poTokenForceRefresh = forceRefresh || attempt > 0
            val allowBlockingWebRemixPoToken = requireDirect || attempt > 0
            val cipherResolver = createStreamingCipherResolver(
                videoId = videoId,
                playerJsUrl = bootstrap.playerJsUrl
            )
            val requestLocaleCandidates = playerRequestLocaleCandidates()
            var bestPlayableAudio: YouTubePlayableAudio? = null
            var bestPlayableAudioClientName: String? = null
            var shouldRefreshBootstrapBeforeFallback = false
            profileLoop@ for (profile in playerClientProfiles()) {
                for ((localeIndex, requestLocale) in requestLocaleCandidates.withIndex()) {
                    try {
                        val root = postPlayerRequest(
                            videoId = videoId,
                            auth = auth,
                            bootstrap = bootstrap,
                            profile = profile,
                            requestLocale = requestLocale
                        )
                        val playability = YouTubeMusicPlaybackParser.parsePlayabilityStatus(root)
                        NPLogger.d(
                            "YouTubeMusicPlayback",
                            "player client response: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, status=${playability.status}, reason=${playability.reason.take(80)}"
                        )
                        if (!playability.status.equals("OK", ignoreCase = true) &&
                            localeIndex < requestLocaleCandidates.lastIndex
                        ) {
                            val fallbackLocale = requestLocaleCandidates[localeIndex + 1]
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "retry player locale fallback: videoId=$videoId, client=${profile.clientName}, from=${requestLocale.gl}/${requestLocale.hl}, to=${fallbackLocale.gl}/${fallbackLocale.hl}, status=${playability.status}"
                            )
                            continue
                        }

                        val metadata = YouTubeMusicPlaybackParser.parsePreferredAudioMetadata(
                            root = root,
                            preferredQualityKey = preferredQualityKey,
                            preferM4a = preferM4a
                        )
                        bestMetadata = bestMetadata.mergePreferred(metadata)
                        val playableAudio = if (playability.status == "OK") {
                            val webRemixPoTokenPrefetch = if (
                                profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME &&
                                shouldPrefetchWebRemixPoToken(root)
                            ) {
                                prefetchWebRemixPoToken(
                                    videoId = videoId,
                                    bootstrap = bootstrap,
                                    forceRefresh = poTokenForceRefresh
                                )
                            } else {
                                null
                            }
                            val parsedDirectPlayableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
                                root = root,
                                preferredQualityKey = preferredQualityKey,
                                preferM4a = preferM4a,
                                cipherResolver = cipherResolver
                            )
                            val directPlayableAudio = maybeAttachGvsPoToken(
                                playableAudio = parsedDirectPlayableAudio,
                                profile = profile,
                                videoId = videoId,
                                bootstrap = bootstrap,
                                forceRefresh = poTokenForceRefresh,
                                prefetchedPoToken = webRemixPoTokenPrefetch,
                                allowBlockingAcquisition = allowBlockingWebRemixPoToken
                            )
                            val hlsPlayableAudio = try {
                                if (
                                    requireDirect ||
                                    directPlayableAudio != null ||
                                    bestPlayableAudio?.streamType == YouTubePlayableStreamType.DIRECT
                                ) {
                                    null
                                } else {
                                    // 已有 direct 候选时不再额外拉 manifest，减少无效请求和风控暴露面
                                    resolveHlsPlayableAudio(
                                        root = root,
                                        preferredQualityKey = preferredQualityKey,
                                        auth = auth,
                                        durationMs = metadata?.durationMs ?: 0L,
                                        profile = profile,
                                        videoId = videoId,
                                        bootstrap = bootstrap,
                                        forceRefresh = forceRefresh || attempt > 0,
                                        prefetchedPoToken = webRemixPoTokenPrefetch,
                                        allowBlockingAcquisition = allowBlockingWebRemixPoToken
                                    )
                                }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                lastError = error as? IOException ?: IOException(error)
                                null
                            }
                            selectPreferredPlayableAudio(
                                current = hlsPlayableAudio,
                                incoming = directPlayableAudio,
                                currentClientName = profile.clientName,
                                incomingClientName = profile.clientName
                            )
                        } else {
                            null
                        }
                        if (playability.status == "OK" && playableAudio != null) {
                            val resolvedPlayableAudio = selectPreferredPlayableAudio(
                                current = bestPlayableAudio,
                                incoming = playableAudio,
                                currentClientName = bestPlayableAudioClientName,
                                incomingClientName = profile.clientName
                            ) ?: continue@profileLoop
                            if (resolvedPlayableAudio === playableAudio) {
                                bestPlayableAudioClientName = profile.clientName
                            }
                            bestPlayableAudio = resolvedPlayableAudio
                            if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME &&
                                resolvedPlayableAudio === playableAudio &&
                                resolvedPlayableAudio.streamType == YouTubePlayableStreamType.DIRECT
                            ) {
                                // 官方 WEB_REMIX 直链已经足够稳定，命中后立即停止后续 fallback，
                                // 避免额外触发 IOS/TV 探测而增加风控概率
                                NPLogger.d(
                                    "YouTubeMusicPlayback",
                                    "player resolve satisfied by WEB_REMIX direct: videoId=$videoId, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                                )
                                return PlayerAudioResolution(
                                    playableAudio = resolvedPlayableAudio.mergeMetadataFrom(bestMetadata),
                                    metadata = bestMetadata
                                )
                            }
                            continue@profileLoop
                        }

                        val description = buildString {
                            append("YouTube player unavailable via ")
                            append(profile.clientName)
                            append(" @ ")
                            append(requestLocale.gl)
                            append('/')
                            append(requestLocale.hl)
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
                        if (shouldRetryWithFreshBootstrapBeforeFallback(
                                profile = profile,
                                playability = playability,
                                attempt = attempt,
                                forceRefresh = forceRefresh
                            )
                        ) {
                            shouldRefreshBootstrapBeforeFallback = true
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "refresh bootstrap before fallback: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, status=${playability.status}, reason=${playability.reason.take(80)}"
                            )
                            break
                        }
                    } catch (error: IOException) {
                        lastError = error
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "player client request failed: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, error=${error.message}"
                        )
                        if (shouldRetryWithFreshBootstrapAfterRequestFailure(
                                profile = profile,
                                attempt = attempt,
                                forceRefresh = forceRefresh
                            )
                        ) {
                            shouldRefreshBootstrapBeforeFallback = true
                            NPLogger.d(
                                "YouTubeMusicPlayback",
                                "refresh bootstrap after request failure: videoId=$videoId, client=${profile.clientName}, locale=${requestLocale.gl}/${requestLocale.hl}, error=${error.message}"
                            )
                            break
                        }
                    }
                }
                if (shouldRefreshBootstrapBeforeFallback) {
                    break
                }
            }

            if (bestPlayableAudio != null) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "player resolve finished: videoId=$videoId, type=${bestPlayableAudio.streamType}, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
                )
                return PlayerAudioResolution(
                    playableAudio = bestPlayableAudio.mergeMetadataFrom(bestMetadata),
                    metadata = bestMetadata
                )
            }

            if (attempt < PLAYER_REQUEST_MAX_ATTEMPTS - 1) {
                if (shouldRefreshBootstrapBeforeFallback) {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "player resolve refresh bootstrap immediately: videoId=$videoId, attempt=${attempt + 1}, error=${lastError?.message.orEmpty()}"
                    )
                } else {
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "player resolve retry: videoId=$videoId, attempt=${attempt + 1}, error=${lastError?.message.orEmpty()}"
                    )
                }
                bootstrap = bootstrap(auth, forceRefresh = true)
            }
        }

        if (bestMetadata != null) {
            NPLogger.w(
                "YouTubeMusicPlayback",
                "player resolve returned metadata only: videoId=$videoId, elapsedMs=${playbackElapsedMs(resolveStartedAtMs)}"
            )
            return PlayerAudioResolution(metadata = bestMetadata)
        }
        throw lastError ?: IOException("YouTube Music player request failed")
    }

    private suspend fun maybeAttachGvsPoToken(
        playableAudio: YouTubePlayableAudio?,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>? = null,
        allowBlockingAcquisition: Boolean
    ): YouTubePlayableAudio? {
        if (playableAudio == null ||
            playableAudio.streamType != YouTubePlayableStreamType.DIRECT ||
            profile.clientName != YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME
        ) {
            return playableAudio
        }

        val startedAtMs = System.currentTimeMillis()
        val streamUrl = playableAudio.url
        if (!isYouTubeGoogleVideoStream(streamUrl)) {
            return playableAudio
        }

        val existingPoToken = extractStreamQueryParameter(streamUrl, "pot")
        if (!existingPoToken.isNullOrBlank()) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "reuse existing stream PO token: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, forceRefresh=$forceRefresh"
            )
            return playableAudio
        }

        val poToken = resolveWebRemixPoToken(
            videoId = videoId,
            bootstrap = bootstrap,
            forceRefresh = forceRefresh,
            prefetchedPoToken = prefetchedPoToken,
            allowBlockingAcquisition = allowBlockingAcquisition
        )
            .orEmpty()
            .ifBlank {
                if (existingPoToken.isNullOrBlank()) {
                    NPLogger.w(
                        "YouTubeMusicPlayback",
                        "Missing GVS PO token for WEB_REMIX direct stream: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                    )
                    return null
                }
                return playableAudio
            }

        NPLogger.d(
            "YouTubeMusicPlayback",
            "attached stream PO token: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, forceRefresh=$forceRefresh"
        )
        return playableAudio.copy(
            url = replaceStreamQueryParameter(streamUrl, "pot", poToken)
        )
    }

    private fun prefetchWebRemixPoToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean
    ): Deferred<String?>? {
        val provider = poTokenProvider ?: return null
        if (bootstrap.visitorData.isBlank()) {
            return null
        }
        return inFlightPlayableAudioScope.async {
            val startedAtMs = System.currentTimeMillis()
            runCatching {
                provider.getWebRemixGvsPoToken(
                    videoId = videoId,
                    visitorData = bootstrap.visitorData,
                    remoteHost = bootstrap.remoteHost,
                    forceRefresh = forceRefresh
                )
            }.onSuccess { token ->
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "prefetch GVS PO token finished: videoId=$videoId, hasToken=${!token.isNullOrBlank()}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "prefetch GVS PO token failed: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
                )
            }.getOrNull()
        }
    }

    private fun shouldPrefetchWebRemixPoToken(root: JSONObject): Boolean {
        val streamingData = root.optJSONObject("streamingData") ?: return false
        val hlsManifestUrl = streamingData.optString("hlsManifestUrl").trim()
        if (hlsManifestUrl.isNotBlank()) {
            return !hasWebRemixManifestPoToken(hlsManifestUrl)
        }

        val formatArrays = listOfNotNull(
            streamingData.optJSONArray("adaptiveFormats"),
            streamingData.optJSONArray("formats")
        )
        formatArrays.forEach { formats ->
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val mimeType = format.optString("mimeType")
                    .substringBefore(';')
                    .trim()
                if (!mimeType.startsWith("audio/")) {
                    continue
                }

                val directUrl = format.optString("url").trim()
                if (directUrl.isNotBlank()) {
                    if (
                        isYouTubeGoogleVideoStream(directUrl) &&
                        extractStreamQueryParameter(directUrl, "pot").isNullOrBlank()
                    ) {
                        return true
                    }
                    continue
                }

                val cipher = format.optString("signatureCipher")
                    .ifBlank { format.optString("cipher") }
                    .trim()
                if (cipher.isNotBlank()) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun awaitPrefetchedWebRemixPoToken(
        videoId: String,
        prefetchedPoToken: Deferred<String?>?,
        timeoutMs: Long? = null
    ): String? {
        return prefetchedPoToken
            ?.let { deferred ->
                runCatching {
                    when {
                        timeoutMs == null -> deferred.await()
                        deferred.isCompleted -> deferred.await()
                        else -> withTimeoutOrNull(timeoutMs) { deferred.await() }
                    }
                }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "Await prefetched GVS PO token failed: videoId=$videoId, error=${error.message}"
                        )
                    }
                    .getOrNull()
            }
    }

    private suspend fun resolveWebRemixPoToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>?,
        allowBlockingAcquisition: Boolean
    ): String {
        val prefetchedToken = awaitPrefetchedWebRemixPoToken(
            videoId = videoId,
            prefetchedPoToken = prefetchedPoToken,
            timeoutMs = if (allowBlockingAcquisition) {
                null
            } else {
                WEB_REMIX_PO_TOKEN_PREFETCH_JOIN_TIMEOUT_MS
            }
        ).orEmpty()
        if (prefetchedToken.isNotBlank()) {
            return prefetchedToken
        }
        if (!allowBlockingAcquisition) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "skip blocking GVS PO token mint for fallback-eligible request: videoId=$videoId"
            )
            return ""
        }
        return poTokenProvider?.getWebRemixGvsPoToken(
            videoId = videoId,
            visitorData = bootstrap.visitorData,
            remoteHost = bootstrap.remoteHost,
            forceRefresh = forceRefresh
        ).orEmpty()
    }

    private fun createStreamingCipherResolver(
        videoId: String,
        playerJsUrl: String
    ): YouTubeStreamingCipherResolver {
        streamingCipherResolverFactory?.let { factory ->
            return factory(videoId)
        }
        ensureInitialized()

        val signatureErrorLogged = AtomicBoolean(false)
        val throttlingErrorLogged = AtomicBoolean(false)
        val signatureEjsFallbackLogged = AtomicBoolean(false)
        val throttlingEjsFallbackLogged = AtomicBoolean(false)
        val signatureResolutionLogged = AtomicBoolean(false)
        val throttlingResolutionLogged = AtomicBoolean(false)

        fun maybeLogResolution(
            challengeType: String,
            source: String,
            elapsedMs: Long,
            logged: AtomicBoolean
        ) {
            if (elapsedMs >= STREAMING_CIPHER_LOG_THRESHOLD_MS || logged.compareAndSet(false, true)) {
                NPLogger.d(
                    "YouTubeMusicPlayback",
                    "Resolved $challengeType via $source for $videoId elapsedMs=$elapsedMs"
                )
            }
        }

        return object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? {
                val resolvedPlayerJsUrl = playerJsUrl.ifBlank { bootstrapCache?.playerJsUrl.orEmpty() }
                val skipSignatureNewPipe = NewPipeFallbackTracker.maybeSkipSignature(resolvedPlayerJsUrl)
                if (skipSignatureNewPipe && signatureErrorLogged.compareAndSet(false, true)) {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "Skip NewPipe signature for $videoId because player.js is already flagged"
                    )
                }
                return runBlocking {
                    val newPipeDeferred = if (skipSignatureNewPipe) {
                        null
                    } else {
                        async(Dispatchers.Default) {
                            val startedAtMs = System.currentTimeMillis()
                            val resolvedByNewPipe = runCatching {
                                YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                                    videoId,
                                    encryptedSignature
                                )
                            }.onFailure { error ->
                                if (signatureErrorLogged.compareAndSet(false, true)) {
                                    NPLogger.w(
                                        "YouTubeMusicPlayback",
                                        "Failed to deobfuscate streaming signature for $videoId via NewPipe elapsedMs=${playbackElapsedMs(startedAtMs)}",
                                        error
                                    )
                                }
                            }.getOrNull()?.takeIf { it.isNotBlank() && it != encryptedSignature }
                            ChallengeCandidateResult(
                                source = "NEWPIPE",
                                value = resolvedByNewPipe,
                                elapsedMs = playbackElapsedMs(startedAtMs)
                            )
                        }
                    }
                    val ejsDeferred = if (resolvedPlayerJsUrl.isBlank()) {
                        null
                    } else {
                        async(Dispatchers.IO) {
                            val startedAtMs = System.currentTimeMillis()
                            val ejsResult = runCatching {
                                ejsChallengeSolver?.solveDetailed(
                                    playerJsUrl = resolvedPlayerJsUrl,
                                    encryptedSignature = encryptedSignature
                                )
                            }.getOrElse { error ->
                                YouTubeJsChallengeSolveResult(
                                    status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                    detail = "solveDetailed threw unexpectedly",
                                    cause = error
                                )
                            } ?: YouTubeJsChallengeSolveResult(
                                status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                detail = "ejsChallengeSolver is unavailable"
                            )
                            val elapsedMs = playbackElapsedMs(startedAtMs)
                            val resolvedByEjs = ejsResult.solution.signature
                                ?.takeIf { it.isNotBlank() && it != encryptedSignature }
                            if (resolvedByEjs == null &&
                                ejsResult.status != YouTubeJsChallengeSolveStatus.SUCCESS &&
                                signatureEjsFallbackLogged.compareAndSet(false, true)
                            ) {
                                NPLogger.w(
                                    "YouTubeMusicPlayback",
                                    "EJS signature fallback failed for $videoId: ${ejsResult.summary()}, elapsedMs=$elapsedMs",
                                    ejsResult.cause
                                )
                            }
                            ChallengeCandidateResult(
                                source = "EJS_FALLBACK",
                                value = resolvedByEjs,
                                elapsedMs = elapsedMs
                            )
                        }
                    }
                    val winner = awaitFirstChallengeSuccess(listOfNotNull(newPipeDeferred, ejsDeferred))
                    if (winner != null) {
                        maybeLogResolution(
                            challengeType = "signature",
                            source = winner.source,
                            elapsedMs = winner.elapsedMs,
                            logged = signatureResolutionLogged
                        )
                        return@runBlocking winner.value
                    }
                    val newPipeResult = newPipeDeferred?.await()
                    if (!skipSignatureNewPipe && newPipeResult?.value == null) {
                        NewPipeFallbackTracker.recordSignatureFailure(resolvedPlayerJsUrl)
                    }
                    return@runBlocking null
                }
            }

            override fun resolveStreamingUrl(url: String): String {
                val obfuscatedN = extractStreamQueryParameter(url, "n") ?: return url
                val resolvedPlayerJsUrl = playerJsUrl.ifBlank { bootstrapCache?.playerJsUrl.orEmpty() }
                val skipThrottlingNewPipe = NewPipeFallbackTracker.maybeSkipThrottling(resolvedPlayerJsUrl)
                if (skipThrottlingNewPipe && throttlingErrorLogged.compareAndSet(false, true)) {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "Skip NewPipe throttling for $videoId because player.js is already flagged"
                    )
                }
                return runBlocking {
                    val newPipeDeferred = if (skipThrottlingNewPipe) {
                        null
                    } else {
                        async(Dispatchers.Default) {
                            val startedAtMs = System.currentTimeMillis()
                            val resolvedByNewPipe = runCatching {
                                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                                    videoId,
                                    url
                                )
                            }.onFailure { error ->
                                if (throttlingErrorLogged.compareAndSet(false, true)) {
                                    NPLogger.w(
                                        "YouTubeMusicPlayback",
                                        "Failed to deobfuscate throttling parameter for $videoId via NewPipe elapsedMs=${playbackElapsedMs(startedAtMs)}",
                                        error
                                    )
                                }
                            }.getOrNull()?.takeIf { it.isNotBlank() && it != url }
                            ChallengeCandidateResult(
                                source = "NEWPIPE",
                                value = resolvedByNewPipe,
                                elapsedMs = playbackElapsedMs(startedAtMs)
                            )
                        }
                    }
                    val ejsDeferred = if (resolvedPlayerJsUrl.isBlank()) {
                        null
                    } else {
                        async(Dispatchers.IO) {
                            val startedAtMs = System.currentTimeMillis()
                            val ejsResult = runCatching {
                                ejsChallengeSolver?.solveDetailed(
                                    playerJsUrl = resolvedPlayerJsUrl,
                                    throttlingParameter = obfuscatedN
                                )
                            }.getOrElse { error ->
                                YouTubeJsChallengeSolveResult(
                                    status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                    detail = "solveDetailed threw unexpectedly",
                                    cause = error
                                )
                            } ?: YouTubeJsChallengeSolveResult(
                                status = YouTubeJsChallengeSolveStatus.SCRIPT_EVALUATION_FAILED,
                                detail = "ejsChallengeSolver is unavailable"
                            )
                            val elapsedMs = playbackElapsedMs(startedAtMs)
                            val resolvedByEjs = ejsResult.solution.throttlingParameter
                                ?.takeIf { it.isNotBlank() && it != obfuscatedN }
                                ?.let { replaceStreamQueryParameter(url, "n", it) }
                            if (resolvedByEjs == null &&
                                ejsResult.status != YouTubeJsChallengeSolveStatus.SUCCESS &&
                                throttlingEjsFallbackLogged.compareAndSet(false, true)
                            ) {
                                NPLogger.w(
                                    "YouTubeMusicPlayback",
                                    "EJS throttling fallback failed for $videoId: ${ejsResult.summary()}, elapsedMs=$elapsedMs",
                                    ejsResult.cause
                                )
                            }
                            ChallengeCandidateResult(
                                source = "EJS_FALLBACK",
                                value = resolvedByEjs,
                                elapsedMs = elapsedMs
                            )
                        }
                    }
                    val winner = awaitFirstChallengeSuccess(listOfNotNull(newPipeDeferred, ejsDeferred))
                    if (winner != null) {
                        maybeLogResolution(
                            challengeType = "throttling",
                            source = winner.source,
                            elapsedMs = winner.elapsedMs,
                            logged = throttlingResolutionLogged
                        )
                        return@runBlocking winner.value ?: url
                    }
                    val newPipeResult = newPipeDeferred?.await()
                    if (!skipThrottlingNewPipe && newPipeResult?.value == null) {
                        NewPipeFallbackTracker.recordThrottlingFailure(resolvedPlayerJsUrl)
                    }
                    return@runBlocking url
                }
            }
        }
    }

    private suspend fun resolveHlsPlayableAudio(
        root: JSONObject,
        preferredQualityKey: String,
        auth: YouTubeAuthBundle,
        durationMs: Long,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>? = null,
        allowBlockingAcquisition: Boolean
    ): YouTubePlayableAudio? {
        val hlsManifestUrl = root.optJSONObject("streamingData")
            ?.optString("hlsManifestUrl")
            .orEmpty()
            .trim()
        if (hlsManifestUrl.isBlank()) {
            return null
        }
        val resolvedManifestUrl = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            if (hasWebRemixManifestPoToken(hlsManifestUrl)) {
                hlsManifestUrl
            } else {
                val poToken = resolveWebRemixPoToken(
                    videoId = videoId,
                    bootstrap = bootstrap,
                    forceRefresh = forceRefresh,
                    prefetchedPoToken = prefetchedPoToken,
                    allowBlockingAcquisition = allowBlockingAcquisition
                )
                if (poToken.isBlank()) {
                    if (poTokenProvider == null) {
                        hlsManifestUrl
                    } else {
                        return null
                    }
                } else {
                    appendWebRemixManifestPoToken(hlsManifestUrl, poToken)
                }
            }
        } else {
            hlsManifestUrl
        }

        val requestAuth = buildBootstrapRequestAuth(auth = auth, bootstrap = bootstrap)
        val masterManifest = executeText(buildYouTubeStreamRequest(resolvedManifestUrl, requestAuth))
        val selectedAudioPlaylist = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = masterManifest,
            masterManifestUrl = resolvedManifestUrl,
            preferredQualityKey = preferredQualityKey,
            durationMs = durationMs
        ) ?: return null

        return YouTubePlayableAudio(
            url = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
                carryForwardWebRemixManifestPoToken(
                    masterManifestUrl = resolvedManifestUrl,
                    playlistUrl = selectedAudioPlaylist.uri
                )
            } else {
                selectedAudioPlaylist.uri
            },
            durationMs = durationMs,
            mimeType = MimeTypes.APPLICATION_M3U8,
            contentLength = selectedAudioPlaylist.contentLength,
            streamType = YouTubePlayableStreamType.HLS,
            bitrateKbps = selectedAudioPlaylist.estimatedBitrate
                .takeIf { it > 0 }
                ?.let { (it + 500) / 1000 }
        )
    }

    private fun appendWebRemixManifestPoToken(
        manifestUrl: String,
        poToken: String
    ): String {
        if (manifestUrl.isBlank() || poToken.isBlank() || hasWebRemixManifestPoToken(manifestUrl)) {
            return manifestUrl
        }
        val uri = runCatching { URI(manifestUrl) }.getOrNull()
            ?: return replaceStreamQueryParameter(manifestUrl, "pot", poToken)
        val rawPath = uri.rawPath.orEmpty()
        if (!rawPath.contains("/api/manifest/")) {
            return replaceStreamQueryParameter(manifestUrl, "pot", poToken)
        }
        val resolvedPath = if (rawPath.endsWith("/")) {
            "${rawPath}pot/$poToken"
        } else {
            "$rawPath/pot/$poToken"
        }
        return runCatching {
            URI(
                uri.scheme,
                uri.rawAuthority,
                resolvedPath,
                uri.rawQuery,
                uri.rawFragment
            ).toString()
        }.getOrElse {
            replaceStreamQueryParameter(manifestUrl, "pot", poToken)
        }
    }

    private fun hasWebRemixManifestPoToken(manifestUrl: String): Boolean {
        if (manifestUrl.isBlank()) {
            return false
        }
        return !extractStreamQueryParameter(manifestUrl, "pot").isNullOrBlank() ||
            "/pot/" in manifestUrl
    }

    private fun carryForwardWebRemixManifestPoToken(
        masterManifestUrl: String,
        playlistUrl: String
    ): String {
        if (playlistUrl.isBlank()) {
            return playlistUrl
        }
        val existingPoToken = extractStreamQueryParameter(playlistUrl, "pot")
        if (!existingPoToken.isNullOrBlank() || "/pot/" in playlistUrl) {
            return playlistUrl
        }
        val poTokenFromQuery = extractStreamQueryParameter(masterManifestUrl, "pot")
        if (!poTokenFromQuery.isNullOrBlank()) {
            return replaceStreamQueryParameter(playlistUrl, "pot", poTokenFromQuery)
        }
        val poTokenFromPath = Regex("/pot/([^/?#]+)")
            .find(masterManifestUrl)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (poTokenFromPath.isBlank()) {
            return playlistUrl
        }
        return appendWebRemixManifestPoToken(playlistUrl, poTokenFromPath)
    }

    private fun buildYouTubeStreamRequest(
        url: String,
        auth: YouTubeAuthBundle
    ): Request {
        val headers = auth.buildYouTubeStreamRequestHeaders(
            refererOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
            streamUrl = url
        )
        return Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .build()
    }

    private fun buildBootstrapRequestAuth(
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        origin: String = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
    ): YouTubeAuthBundle {
        return auth.copy(
            cookieHeader = bootstrap.cookieHeader,
            cookies = emptyMap(),
            authorization = auth.authorization,
            xGoogAuthUser = bootstrap.sessionIndex,
            origin = origin,
            userAgent = bootstrap.userAgent.ifBlank { auth.userAgent }
        ).normalized(savedAt = auth.savedAt)
    }

    private fun postPlayerRequest(
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        profile: YouTubePlayerClientProfile,
        requestLocale: YouTubeMusicRequestLocale
    ): JSONObject {
        val startedAtMs = System.currentTimeMillis()
        val requestUrl = resolvePlayerRequestUrl(profile, bootstrap, videoId)
        val origin = resolvePlayerRequestOrigin(profile)
        val requestAuth = buildBootstrapRequestAuth(
            auth = auth,
            bootstrap = bootstrap,
            origin = origin
        )
        val clientVersion = resolvePlayerClientVersion(profile, bootstrap)
        val userAgent = resolvePlayerRequestUserAgent(profile, bootstrap)
        val webRemixMetadata = if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            buildWebRemixRequestMetadata(videoId)
        } else {
            null
        }
        val body = buildPlayerRequestBody(
            videoId = videoId,
            profile = profile,
            bootstrap = bootstrap,
            requestLocale = requestLocale,
            clientVersion = clientVersion,
            userAgent = userAgent,
            webRemixMetadata = webRemixMetadata
        )
        val requestHeaders = linkedMapOf(
            "Cookie" to bootstrap.cookieHeader,
            "User-Agent" to userAgent,
            "Accept-Language" to requestLocale.acceptLanguage,
            "Content-Type" to "application/json",
            "X-Goog-AuthUser" to requestAuth.resolveXGoogAuthUser(
                fallback = bootstrap.sessionIndex
            ),
            "X-Goog-Visitor-Id" to bootstrap.visitorData,
            "X-YouTube-Client-Name" to profile.clientId,
            "X-YouTube-Client-Version" to clientVersion
        )
        if (profile.clientName != "WEB_REMIX") {
            requestHeaders["X-Goog-Api-Format-Version"] = YOUTUBE_PLAYER_API_FORMAT_VERSION
        }
        requestHeaders["Origin"] = origin
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            requestHeaders["X-YouTube-Bootstrap-Logged-In"] = requestAuth.hasLoginCookies().toString()
            NPLogger.d(
                "YouTubeMusicPlayback",
                "WEB_REMIX request context: videoId=$videoId, locale=${requestLocale.gl}/${requestLocale.hl}, originalUrl=${webRemixMetadata?.originalUrl.orEmpty()}, referer=${webRemixMetadata?.watchUrl.orEmpty()}, remoteHost=${bootstrap.remoteHost.ifBlank { "<blank>" }}, signatureTimestamp=${bootstrap.signatureTimestamp}, clientVersion=$clientVersion"
            )
        }
        requestHeaders["Referer"] = webRemixMetadata?.watchUrl ?: "$origin/"

        val userSessionId = bootstrap.userSessionId.takeIf { bootstrap.loggedIn }.orEmpty()
        requestAuth.resolveAuthorizationHeader(origin = origin, userSessionId = userSessionId)
            .takeIf { it.isNotBlank() }
            ?.let {
                requestHeaders["Authorization"] = it
                requestHeaders["X-Origin"] = origin
            }
        if (profile.clientName == YOUTUBE_PLAYER_TV_CLIENT_NAME) {
            bootstrap.delegatedSessionId
                .takeIf { it.isNotBlank() }
                ?.let { requestHeaders["X-Goog-PageId"] = it }
            if (bootstrap.loggedIn) {
                requestHeaders["X-Youtube-Bootstrap-Logged-In"] = "true"
            }
        }

        val request = Request.Builder()
            .url(requestUrl)
            .apply {
                requestHeaders.forEach { (name, value) ->
                    header(name, value)
                }
            }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val root = executeJson(request)
        NPLogger.d(
            "YouTubeMusicPlayback",
            "postPlayerRequest ok: videoId=$videoId, client=${profile.clientName}, clientVersion=$clientVersion, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return profile.responseField
            ?.let { root.optJSONObject(it) ?: root }
            ?: root
    }

    private fun buildPlayerRequestBody(
        videoId: String,
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        requestLocale: YouTubeMusicRequestLocale,
        clientVersion: String,
        userAgent: String,
        webRemixMetadata: YouTubeWebRemixRequestMetadata? = null
    ): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", clientVersion)
            .put("platform", profile.platform)
            .put("hl", requestLocale.hl)
            .put("gl", requestLocale.gl)
            .put("utcOffsetMinutes", utcOffsetMinutes())
        if (profile.clientScreen.isNotBlank()) {
            clientContext.put("clientScreen", profile.clientScreen)
        }
        if (bootstrap.visitorData.isNotBlank()) {
            clientContext.put("visitorData", bootstrap.visitorData)
        }
        if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME && userAgent.isNotBlank()) {
            // WEB_REMIX 需要更接近浏览器 watch 页的 client 上下文，避免退回到风险更高的移动端直链
            clientContext.put("deviceMake", "")
            clientContext.put("deviceModel", "")
            clientContext.put("userAgent", ensureGfeUserAgent(userAgent))
            clientContext.put("browserName", resolveBrowserName(userAgent))
            clientContext.put("browserVersion", resolveBrowserVersion(userAgent))
            clientContext.put("timeZone", currentTimeZoneId())
            clientContext.put("originalUrl", webRemixMetadata?.originalUrl.orEmpty())
            clientContext.put("acceptHeader", YOUTUBE_PLAYER_WEB_REMIX_ACCEPT_HEADER)
            clientContext.put("clientFormFactor", YOUTUBE_PLAYER_WEB_REMIX_CLIENT_FORM_FACTOR)
            clientContext.put("playerType", YOUTUBE_PLAYER_WEB_REMIX_PLAYER_TYPE)
            clientContext.put("userInterfaceTheme", YOUTUBE_PLAYER_WEB_REMIX_UI_THEME)
            clientContext.put("connectionType", YOUTUBE_PLAYER_WEB_REMIX_CONNECTION_TYPE)
            clientContext.put("screenWidthPoints", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS)
            clientContext.put("screenHeightPoints", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS)
            clientContext.put("screenPixelDensity", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_PIXEL_DENSITY)
            clientContext.put("screenDensityFloat", YOUTUBE_PLAYER_WEB_REMIX_SCREEN_DENSITY_FLOAT)
            clientContext.put(
                "tvAppInfo",
                JSONObject().put("livingRoomAppMode", "LIVING_ROOM_APP_MODE_UNSPECIFIED")
            )
            val configInfo = JSONObject()
            bootstrap.appInstallData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("appInstallData", it)
            }
            bootstrap.coldConfigData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("coldConfigData", it)
            }
            bootstrap.coldHashData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("coldHashData", it)
            }
            bootstrap.hotHashData.takeIf { it.isNotBlank() }?.let {
                configInfo.put("hotHashData", it)
            }
            clientContext.put("configInfo", configInfo)
            bootstrap.rolloutToken.takeIf { it.isNotBlank() }?.let {
                clientContext.put("rolloutToken", it)
            }
            bootstrap.deviceExperimentId.takeIf { it.isNotBlank() }?.let {
                clientContext.put("deviceExperimentId", it)
            }
            bootstrap.remoteHost.takeIf { it.isNotBlank() }?.let { remoteHost ->
                clientContext.put("remoteHost", remoteHost)
            }
        }
        profile.deviceMake?.let { clientContext.put("deviceMake", it) }
        profile.deviceModel?.let { clientContext.put("deviceModel", it) }
        profile.osName?.let { clientContext.put("osName", it) }
        profile.osVersion?.let { clientContext.put("osVersion", it) }
        profile.androidSdkVersion?.let { clientContext.put("androidSdkVersion", it) }

        val requestContext = JSONObject()
            .put("useSsl", true)
            .put("internalExperimentFlags", JSONArray())
            .put("consistencyTokenJars", JSONArray())

        val context = JSONObject()
            .put("client", clientContext)
            .put("request", requestContext)
            .put("user", JSONObject().put("lockedSafetyMode", false))
        webRemixMetadata?.let { metadata ->
            context.put("clientScreenNonce", metadata.clientScreenNonce)
            context.put("clickTracking", JSONObject().put("clickTrackingParams", ""))
            context.put("adSignalsInfo", buildWebRemixAdSignalsInfo())
        }

        return JSONObject()
            .put("context", context)
            .apply {
                if (bootstrap.signatureTimestamp != null || webRemixMetadata != null) {
                    put(
                        "playbackContext",
                        buildPlayerPlaybackContext(
                            refererUrl = webRemixMetadata?.originalUrl,
                            signatureTimestamp = bootstrap.signatureTimestamp
                        )
                    )
                }
                webRemixMetadata?.let { metadata ->
                    put("cpn", metadata.cpn)
                    put("captionParams", JSONObject())
                    put("playlistId", metadata.playlistId)
                }
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

    private fun resolvePlayerRequestOrigin(profile: YouTubePlayerClientProfile): String {
        return if (profile.clientName == "WEB_REMIX") YOUTUBE_MUSIC_ORIGIN else YOUTUBE_WEB_ORIGIN
    }

    private fun resolvePlayerRequestUrl(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap,
        videoId: String
    ): String {
        val baseUrl = if (profile.clientName == "WEB_REMIX") {
            "$YOUTUBE_MUSIC_ORIGIN/youtubei/v1/${profile.endpointPath}"
        } else {
            "$YOUTUBE_WEB_ORIGIN/youtubei/v1/${profile.endpointPath}"
        }
        return buildString {
            append(baseUrl)
            append("?prettyPrint=false")
            if (profile.clientName != "WEB_REMIX") {
                append("&id=")
                append(videoId)
            }
            append("&key=")
            append(bootstrap.apiKey)
            if (profile.responseField != null) {
                append("&fields=")
                append(profile.responseField)
            }
        }
    }

    private fun resolvePlayerClientVersion(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap
    ): String {
        return if (profile.clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            bootstrap.webRemixClientVersion.ifBlank { profile.clientVersion }
        } else {
            profile.clientVersion
        }
    }

    private fun resolvePlayerRequestUserAgent(
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap
    ): String {
        return if (profile.clientName == "WEB_REMIX") {
            bootstrap.userAgent.ifBlank { profile.userAgent }
        } else {
            profile.userAgent
        }
    }

    private fun resolvePlayerJavaScriptUrl(rawUrl: String): String {
        return when {
            rawUrl.startsWith("https://") || rawUrl.startsWith("http://") -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "$YOUTUBE_MUSIC_ORIGIN$rawUrl"
            else -> "$YOUTUBE_MUSIC_ORIGIN/$rawUrl"
        }
    }

    private suspend fun bootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean = false
    ): YouTubePlaybackBootstrap {
        val startedAtMs = System.currentTimeMillis()
        val requestAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        val requestAuthFingerprint = requestAuth.buildBootstrapAuthFingerprint(
            origin = requestAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        val cached = bootstrapCache
        if (!forceRefresh &&
            cached != null &&
            cached.authFingerprint == requestAuthFingerprint &&
            System.currentTimeMillis() - cached.fetchedAtMs < PLAYABLE_BOOTSTRAP_TTL_MS
        ) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "bootstrap cache hit: forceRefresh=$forceRefresh, ageMs=${System.currentTimeMillis() - cached.fetchedAtMs}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
            return cached
        }

        val requestKey = InFlightBootstrapRequest(
            authFingerprint = requestAuthFingerprint,
            forceRefresh = forceRefresh
        )
        var joinedInFlight = false
        val deferred = synchronized(bootstrapRequestLock) {
            inFlightBootstrapRequests[requestKey]?.takeUnless { it.isCompleted || it.isCancelled }?.also {
                joinedInFlight = true
            } ?: run {
                lateinit var created: Deferred<YouTubePlaybackBootstrap>
                created = inFlightPlayableAudioScope.async(start = CoroutineStart.LAZY) {
                    try {
                        loadBootstrap(auth = auth, forceRefresh = forceRefresh)
                    } finally {
                        synchronized(bootstrapRequestLock) {
                            if (inFlightBootstrapRequests[requestKey] === created) {
                                inFlightBootstrapRequests.remove(requestKey)
                            }
                        }
                    }
                }
                inFlightBootstrapRequests[requestKey] = created
                created
            }
        }
        if (joinedInFlight) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "join in-flight bootstrap: forceRefresh=$forceRefresh, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
        }
        if (!deferred.isActive && !deferred.isCompleted && !deferred.isCancelled) {
            deferred.start()
        }
        return deferred.await()
    }

    private suspend fun loadBootstrap(
        auth: YouTubeAuthBundle,
        forceRefresh: Boolean
    ): YouTubePlaybackBootstrap {
        val startedAtMs = System.currentTimeMillis()
        var workingAuth = authProvider().normalized().takeIf { it.hasLoginCookies() } ?: auth
        var userAgent = workingAuth.resolveBootstrapUserAgent()
        var authFingerprint = workingAuth.buildBootstrapAuthFingerprint(
            origin = workingAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
        )
        var cookieHeader = appendYouTubeConsentCookie(workingAuth.effectiveCookieHeader())
        if (cookieHeader.isBlank()) {
            throw IOException("YouTube Music auth cookies missing")
        }

        val authGeneration = authCacheGeneration
        val homeHtml = try {
            fetchBootstrapHtml(
                auth = workingAuth,
                userAgent = userAgent,
                cookieHeader = cookieHeader
            )
        } catch (error: IOException) {
            if (isYouTubeAuthRecoverableFailure(error)) {
                authAutoRefreshManager?.refreshIfNeeded(
                    reason = "playback_bootstrap_http_recoverable",
                    force = true
                )
                workingAuth = authProvider().normalized()
                cookieHeader = appendYouTubeConsentCookie(workingAuth.effectiveCookieHeader())
                if (cookieHeader.isBlank()) {
                    throw error
                }
                userAgent = workingAuth.resolveBootstrapUserAgent()
                authFingerprint = workingAuth.buildBootstrapAuthFingerprint(
                    origin = workingAuth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN }
                )
                fetchBootstrapHtml(
                    auth = workingAuth,
                    userAgent = userAgent,
                    cookieHeader = cookieHeader
                )
            } else {
                throw error
            }
        }
        val fetchedAtMs = System.currentTimeMillis()
        val bootstrapSource = YouTubeBootstrapHtmlSource(homeHtml)
        val dataSyncId = bootstrapSource.optionalString("DATASYNC_ID", "datasyncId")
        val (derivedDelegatedSessionId, derivedUserSessionId) = parseDataSyncId(dataSyncId)
        val playerJsUrl = resolvePlayerJavaScriptUrl(
            bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "jsUrl"
            )
        )
        val cached = bootstrapCache
        val cachedSignatureTimestamp = cached
            ?.takeIf { it.playerJsUrl == playerJsUrl }
            ?.signatureTimestamp
        val parsedBootstrap = YouTubePlaybackBootstrap(
            apiKey = bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "INNERTUBE_API_KEY",
                "innertubeApiKey"
            ),
            webRemixClientVersion = bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "INNERTUBE_CLIENT_VERSION",
                "INNERTUBE_CONTEXT_CLIENT_VERSION",
                "innertubeContextClientVersion"
            ),
            visitorData = bootstrapSource.requireString(
                "YouTube bootstrap parse failed",
                "VISITOR_DATA",
                "visitorData"
            ),
            playerJsUrl = playerJsUrl,
            cookieHeader = cookieHeader,
            authFingerprint = authFingerprint,
            sessionIndex = workingAuth.resolveXGoogAuthUser(
                fallback = bootstrapSource.optionalNumber("SESSION_INDEX").ifBlank { "0" }
            ),
            userAgent = userAgent,
            remoteHost = bootstrapSource.optionalString("remoteHost"),
            signatureTimestamp = bootstrapSource.optionalNumber("STS", "signatureTimestamp")
                .ifBlank { cachedSignatureTimestamp?.toString().orEmpty() }
                .ifBlank { fetchPlayerSignatureTimestamp(playerJsUrl, userAgent)?.toString().orEmpty() }
                .toIntOrNull(),
            appInstallData = bootstrapSource.optionalString("appInstallData"),
            coldConfigData = bootstrapSource.optionalString("coldConfigData"),
            coldHashData = bootstrapSource.optionalString(
                "coldHashData",
                "SERIALIZED_COLD_HASH_DATA"
            ),
            hotHashData = bootstrapSource.optionalString(
                "hotHashData",
                "SERIALIZED_HOT_HASH_DATA"
            ),
            deviceExperimentId = bootstrapSource.optionalString("deviceExperimentId"),
            rolloutToken = bootstrapSource.optionalString("rolloutToken"),
            dataSyncId = dataSyncId,
            delegatedSessionId = bootstrapSource.optionalString("DELEGATED_SESSION_ID")
                .ifBlank { derivedDelegatedSessionId },
            userSessionId = bootstrapSource.optionalString("USER_SESSION_ID")
                .ifBlank { derivedUserSessionId },
            loggedIn = bootstrapSource.optionalBoolean("LOGGED_IN")
                .equals("true", ignoreCase = true),
            fetchedAtMs = fetchedAtMs
        )
        if (cached != null && cached.webRemixClientVersion != parsedBootstrap.webRemixClientVersion) {
            YoutubeJavaScriptPlayerManager.clearAllCaches()
        }
        return parsedBootstrap.also { parsed ->
            NPLogger.d(
                "YouTubeMusicPlayback",
                "bootstrap parsed: forceRefresh=$forceRefresh, loggedIn=${parsed.loggedIn}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
            )
            if (cached?.playerJsUrl != parsed.playerJsUrl) {
                inFlightPlayableAudioScope.launch {
                    runCatching {
                        ejsChallengeSolver?.warmPlayerScript(parsed.playerJsUrl)
                    }.onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        NPLogger.w(
                            "YouTubeMusicPlayback",
                            "Warm player script cache failed: ${error.message}"
                        )
                    }
                }
            }
            if (authGeneration == authCacheGeneration) {
                bootstrapCache = parsed
            }
        }
    }

    private fun fetchBootstrapHtml(
        auth: YouTubeAuthBundle,
        userAgent: String,
        cookieHeader: String
    ): String {
        var lastError: IOException? = null
        val requestLocale = currentPlayerRequestLocale()
        for (origin in BOOTSTRAP_PAGE_ORIGINS) {
            val startedAtMs = System.currentTimeMillis()
            val requestHeaders = auth.buildYouTubePageRequestHeaders(
                original = linkedMapOf(
                    "Accept-Language" to requestLocale.acceptLanguage
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
                return executeText(request).also {
                    NPLogger.d(
                        "YouTubeMusicPlayback",
                        "fetchBootstrapHtml ok: origin=$origin, elapsedMs=${playbackElapsedMs(startedAtMs)}"
                    )
                }
            } catch (error: IOException) {
                lastError = error
                NPLogger.w(
                    "YouTubeMusicPlayback",
                    "fetchBootstrapHtml failed: origin=$origin, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
                )
            }
        }
        throw lastError ?: IOException("YouTube Music bootstrap request failed")
    }

    private fun executeJson(request: Request): JSONObject {
        return JSONObject(executeText(request))
    }

    private fun executeText(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("YouTube Music request failed: ${response.code} ${body.take(160)}")
            }
            return body
        }
    }

    private fun fetchPlayerSignatureTimestamp(
        playerJsUrl: String,
        userAgent: String
    ): Int? {
        if (playerJsUrl.isBlank()) {
            return null
        }
        val request = Request.Builder()
            .url(playerJsUrl)
            .header("User-Agent", userAgent)
            .build()
        return runCatching {
            val playerJs = executeText(request)
            Regex("""(?:signatureTimestamp|sts)\s*:\s*(\d{5})""")
                .find(playerJs)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }.onFailure { error ->
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Failed to fetch player signature timestamp",
                error
            )
        }.getOrNull()
    }

    private fun resolvePlayableAudioViaNewPipe(
        videoId: String,
        preferredQualityKey: String,
        logFailure: Boolean,
        preferM4a: Boolean
    ): YouTubePlayableAudio? {
        return runCatching {
            val streamInfo = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )
            selectPlayableAudio(streamInfo, preferredQualityKey, preferM4a)
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

    private fun selectPlayableAudio(
        streamInfo: StreamInfo,
        preferredQualityKey: String,
        preferM4a: Boolean
    ): YouTubePlayableAudio? {
        val sortedStreams = streamInfo.audioStreams
            .asSequence()
            .filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .thenByDescending { if (preferM4a) playableAudioMimePreferenceScore(it.format?.mimeType) else 0 }
                    .thenByDescending { it.averageBitrate }
                    .thenByDescending { it.bitrate }
            )
            .filter { it.content.isNotBlank() }
            .toList()
        val selectedStream = selectAudioStreamByQuality(
            streams = sortedStreams,
            preferredQualityKey = preferredQualityKey
        )
            ?: return null

        val resolvedDurationMs = streamInfo.duration
            .takeIf { it > 0L }
            ?.times(1000L)
            ?: 0L

        return YouTubePlayableAudio(
            url = selectedStream.content,
            durationMs = resolvedDurationMs,
            mimeType = selectedStream.format?.mimeType,
            contentLength = null,
            bitrateKbps = selectedStream.averageBitrate
                .takeIf { it > 0 }
                ?.let { (it + 500) / 1000 }
                ?: selectedStream.bitrate.takeIf { it > 0 }?.let { (it + 500) / 1000 }
        )
    }

    private fun selectAudioStreamByQuality(
        streams: List<AudioStream>,
        preferredQualityKey: String
    ): AudioStream? {
        if (streams.isEmpty()) {
            return null
        }
        val sortedAscending = streams.asReversed()
        fun AudioStream.effectiveBitrate(): Int {
            return averageBitrate.takeIf { it > 0 } ?: bitrate
        }
        return when (YouTubeMusicPlaybackQuality.fromSetting(preferredQualityKey)) {
            YouTubeMusicPlaybackQuality.LOW -> sortedAscending.firstOrNull()
            YouTubeMusicPlaybackQuality.MEDIUM -> {
                sortedAscending.firstOrNull { it.effectiveBitrate() >= 96_000 }
                    ?: streams.firstOrNull()
            }
            YouTubeMusicPlaybackQuality.HIGH -> {
                sortedAscending.firstOrNull { it.effectiveBitrate() >= 128_000 }
                    ?: streams.firstOrNull()
            }
            YouTubeMusicPlaybackQuality.VERY_HIGH -> streams.firstOrNull()
        }
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
                clientId = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_WEB_REMIX_USER_AGENT,
                endpointPath = "player",
                platform = "DESKTOP",
                clientScreen = YOUTUBE_PLAYER_WEB_REMIX_CLIENT_SCREEN,
                osName = "Windows",
                osVersion = "10.0"
            ),
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_TV_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_TV_USER_AGENT,
                endpointPath = "player",
                platform = "TV"
            ),
            YouTubePlayerClientProfile(
                clientId = YOUTUBE_PLAYER_TV_CLIENT_ID,
                clientName = YOUTUBE_PLAYER_TV_CLIENT_NAME,
                clientVersion = YOUTUBE_PLAYER_TV_DOWNGRADED_CLIENT_VERSION,
                userAgent = YOUTUBE_PLAYER_TV_DOWNGRADED_USER_AGENT,
                endpointPath = "player",
                platform = "TV"
            )
        )
    }

    private suspend fun resolvePreferredQualityKey(preferredQualityOverride: String?): String {
        return preferredQualityOverride
            ?.takeIf { it.isNotBlank() }
            ?: settings?.youtubeAudioQualityFlow?.first()?.takeIf { it.isNotBlank() }
            ?: "very_high"
    }

    internal fun selectPreferredPlayableAudio(
        current: YouTubePlayableAudio?,
        incoming: YouTubePlayableAudio?,
        currentClientName: String? = null,
        incomingClientName: String? = null
    ): YouTubePlayableAudio? {
        if (incoming == null) {
            return current
        }
        if (current == null) {
            return incoming
        }
        val qualityComparison = comparePlayableAudioQuality(incoming, current)
        return when {
            incoming.streamType != current.streamType -> {
                // 优先 progressive 直链，seek 更快且能绕过数据中心 IP 下的 HLS/SABR 403
                if (incoming.streamType == YouTubePlayableStreamType.DIRECT) incoming else current
            }
            qualityComparison != 0 -> {
                if (qualityComparison > 0) {
                    incoming
                } else {
                    current
                }
            }
            currentClientName != incomingClientName -> {
                val incomingClientScore = playbackClientPreferenceScore(
                    clientName = incomingClientName,
                    streamType = incoming.streamType
                )
                val currentClientScore = playbackClientPreferenceScore(
                    clientName = currentClientName,
                    streamType = current.streamType
                )
                if (incomingClientScore > currentClientScore) {
                    incoming
                } else {
                    current
                }
            }
            else -> current
        }
    }

    private fun playbackClientPreferenceScore(
        clientName: String?,
        streamType: YouTubePlayableStreamType
    ): Int {
        return when (streamType) {
            YouTubePlayableStreamType.DIRECT -> when {
                clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME -> 30
                clientName?.startsWith(YOUTUBE_PLAYER_TV_CLIENT_NAME, ignoreCase = true) == true -> 20
                clientName.isNullOrBlank() -> 0
                else -> 5
            }
            YouTubePlayableStreamType.HLS -> when {
                clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME -> 20
                clientName?.startsWith(YOUTUBE_PLAYER_TV_CLIENT_NAME, ignoreCase = true) == true -> 5
                else -> 0
            }
        }
    }

    private fun comparePlayableAudioQuality(
        incoming: YouTubePlayableAudio,
        current: YouTubePlayableAudio
    ): Int {
        val incomingBitrate = incoming.bitrateKbps ?: 0
        val currentBitrate = current.bitrateKbps ?: 0
        if (incomingBitrate != currentBitrate) {
            return incomingBitrate.compareTo(currentBitrate)
        }

        val incomingSampleRate = incoming.sampleRateHz ?: 0
        val currentSampleRate = current.sampleRateHz ?: 0
        if (incomingSampleRate != currentSampleRate) {
            return incomingSampleRate.compareTo(currentSampleRate)
        }

        val incomingMimeScore = playableAudioMimePreferenceScore(incoming.mimeType)
        val currentMimeScore = playableAudioMimePreferenceScore(current.mimeType)
        if (incomingMimeScore != currentMimeScore) {
            return incomingMimeScore.compareTo(currentMimeScore)
        }

        val incomingContentLength = incoming.contentLength ?: 0L
        val currentContentLength = current.contentLength ?: 0L
        if (incomingContentLength != currentContentLength) {
            return incomingContentLength.compareTo(currentContentLength)
        }

        return incoming.durationMs.compareTo(current.durationMs)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldRetryWithFreshBootstrapBeforeFallback(
        profile: YouTubePlayerClientProfile,
        playability: YouTubePlayerPlayabilityStatus,
        attempt: Int,
        forceRefresh: Boolean
    ): Boolean {
        // 先让后续 client 立即接管，避免 WEB_REMIX 一次失败就白白多刷一轮 bootstrap
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldRetryWithFreshBootstrapAfterRequestFailure(
        profile: YouTubePlayerClientProfile,
        attempt: Int,
        forceRefresh: Boolean
    ): Boolean {
        // 同一轮里先跑完 fallback，下一轮再统一强刷 bootstrap
        return false
    }

    private fun getCachedPlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        requireDirect: Boolean = false
    ): YouTubePlayableAudio? {
        val cacheKey = playableAudioCacheKey(videoId, preferredQualityKey)
        synchronized(playableAudioCache) {
            val cached = playableAudioCache[cacheKey] ?: return null
            if (System.currentTimeMillis() - cached.cachedAtMs > PLAYABLE_URL_CACHE_TTL_MS) {
                playableAudioCache.remove(cacheKey)
                return null
            }
            if (requireDirect && cached.audio.streamType != YouTubePlayableStreamType.DIRECT) {
                return null
            }
            return cached.audio
        }
    }

    private fun cachePlayableAudio(
        videoId: String,
        preferredQualityKey: String,
        audio: YouTubePlayableAudio
    ) {
        val cacheKey = playableAudioCacheKey(videoId, preferredQualityKey)
        synchronized(playableAudioCache) {
            playableAudioCache.remove(cacheKey)
            playableAudioCache[cacheKey] = CachedPlayableAudio(
                audio = audio,
                cachedAtMs = System.currentTimeMillis()
            )
            while (playableAudioCache.size > PLAYABLE_URL_CACHE_MAX_SIZE) {
                val eldestKey = playableAudioCache.entries.firstOrNull()?.key ?: break
                playableAudioCache.remove(eldestKey)
            }
        }
    }

    private fun playableAudioCacheKey(videoId: String, preferredQualityKey: String): String {
        return "$videoId|${preferredQualityKey.lowercase(Locale.US)}"
    }

    private fun currentPlayerRequestLocale(): YouTubeMusicRequestLocale {
        return YouTubeMusicLocaleResolver.preferred()
    }

    private fun playerRequestLocaleCandidates(): List<YouTubeMusicRequestLocale> {
        return YouTubeMusicLocaleResolver.requestCandidates(
            preferredLocale = currentPlayerRequestLocale()
        )
    }

    private fun utcOffsetMinutes(): Int {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)
    }

    private fun currentTimeZoneId(): String = TimeZone.getDefault().id

    private fun buildWebRemixRequestMetadata(videoId: String): YouTubeWebRemixRequestMetadata {
        val playlistId = "RDAMVM$videoId"
        val watchUrl = buildWebRemixWatchUrl(videoId, playlistId)
        return YouTubeWebRemixRequestMetadata(
            originalUrl = "$YOUTUBE_MUSIC_ORIGIN/",
            watchUrl = watchUrl,
            playlistId = playlistId,
            cpn = generateRequestNonce(),
            clientScreenNonce = generateRequestNonce()
        )
    }

    private fun buildWebRemixWatchUrl(videoId: String, playlistId: String): String {
        return buildString {
            append(YOUTUBE_MUSIC_ORIGIN)
            append("/watch?v=")
            append(URLEncoder.encode(videoId, Charsets.UTF_8.name()))
            append("&list=")
            append(URLEncoder.encode(playlistId, Charsets.UTF_8.name()))
        }
    }

    private fun buildPlayerPlaybackContext(
        refererUrl: String?,
        signatureTimestamp: Int?
    ): JSONObject {
        val contentPlaybackContext = JSONObject()
            .put("html5Preference", "HTML5_PREF_WANTS")
            .put("lactMilliseconds", YOUTUBE_PLAYER_PLAYBACK_LACT_MILLISECONDS)
            .put("autonavState", "STATE_OFF")
            .put("autoCaptionsDefaultOn", false)
            .put("mdxContext", JSONObject())
            .put("vis", 10)
        refererUrl?.takeIf { it.isNotBlank() }?.let {
            contentPlaybackContext.put("referer", it)
        }
        signatureTimestamp?.let { contentPlaybackContext.put("signatureTimestamp", it) }
        return JSONObject()
            .put("contentPlaybackContext", contentPlaybackContext)
            .put(
                "devicePlaybackCapabilities",
                JSONObject()
                    .put("supportsVp9Encoding", true)
                    .put("supportXhr", true)
            )
    }

    private fun buildWebRemixAdSignalsInfo(): JSONObject {
        val params = listOf(
            "dt" to System.currentTimeMillis().toString(),
            "flash" to "0",
            "frm" to "0",
            "u_tz" to utcOffsetMinutes().toString(),
            "u_his" to YOUTUBE_PLAYER_WEB_REMIX_HISTORY_LENGTH.toString(),
            "u_h" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_HEIGHT.toString(),
            "u_w" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH.toString(),
            "u_ah" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT.toString(),
            "u_aw" to YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH.toString(),
            "u_cd" to YOUTUBE_PLAYER_WEB_REMIX_COLOR_DEPTH.toString(),
            "bc" to YOUTUBE_PLAYER_WEB_REMIX_BROWSER_CONNECTION.toString(),
            "bih" to YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS.toString(),
            "biw" to YOUTUBE_PLAYER_WEB_REMIX_INNER_WIDTH.toString(),
            "brdim" to "0,0,0,0,${YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_WIDTH},0," +
                "${YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_WIDTH}," +
                "${YOUTUBE_PLAYER_WEB_REMIX_VIEWPORT_AVAILABLE_HEIGHT}," +
                "${YOUTUBE_PLAYER_WEB_REMIX_SCREEN_WIDTH_POINTS}," +
                YOUTUBE_PLAYER_WEB_REMIX_SCREEN_HEIGHT_POINTS,
            "vis" to "1",
            "wgl" to "true",
            "ca_type" to "image"
        )
        return JSONObject().put(
            "params",
            JSONArray().apply {
                params.forEach { (key, value) ->
                    put(
                        JSONObject()
                            .put("key", key)
                            .put("value", value)
                    )
                }
            }
        )
    }

    private fun resolveBrowserName(userAgent: String): String {
        val lowerCaseUserAgent = userAgent.lowercase(Locale.US)
        return when {
            "edg/" in lowerCaseUserAgent -> "Edge"
            "chrome/" in lowerCaseUserAgent -> "Chrome"
            "firefox/" in lowerCaseUserAgent -> "Firefox"
            else -> "Chrome"
        }
    }

    private fun resolveBrowserVersion(userAgent: String): String {
        val patterns = listOf("Edg/([\\d.]+)", "Chrome/([\\d.]+)", "Firefox/([\\d.]+)")
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern).find(userAgent)?.groupValues?.getOrNull(1)
        }.orEmpty()
    }

    private fun ensureGfeUserAgent(userAgent: String): String {
        return if (userAgent.contains("gzip(gfe)")) {
            userAgent
        } else {
            "$userAgent,gzip(gfe)"
        }
    }

    private fun generateRequestNonce(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString(16) {
            repeat(16) {
                append(alphabet[Random.nextInt(alphabet.length)])
            }
        }
    }

    private fun findRequired(source: String, vararg patterns: String): String {
        return findOptional(source, *patterns).ifBlank {
            throw IOException("YouTube bootstrap parse failed: ${patterns.firstOrNull().orEmpty()}")
        }
    }

    private fun findOptional(source: String, vararg patterns: String): String {
        patterns.forEach { pattern ->
            val match = Regex(pattern).find(source)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                return match
            }
        }
        return ""
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
