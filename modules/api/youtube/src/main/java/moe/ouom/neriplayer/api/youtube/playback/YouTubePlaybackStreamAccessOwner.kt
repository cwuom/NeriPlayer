package moe.ouom.neriplayer.api.youtube.playback

import androidx.media3.common.MimeTypes
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.api.youtube.challenge.extractStreamQueryParameter
import moe.ouom.neriplayer.api.youtube.challenge.playbackElapsedMs
import moe.ouom.neriplayer.api.youtube.challenge.replaceStreamQueryParameter
import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableStreamType
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlaybackBootstrap
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayerClientProfile
import moe.ouom.neriplayer.api.youtube.potoken.YouTubePoTokenProvider
import moe.ouom.neriplayer.api.youtube.potoken.appendWebRemixManifestPoToken
import moe.ouom.neriplayer.api.youtube.potoken.carryForwardWebRemixManifestPoToken
import moe.ouom.neriplayer.api.youtube.potoken.hasWebRemixManifestPoToken
import moe.ouom.neriplayer.api.youtube.protocol.YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubeHlsAudioPlaylist
import moe.ouom.neriplayer.api.youtube.protocol.YouTubeMusicHlsManifestParser
import moe.ouom.neriplayer.api.youtube.protocol.buildBootstrapRequestAuth
import moe.ouom.neriplayer.api.youtube.protocol.isYouTubeGoogleVideoStream
import moe.ouom.neriplayer.api.youtube.protocol.requiresGvsPoToken
import moe.ouom.neriplayer.api.youtube.transport.YOUTUBE_ERROR_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.api.youtube.transport.YOUTUBE_TEXT_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.api.youtube.transport.YouTubeHttpStatusException
import moe.ouom.neriplayer.api.youtube.transport.buildYouTubeStreamRequestHeaders
import moe.ouom.neriplayer.api.youtube.transport.parseRetryAfterMs
import moe.ouom.neriplayer.api.youtube.transport.readErrorPreviewWithLimit
import moe.ouom.neriplayer.api.youtube.transport.readTextWithLimit
import moe.ouom.neriplayer.core.logging.NPLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject

private const val WEB_REMIX_PO_TOKEN_PREFETCH_JOIN_TIMEOUT_MS = 150L
private const val YOUTUBE_PLAYBACK_DIAG_PREFIX = "[YT-DIAG-20260530]"

private enum class DirectRangeVerificationStatus {
    READABLE,
    NON_PARTIAL_CONTENT,
    EMPTY_BODY,
    NO_BYTES_READ,
    REQUEST_FAILED
}

private data class DirectRangeVerificationResult(
    val status: DirectRangeVerificationStatus,
    val httpCode: Int?,
    val bytesRead: Long,
    val elapsedMs: Long
) {
    val isReadable: Boolean
        get() = status == DirectRangeVerificationStatus.READABLE
}

private fun YouTubePlayableAudio.missingPoTokenDiagnosticMetadata(clientName: String): String {
    return "client=$clientName " +
        "itag=${diagnosticItag(url)} " +
        "mimeType=${mimeType.diagnosticValue()} " +
        "bitrate=${bitrateKbps.diagnosticValue()} " +
        "sourceKind=$streamType " +
        "contentLength=${contentLength.diagnosticValue()}"
}

internal fun diagnosticItag(url: String): String =
    extractStreamQueryParameter(url, "itag")
        ?.takeIf { it.all(Char::isDigit) }
        ?: "<unknown>"

private fun Any?.diagnosticValue(): String = this?.toString() ?: "<unknown>"

class YouTubePlaybackStreamAccessOwner(
    private val okHttpClient: OkHttpClient,
    private val poTokenProvider: YouTubePoTokenProvider?,
    private val scope: CoroutineScope
) {
    fun clearSession() = poTokenProvider?.clearSession()

    fun warmSessionAsync(reason: String) {
        val provider = poTokenProvider ?: return
        scope.launch { warmSession(provider, reason) }
    }

    private suspend fun warmSession(provider: YouTubePoTokenProvider, reason: String) {
        val startedAtMs = System.currentTimeMillis()
        try {
            provider.warmSession()
        } catch (error: Throwable) {
            logSessionWarmFailure(reason, error)
            return
        }
        NPLogger.d(
            "YouTubeMusicPlayback",
            "Warm WebPo session finished in background: reason=$reason, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
    }

    private fun logSessionWarmFailure(reason: String, error: Throwable) {
        if (error is CancellationException) throw error
        NPLogger.w("YouTubeMusicPlayback", "Warm WebPo session failed in background: reason=$reason", error)
    }

    suspend fun maybeAttachGvsPoToken(
        playableAudio: YouTubePlayableAudio?,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>? = null,
        allowBlockingAcquisition: Boolean,
        allowUnverifiedDirectFallback: Boolean
    ): YouTubePlayableAudio? {
        if (playableAudio == null) return null
        if (!needsDirectGvsPoToken(playableAudio, profile)) return playableAudio
        if (!isYouTubeGoogleVideoStream(playableAudio.url)) return playableAudio
        return attachGoogleVideoPoToken(
            playableAudio, profile, videoId, auth, bootstrap, forceRefresh,
            prefetchedPoToken, allowBlockingAcquisition, allowUnverifiedDirectFallback
        )
    }

    private fun needsDirectGvsPoToken(
        playableAudio: YouTubePlayableAudio,
        profile: YouTubePlayerClientProfile
    ): Boolean = playableAudio.streamType == YouTubePlayableStreamType.DIRECT && profile.requiresGvsPoToken()

    private suspend fun attachGoogleVideoPoToken(
        playableAudio: YouTubePlayableAudio,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>?,
        allowBlockingAcquisition: Boolean,
        allowUnverifiedDirectFallback: Boolean
    ): YouTubePlayableAudio? {
        val startedAtMs = System.currentTimeMillis()
        val streamUrl = playableAudio.url
        if (hasDirectPoToken(streamUrl)) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "reuse existing stream PO token: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, forceRefresh=$forceRefresh"
            )
            return playableAudio
        }
        return attachMissingGvsPoToken(
            playableAudio, profile, videoId, auth, bootstrap, startedAtMs, forceRefresh,
            prefetchedPoToken, allowBlockingAcquisition, allowUnverifiedDirectFallback
        )
    }

    private fun hasDirectPoToken(streamUrl: String): Boolean =
        extractStreamQueryParameter(streamUrl, "pot").orEmpty().isNotBlank()

    private suspend fun attachMissingGvsPoToken(
        playableAudio: YouTubePlayableAudio,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        startedAtMs: Long,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>?,
        allowBlockingAcquisition: Boolean,
        allowUnverifiedDirectFallback: Boolean
    ): YouTubePlayableAudio? {
        val poToken = resolveWebRemixPoToken(
            videoId = videoId,
            bootstrap = bootstrap,
            forceRefresh = forceRefresh,
            prefetchedPoToken = prefetchedPoToken,
            allowBlockingAcquisition = allowBlockingAcquisition
        )
        return attachGvsTokenResult(
            playableAudio, profile, videoId, auth, bootstrap, startedAtMs,
            forceRefresh, allowBlockingAcquisition, allowUnverifiedDirectFallback, poToken
        )
    }

    private fun attachGvsTokenResult(
        playableAudio: YouTubePlayableAudio,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        startedAtMs: Long,
        forceRefresh: Boolean,
        allowBlockingAcquisition: Boolean,
        allowUnverifiedDirectFallback: Boolean,
        poToken: String
    ): YouTubePlayableAudio? {
        if (poToken.isBlank()) return withoutGvsPoToken(
            playableAudio, profile, videoId, auth, bootstrap, startedAtMs,
            forceRefresh, allowBlockingAcquisition, allowUnverifiedDirectFallback
        )
        return attachResolvedGvsPoToken(playableAudio, poToken, videoId, forceRefresh, startedAtMs)
    }

    private fun attachResolvedGvsPoToken(
        playableAudio: YouTubePlayableAudio,
        poToken: String,
        videoId: String,
        forceRefresh: Boolean,
        startedAtMs: Long
    ): YouTubePlayableAudio {
        NPLogger.d(
            "YouTubeMusicPlayback",
            "attached stream PO token: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, forceRefresh=$forceRefresh"
        )
        return playableAudio.copy(
            url = replaceStreamQueryParameter(playableAudio.url, "pot", poToken)
        )
    }

    private fun withoutGvsPoToken(
        playableAudio: YouTubePlayableAudio,
        profile: YouTubePlayerClientProfile,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        startedAtMs: Long,
        forceRefresh: Boolean,
        allowBlockingAcquisition: Boolean,
        allowUnverifiedDirectFallback: Boolean
    ): YouTubePlayableAudio? {
        if (allowUnverifiedDirectFallback && profile.clientName != YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            return playableAudio
        }
        if (!allowBlockingAcquisition) {
            NPLogger.d(
                "YouTubeMusicPlayback",
                "$YOUTUBE_PLAYBACK_DIAG_PREFIX missing_pot_web_direct_fast_fallback " +
                    "videoId=$videoId ${playableAudio.missingPoTokenDiagnosticMetadata(profile.clientName)} " +
                    "fallbackReason=missing_pot_fast_path_timeout " +
                    "fallbackPath=continue_player_clients " +
                    "branchElapsedMs=${playbackElapsedMs(startedAtMs)} " +
                    "forceRefresh=$forceRefresh"
            )
            return null
        }
        if (!allowUnverifiedDirectFallback) return null
        return verifyTokenlessDirect(playableAudio, profile.clientName, videoId, auth, bootstrap, startedAtMs)
    }

    private fun verifyTokenlessDirect(
        playableAudio: YouTubePlayableAudio,
        clientName: String,
        videoId: String,
        auth: YouTubeAuthBundle,
        bootstrap: YouTubePlaybackBootstrap,
        startedAtMs: Long
    ): YouTubePlayableAudio? {
        val verification = verifyDirectRangeReadable(
            playableAudio.url,
            buildBootstrapRequestAuth(auth, bootstrap)
        )
        val metadata = playableAudio.missingPoTokenDiagnosticMetadata(clientName)
        val result = "$YOUTUBE_PLAYBACK_DIAG_PREFIX missing_pot_webremix_direct_verification " +
            "videoId=$videoId $metadata " +
            "status=${verification.status} httpCode=${verification.httpCode ?: "<none>"} " +
            "bytesRead=${verification.bytesRead} elapsedMs=${verification.elapsedMs} "
        if (verification.isReadable) {
            NPLogger.d("YouTubeMusicPlayback", "${result}candidateDecision=accepted")
            return playableAudio
        }
        NPLogger.w(
            "YouTubeMusicPlayback",
            result + "candidateDecision=rejected " +
                "fallbackReason=missing_pot_range_verification_failed " +
                "fallbackPath=continue_player_clients " +
                "branchElapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return null
    }

    private fun verifyDirectRangeReadable(
        streamUrl: String,
        auth: YouTubeAuthBundle
    ): DirectRangeVerificationResult {
        val startedAtMs = System.currentTimeMillis()
        val request = buildYouTubeStreamRequest(streamUrl, auth)
            .newBuilder()
            .header("Range", "bytes=0-0")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code != 206) {
                    return@use DirectRangeVerificationResult(
                        status = DirectRangeVerificationStatus.NON_PARTIAL_CONTENT,
                        httpCode = response.code,
                        bytesRead = 0L,
                        elapsedMs = playbackElapsedMs(startedAtMs)
                    )
                }
                val bytesRead = response.body.source().read(Buffer(), 1L).coerceAtLeast(0L)
                DirectRangeVerificationResult(
                    status = if (bytesRead > 0L) {
                        DirectRangeVerificationStatus.READABLE
                    } else {
                        DirectRangeVerificationStatus.NO_BYTES_READ
                    },
                    httpCode = response.code,
                    bytesRead = bytesRead,
                    elapsedMs = playbackElapsedMs(startedAtMs)
                )
            }
        } catch (_: Exception) {
            DirectRangeVerificationResult(
                status = DirectRangeVerificationStatus.REQUEST_FAILED,
                httpCode = null,
                bytesRead = 0L,
                elapsedMs = playbackElapsedMs(startedAtMs)
            )
        }
    }

    fun prefetchWebRemixPoToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean
    ): Deferred<String?>? {
        val provider = poTokenProvider ?: return null
        if (bootstrap.visitorData.isBlank()) {
            return null
        }
        return scope.async { acquirePrefetchedToken(provider, videoId, bootstrap, forceRefresh) }
    }

    private suspend fun acquirePrefetchedToken(
        provider: YouTubePoTokenProvider,
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean
    ): String? {
        val startedAtMs = System.currentTimeMillis()
        val token = try {
            provider.getWebRemixGvsPoToken(videoId, bootstrap.visitorData, bootstrap.remoteHost, forceRefresh)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NPLogger.w(
                "YouTubeMusicPlayback",
                "prefetch GVS PO token failed: videoId=$videoId, elapsedMs=${playbackElapsedMs(startedAtMs)}, error=${error.message}"
            )
            return null
        }
        NPLogger.d(
            "YouTubeMusicPlayback",
            "prefetch GVS PO token finished: videoId=$videoId, hasToken=${!token.isNullOrBlank()}, elapsedMs=${playbackElapsedMs(startedAtMs)}"
        )
        return token
    }

    fun shouldPrefetchWebRemixPoToken(root: JSONObject): Boolean {
        val streamingData = root.optJSONObject("streamingData") ?: return false
        val hlsManifestUrl = streamingData.optString("hlsManifestUrl").trim()
        if (hlsManifestUrl.isNotBlank()) return !hasWebRemixManifestPoToken(hlsManifestUrl)
        val formatArrays = listOfNotNull(
            streamingData.optJSONArray("adaptiveFormats"),
            streamingData.optJSONArray("formats")
        )
        return formatArrays.any { formats ->
            (0 until formats.length()).any { index ->
                formats.optJSONObject(index)?.let(::audioFormatNeedsPoToken) == true
            }
        }
    }

    private fun audioFormatNeedsPoToken(format: JSONObject): Boolean {
        if (!isAudioFormat(format)) return false
        val directUrl = format.optString("url").trim()
        return if (directUrl.isNotBlank()) directUrlNeedsPoToken(directUrl)
        else cipherFormatNeedsPoToken(format)
    }

    private fun isAudioFormat(format: JSONObject): Boolean =
        format.optString("mimeType").substringBefore(';').trim().startsWith("audio/")

    private fun directUrlNeedsPoToken(url: String): Boolean =
        isYouTubeGoogleVideoStream(url) && extractStreamQueryParameter(url, "pot").isNullOrBlank()

    private fun cipherFormatNeedsPoToken(format: JSONObject): Boolean =
        format.optString("signatureCipher").ifBlank { format.optString("cipher") }.isNotBlank()

    private suspend fun awaitPrefetchedWebRemixPoToken(
        videoId: String,
        prefetchedPoToken: Deferred<String?>?,
        timeoutMs: Long? = null
    ): String? {
        val deferred = prefetchedPoToken ?: return null
        return try {
            awaitPoToken(deferred, timeoutMs)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            NPLogger.w(
                "YouTubeMusicPlayback",
                "Await prefetched GVS PO token failed: videoId=$videoId, error=${error.message}"
            )
            null
        }
    }

    private suspend fun awaitPoToken(deferred: Deferred<String?>, timeoutMs: Long?): String? {
        if (timeoutMs == null || deferred.isCompleted) return deferred.await()
        return withTimeoutOrNull(timeoutMs.milliseconds) { deferred.await() }
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
        return mintWebRemixToken(videoId, bootstrap, forceRefresh)
    }

    private suspend fun mintWebRemixToken(
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean
    ): String = poTokenProvider?.getWebRemixGvsPoToken(
        videoId = videoId,
        visitorData = bootstrap.visitorData,
        remoteHost = bootstrap.remoteHost,
        forceRefresh = forceRefresh
    ).orEmpty()

    suspend fun resolveHlsPlayableAudio(
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
        val resolvedManifestUrl = resolveHlsManifestUrl(
            hlsManifestUrl(root), profile.clientName, videoId, bootstrap,
            forceRefresh, prefetchedPoToken, allowBlockingAcquisition
        )
        return fetchHlsPlayableAudio(resolvedManifestUrl, preferredQualityKey, auth, durationMs, profile, bootstrap)
    }

    private fun hlsManifestUrl(root: JSONObject): String =
        root.optJSONObject("streamingData")?.optString("hlsManifestUrl").orEmpty().trim()

    private fun fetchHlsPlayableAudio(
        manifestUrl: String?,
        preferredQualityKey: String,
        auth: YouTubeAuthBundle,
        durationMs: Long,
        profile: YouTubePlayerClientProfile,
        bootstrap: YouTubePlaybackBootstrap
    ): YouTubePlayableAudio? {
        val resolvedManifestUrl = manifestUrl ?: return null
        val requestAuth = buildBootstrapRequestAuth(auth = auth, bootstrap = bootstrap)
        val masterManifest = executeText(buildYouTubeStreamRequest(resolvedManifestUrl, requestAuth))
        val selectedAudioPlaylist = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = masterManifest,
            masterManifestUrl = resolvedManifestUrl,
            preferredQualityKey = preferredQualityKey,
            durationMs = durationMs
        ) ?: return null
        return hlsPlayableAudio(selectedAudioPlaylist, resolvedManifestUrl, durationMs, profile.clientName)
    }

    private fun hlsPlayableAudio(
        selectedAudioPlaylist: YouTubeHlsAudioPlaylist,
        resolvedManifestUrl: String,
        durationMs: Long,
        clientName: String
    ): YouTubePlayableAudio {
        return YouTubePlayableAudio(
            url = playlistUrlForClient(selectedAudioPlaylist.uri, resolvedManifestUrl, clientName),
            durationMs = durationMs,
            mimeType = MimeTypes.APPLICATION_M3U8,
            contentLength = selectedAudioPlaylist.contentLength,
            streamType = YouTubePlayableStreamType.HLS,
            bitrateKbps = estimatedHlsBitrateKbps(selectedAudioPlaylist.estimatedBitrate)
        )
    }

    private fun playlistUrlForClient(playlistUrl: String, manifestUrl: String, clientName: String): String =
        if (clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME) {
            carryForwardWebRemixManifestPoToken(manifestUrl, playlistUrl)
        } else playlistUrl

    private fun estimatedHlsBitrateKbps(estimatedBitrate: Int): Int? =
        if (estimatedBitrate > 0) (estimatedBitrate + 500) / 1000 else null

    private suspend fun resolveHlsManifestUrl(
        manifestUrl: String,
        clientName: String,
        videoId: String,
        bootstrap: YouTubePlaybackBootstrap,
        forceRefresh: Boolean,
        prefetchedPoToken: Deferred<String?>?,
        allowBlockingAcquisition: Boolean
    ): String? {
        if (manifestUrl.isBlank()) return null
        if (!needsHlsManifestPoToken(clientName, manifestUrl)) return manifestUrl
        val poToken = resolveWebRemixPoToken(
            videoId, bootstrap, forceRefresh, prefetchedPoToken, allowBlockingAcquisition
        )
        return tokenizedHlsManifestUrl(manifestUrl, poToken)
    }

    private fun needsHlsManifestPoToken(clientName: String, manifestUrl: String): Boolean =
        clientName == YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME && !hasWebRemixManifestPoToken(manifestUrl)

    private fun tokenizedHlsManifestUrl(manifestUrl: String, poToken: String): String? {
        if (poToken.isNotBlank()) return appendWebRemixManifestPoToken(manifestUrl, poToken)
        return manifestUrl.takeIf { poTokenProvider == null }
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

    private fun executeText(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val preview = response.body.readErrorPreviewWithLimit(YOUTUBE_ERROR_RESPONSE_MAX_BYTES)
                throw YouTubeHttpStatusException(
                    statusCode = response.code,
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                    message = "YouTube Music request failed: ${response.code} $preview"
                )
            }
            return response.body.readTextWithLimit(YOUTUBE_TEXT_RESPONSE_MAX_BYTES)
        }
    }
}
