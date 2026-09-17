package moe.ouom.neriplayer.core.player.download

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import moe.ouom.neriplayer.core.download.policy.ManagedDownloadSizePolicy
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.resource.DownloadTransferStalledException
import moe.ouom.neriplayer.core.player.engine.datasource.ChunkRequestIOException
import moe.ouom.neriplayer.core.player.engine.datasource.ResumableHttpRangeSupport
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeGoogleVideoRangeSupport
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayCoverUrl
import okhttp3.Request
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 音频下载的传输选择、续传校验和失败分类
 *
 * 这里不持有下载任务状态，只负责可复用的边界规则，便于测试和并行执行
 */
internal object AudioDownloadTransferPolicy {
    private const val TAG = "NERI-Downloader"
    internal const val SOURCE_RESOLVE_MAX_CONFIRMED_MISSES = 2
    private const val YOUTUBE_DOWNLOAD_SHARED_DIRECT_RESOLVE_TIMEOUT_MS = 3_500L
    private const val YOUTUBE_DOWNLOAD_FRESH_DIRECT_RESOLVE_TIMEOUT_MS = 18_000L
    private const val YOUTUBE_DOWNLOAD_SHARED_PLAYABLE_RESOLVE_TIMEOUT_MS = 6_000L
    private const val YOUTUBE_DOWNLOAD_FRESH_PLAYABLE_RESOLVE_TIMEOUT_MS = 18_000L

    internal fun buildCoverDownloadCandidateUrls(song: SongItem): List<String> {
        return listOf(
            song.displayCoverUrl(),
            song.coverUrl,
            song.originalCoverUrl,
            song.customCoverUrl
        ).mapNotNull { candidate ->
            candidate
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeIf(::isNetworkCoverUrl)
        }.distinct()
    }

    private fun isNetworkCoverUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    internal fun isTransferSizeComplete(expectedBytes: Long?, actualBytes: Long): Boolean {
        val expectedSize = expectedBytes?.takeIf { it > 0L }
        if (expectedSize != null && actualBytes < expectedSize) {
            return false
        }
        return ManagedDownloadSizePolicy.isTransferSizeComplete(
            expectedSizeBytes = expectedSize,
            actualSizeBytes = actualBytes
        )
    }

    /**
     * 原始传输长度只适用于 TagLib 修改前的文件
     */
    internal fun resolveAudioCommitExpectedSize(
        transferExpectedBytes: Long?,
        bytesBeforeMetadata: Long,
        bytesAtCommit: Long
    ): Long? {
        val expectedBytes = transferExpectedBytes?.takeIf { it > 0L } ?: return null
        return expectedBytes.takeIf { bytesBeforeMetadata == bytesAtCommit }
    }

    internal fun resolveDownloadTransportKind(
        streamType: YouTubePlayableStreamType,
        request: Request
    ): AudioDownloadManager.DownloadTransportKind {
        if (streamType == YouTubePlayableStreamType.HLS) {
            return AudioDownloadManager.DownloadTransportKind.HLS
        }
        val headers = request.headers.names().associateWith { headerName ->
            request.header(headerName).orEmpty()
        }
        return if (
            YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(request) &&
            !ResumableHttpRangeSupport.hasExplicitRangeHeader(headers)
        ) {
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE
        } else {
            AudioDownloadManager.DownloadTransportKind.DIRECT
        }
    }

    internal fun buildResumeRangeHeader(completedBytes: Long): String? {
        return completedBytes
            .takeIf { it > 0L }
            ?.let { "bytes=$it-" }
    }

    internal fun resolveResumeValidatorHeader(
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): String? {
        return fingerprint?.etag?.trim()?.takeIf(::isStrongEtag)
    }

    private fun isStrongEtag(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length >= 2 &&
            !trimmed.startsWith("W/", ignoreCase = true) &&
            trimmed.startsWith('"') &&
            trimmed.endsWith('"')
    }

    internal fun shouldDiscardWorkingFileForResume(
        requestUrl: String,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Boolean {
        val recordedUrl = fingerprint?.sourceUrl
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        val currentUrl = requestUrl.trim()
        if (recordedUrl == currentUrl) {
            return false
        }
        if (!resolveResumeValidatorHeader(fingerprint).isNullOrBlank()) {
            return false
        }
        val recordedKey = resumeResourceKey(recordedUrl) ?: return false
        val currentKey = resumeResourceKey(currentUrl) ?: return false
        return recordedKey != currentKey
    }

    private fun resumeResourceKey(url: String): String? {
        val volatileQueryKeys = setOf(
            "alr",
            "expire",
            "expires",
            "lsig",
            "n",
            "sig",
            "signature",
            "sp",
            "st",
            "token"
        )
        return runCatching {
            val uri = java.net.URI(url)
            val query = uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val key = part.substringBefore('=').lowercase()
                    part.takeIf { key.isNotBlank() && key !in volatileQueryKeys }
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf(String::isNotBlank)
            buildString {
                append(uri.scheme.orEmpty().lowercase())
                append("://")
                append(uri.rawAuthority.orEmpty().lowercase())
                append(uri.rawPath.orEmpty())
                if (query != null) {
                    append('?')
                    append(query)
                }
            }
        }.getOrNull()
    }

    internal fun buildResumeRequest(
        request: Request,
        completedBytes: Long,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Request {
        val resumeRangeHeader = buildResumeRangeHeader(completedBytes) ?: return request
        val validator = resolveResumeValidatorHeader(fingerprint)
        return request.newBuilder()
            .header("Range", resumeRangeHeader)
            .header("Accept-Encoding", "identity")
            .removeHeader("If-Range")
            .apply {
                if (!validator.isNullOrBlank()) {
                    header("If-Range", validator)
                }
            }
            .build()
    }

    internal fun buildChunkResumeRequest(
        request: Request,
        start: Long,
        length: Long,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): Request {
        val validator = resolveResumeValidatorHeader(fingerprint)
        return YouTubeGoogleVideoRangeSupport.buildChunkedRequest(
            request = request,
            start = start,
            length = length
        ).newBuilder()
            .header("Accept-Encoding", "identity")
            .removeHeader("If-Range")
            .apply {
                if (start > 0L && !validator.isNullOrBlank()) {
                    header("If-Range", validator)
                }
            }
            .build()
    }

    internal fun resolveLatestResumeFingerprint(
        fallback: ManagedDownloadStorage.WorkingResumeFingerprint?,
        latest: ManagedDownloadStorage.WorkingResumeFingerprint?
    ): ManagedDownloadStorage.WorkingResumeFingerprint? {
        return latest ?: fallback
    }

    internal fun parseContentRange(headers: Map<String, List<String>>): AudioDownloadManager.ParsedContentRange? {
        val value = responseHeaderValue(headers, "Content-Range") ?: return null
        val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) {
            return null
        }
        return AudioDownloadManager.ParsedContentRange(start = start, end = end, total = total)
    }

    internal fun parseUnsatisfiedContentRangeTotal(headers: Map<String, List<String>>): Long? {
        val value = responseHeaderValue(headers, "Content-Range") ?: return null
        val match = Regex("""^bytes\s+\*/(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
    }

    internal fun isExactRangeEnd(
        headers: Map<String, List<String>>,
        resumedBytes: Long
    ): Boolean {
        return resumedBytes >= 0L && parseUnsatisfiedContentRangeTotal(headers) == resumedBytes
    }

    internal fun validatePartialContentRange(
        headers: Map<String, List<String>>,
        expectedStart: Long,
        bodyLength: Long? = null
    ): AudioDownloadManager.ParsedContentRange {
        val range = parseContentRange(headers)
            ?: throw IOException("缺少或非法 Content-Range")
        if (range.start != expectedStart) {
            throw IOException(
                "续传偏移不匹配: expected=$expectedStart, actual=${range.start}"
            )
        }
        if (bodyLength != null && bodyLength >= 0L && bodyLength != range.length) {
            throw IOException(
                "Content-Range 长度不匹配: expected=${range.length}, actual=$bodyLength"
            )
        }
        return range
    }

    internal fun isResumeResponseCompatible(
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint?,
        headers: Map<String, List<String>>,
        totalBytes: Long
    ): Boolean {
        val expectedEtag = fingerprint?.etag?.trim()?.takeIf(::isStrongEtag) ?: return false
        val actualEtag = responseHeaderValue(headers, "ETag")
            ?.trim()
            ?.takeIf(::isStrongEtag)
            ?: return false
        if (expectedEtag != actualEtag) {
            return false
        }
        val expectedTotal = fingerprint.expectedContentLength?.takeIf { it > 0L }
        return expectedTotal == null || expectedTotal == totalBytes
    }

    internal fun responseHeaderValue(
        headers: Map<String, List<String>>,
        name: String
    ): String? {
        return headers.entries.firstOrNull { (key, _) ->
            key.equals(name, ignoreCase = true)
        }?.value?.firstOrNull()?.takeIf(String::isNotBlank)
    }

    internal fun updateWorkingResumeFingerprint(
        destFile: File,
        requestUrl: String,
        headers: Map<String, List<String>>,
        expectedContentLength: Long?
    ): Boolean {
        return runCatching {
            ManagedDownloadStorage.updateWorkingResumeFingerprint(
                workingFile = destFile,
                fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
                    sourceUrl = requestUrl,
                    etag = responseHeaderValue(headers, "ETag"),
                    lastModified = responseHeaderValue(headers, "Last-Modified"),
                    expectedContentLength = expectedContentLength?.takeIf { it > 0L }
                )
            )
        }.onFailure { error ->
            NPLogger.e(TAG, "写入续传指纹失败，后续续传将退化为整文件重下: ${destFile.name}", error)
        }.getOrDefault(false)
    }

    internal fun resolveResponseExpectedBytes(
        requestUrl: String,
        headers: Map<String, List<String>>,
        bodyLength: Long,
        resumedBytes: Long,
        isPartialResponse: Boolean
    ): Long? {
        val contentRangeValue = responseHeaderValue(headers, "Content-Range")
        if (contentRangeValue != null) {
            return parseContentRange(headers)?.total
                ?: parseUnsatisfiedContentRangeTotal(headers)
        }
        if (bodyLength > 0L) {
            return if (isPartialResponse) {
                bodyLength + resumedBytes.coerceAtLeast(0L)
            } else {
                bodyLength
            }
        }

        if (YouTubeGoogleVideoRangeSupport.shouldUseChunkedRangeForDownload(requestUrl)) {
            return YouTubeGoogleVideoRangeSupport.resolveTotalContentLength(
                requestUrl,
                headers
            )?.takeIf { it > 0L }
        }
        return null
    }

    internal fun shouldPreservePartialDownloadForRetry(
        transportKind: AudioDownloadManager.DownloadTransportKind?,
        existingBytes: Long,
        hasHlsResumeState: Boolean
    ): Boolean {
        if (existingBytes <= 0L || transportKind == null) {
            return false
        }
        return when (transportKind) {
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE -> true
            AudioDownloadManager.DownloadTransportKind.HLS -> hasHlsResumeState
        }
    }

    internal fun advanceRetryWakeSignalVersion(currentVersion: Long): Long {
        return if (currentVersion == Long.MAX_VALUE) 0L else currentVersion + 1L
    }

    internal fun resolveYouTubeDownloadResolveAttempts(
        forceRefresh: Boolean
    ): List<AudioDownloadManager.YouTubeDownloadResolveAttempt> {
        val attempts = mutableListOf<AudioDownloadManager.YouTubeDownloadResolveAttempt>()
        if (!forceRefresh) {
            attempts += AudioDownloadManager.YouTubeDownloadResolveAttempt(
                forceRefresh = false,
                requireDirect = true,
                timeoutMs = YOUTUBE_DOWNLOAD_SHARED_DIRECT_RESOLVE_TIMEOUT_MS,
                shareInFlight = true
            )
        }
        attempts += AudioDownloadManager.YouTubeDownloadResolveAttempt(
            forceRefresh = true,
            requireDirect = true,
            timeoutMs = YOUTUBE_DOWNLOAD_FRESH_DIRECT_RESOLVE_TIMEOUT_MS,
            shareInFlight = false
        )
        if (!forceRefresh) {
            attempts += AudioDownloadManager.YouTubeDownloadResolveAttempt(
                forceRefresh = false,
                requireDirect = false,
                timeoutMs = YOUTUBE_DOWNLOAD_SHARED_PLAYABLE_RESOLVE_TIMEOUT_MS,
                shareInFlight = true
            )
        }
        attempts += AudioDownloadManager.YouTubeDownloadResolveAttempt(
            forceRefresh = true,
            requireDirect = false,
            timeoutMs = YOUTUBE_DOWNLOAD_FRESH_PLAYABLE_RESOLVE_TIMEOUT_MS,
            shareInFlight = false
        )
        return attempts
    }

    internal fun resolveTransientDownloadRetryDelayMs(attemptNumber: Int): Long {
        return when (attemptNumber.coerceAtLeast(1)) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            else -> 5_000L
        }
    }

    internal fun shouldStopRetryingMissingDownloadSource(
        confirmedMissCount: Int,
        hasConfirmedInternetAccess: Boolean
    ): Boolean {
        return hasConfirmedInternetAccess &&
            confirmedMissCount >= SOURCE_RESOLVE_MAX_CONFIRMED_MISSES
    }

    internal fun shouldRetryTransientDownloadFailure(error: Throwable): Boolean {
        if (error is java.util.concurrent.CancellationException) {
            return false
        }
        if (error is DownloadSourceUnavailableException) {
            return false
        }
        if (error is DownloadTransferStalledException) {
            return true
        }
        if (error is ChunkRequestIOException) {
            return isTransientHttpStatusCode(error.responseCode)
        }
        parseHttpStatusCode(error)?.let(::isTransientHttpStatusCode)?.let { shouldRetry ->
            return shouldRetry
        }
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is InterruptedIOException,
                is EOFException,
                is SSLException -> true

                is SocketException -> true
                is IOException -> isTransientNetworkMessage(cause.message)
                else -> false
            }
        }
    }

    internal fun shouldRetryDownloadFailureForSource(
        error: Throwable,
        isYouTubeMusic: Boolean
    ): Boolean {
        if (shouldRetryTransientDownloadFailure(error)) {
            return true
        }
        return isYouTubeMusic && shouldRefreshYouTubeDownloadSourceOnFailure(error)
    }

    internal fun shouldRefreshYouTubeDownloadSourceOnFailure(error: Throwable): Boolean {
        val statusCode = extractYouTubeDownloadHttpStatus(error) ?: return false
        return isRefreshableYouTubeDownloadStatusCode(statusCode)
    }

    // 403 表示服务端拒绝该直链: WEB_REMIX web-GVS 直链在脏 IP 下即便带 pot 也常被 403
    // (同一直链能 range 播放却下不了) ; 据此让下载重试改走不需 pot 的 HLS 兜底
    internal fun isForbiddenYouTubeDownloadFailure(error: Throwable): Boolean {
        return extractYouTubeDownloadHttpStatus(error) == 403
    }

    private fun extractYouTubeDownloadHttpStatus(error: Throwable): Int? {
        if (error is java.util.concurrent.CancellationException) {
            return null
        }
        return when (error) {
            is ChunkRequestIOException -> error.responseCode
            else -> parseHttpStatusCode(error)
        }
    }

    private fun isRefreshableYouTubeDownloadStatusCode(statusCode: Int): Boolean {
        return statusCode == 401 ||
            statusCode == 403 ||
            statusCode == 410 ||
            statusCode == 416 ||
            isTransientHttpStatusCode(statusCode)
    }

    private fun parseHttpStatusCode(error: Throwable): Int? {
        val message = error.message.orEmpty()
        return Regex("""HTTP\s+(\d{3})""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun isTransientHttpStatusCode(statusCode: Int): Boolean {
        return statusCode == 408 ||
            statusCode == 409 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599
    }

    private fun isTransientNetworkMessage(message: String?): Boolean {
        val normalized = message?.lowercase().orEmpty()
        if (normalized.isBlank()) {
            return false
        }
        return normalized.contains("unexpected end of stream") ||
            normalized.contains("connection shutdown") ||
            normalized.contains("connection reset") ||
            normalized.contains("connection abort") ||
            normalized.contains("broken pipe") ||
            normalized.contains("software caused connection abort") ||
            normalized.contains("failed to connect") ||
            normalized.contains("unable to resolve host") ||
            normalized.contains("network is unreachable") ||
            normalized.contains("stream was reset") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out")
    }

}
