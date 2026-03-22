package moe.ouom.neriplayer.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.Request
import java.io.IOException
import java.util.Locale

internal data class ChunkLengthFallbackResult<T>(
    val chunkLength: Long,
    val value: T
)

internal class ChunkRequestIOException(
    val responseCode: Int,
    message: String
) : IOException(message)

internal object YouTubeGoogleVideoRangeSupport {
    private const val DEFAULT_CHUNK_SIZE_BYTES = 1024L * 1024L
    private const val MIN_CHUNK_SIZE_BYTES = 128L * 1024L

    fun supportsSeekingWithoutUrlRefresh(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (!host.contains("googlevideo.com")) {
            return false
        }
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        if (host.startsWith("manifest.") || path.contains("/api/manifest/")) {
            return true
        }
        if (path.contains("/playlist/index.m3u8") || path.contains("/file/seg.ts")) {
            return true
        }
        val queryParameters = parseQueryParameters(uri.rawQuery)
        val hasResolvedThrottling = queryParameters["n"]?.isNotBlank() == true
        val hasResolvedSignature =
            queryParameters["sig"]?.isNotBlank() == true ||
                queryParameters["signature"]?.isNotBlank() == true
        return hasResolvedThrottling && hasResolvedSignature
    }

    fun shouldUseChunkedRange(uri: Uri): Boolean {
        return shouldUseChunkedRange(uri.toString())
    }

    fun shouldUseChunkedRange(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US)
            ?: return false
        if (!host.contains("googlevideo.com")) {
            return false
        }
        if (supportsSeekingWithoutUrlRefresh(url)) {
            return false
        }
        val rawUrl = url.lowercase(Locale.US)
        return rawUrl.contains("source=youtube") || rawUrl.contains("/videoplayback")
    }

    fun shouldUseChunkedRange(request: Request): Boolean {
        return shouldUseChunkedRange(request.url.toString())
    }

    fun resolveQueryContentLength(url: String): Long? {
        return Regex("""(?:\?|&)clen=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun shouldForceExplicitFullRange(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (!host.contains("googlevideo.com")) {
            return false
        }
        val path = uri.path?.lowercase(Locale.US).orEmpty()
        if (host.startsWith("manifest.") || path.contains("/api/manifest/")) {
            return false
        }
        if (path.contains("/playlist/index.m3u8") || path.contains("/file/seg.ts")) {
            return false
        }
        return supportsSeekingWithoutUrlRefresh(url) && resolveQueryContentLength(url) != null
    }

    fun buildFullRangeHeader(totalContentLength: Long): String {
        require(totalContentLength > 0L) { "totalContentLength must be positive" }
        return "bytes=0-${totalContentLength - 1L}"
    }

    fun hasExplicitRangeHeader(headers: Map<String, String>): Boolean {
        return headers.keys.any { it.equals("Range", ignoreCase = true) }
    }

    fun candidateChunkLengths(
        requestLength: Long,
        preferredChunkSize: Long = DEFAULT_CHUNK_SIZE_BYTES
    ): List<Long> {
        val normalizedPreferredChunkSize = preferredChunkSize.coerceAtLeast(MIN_CHUNK_SIZE_BYTES)
        val maxChunk = when {
            requestLength in 1 until normalizedPreferredChunkSize -> requestLength
            else -> normalizedPreferredChunkSize
        }
        if (maxChunk <= 0L) {
            return listOf(normalizedPreferredChunkSize)
        }

        val candidates = linkedSetOf<Long>()
        var chunkSize = maxChunk
        while (chunkSize >= MIN_CHUNK_SIZE_BYTES) {
            candidates += chunkSize
            if (chunkSize == MIN_CHUNK_SIZE_BYTES) {
                break
            }
            chunkSize = (chunkSize / 2L).coerceAtLeast(MIN_CHUNK_SIZE_BYTES)
        }
        if (requestLength in 1 until MIN_CHUNK_SIZE_BYTES) {
            candidates += requestLength
        }
        candidates += MIN_CHUNK_SIZE_BYTES
        return candidates
            .filter { it > 0L }
            .distinct()
            .sortedDescending()
    }

    fun shouldRetryChunkError(error: IOException): Boolean {
        // 只对 416 (Range Not Satisfiable) 做 chunk 大小 fallback
        // 403 是 CDN 拒绝访问，缩小 chunk 无法解决，应直接抛出让上层刷新 URL
        return when (error) {
            is HttpDataSource.InvalidResponseCodeException -> {
                error.responseCode == 416
            }
            is ChunkRequestIOException -> {
                error.responseCode == 416
            }
            else -> false
        }
    }

    inline fun <T> executeChunkLengthFallback(
        requestLength: Long,
        preferredChunkSize: Long = DEFAULT_CHUNK_SIZE_BYTES,
        execute: (Long) -> T
    ): ChunkLengthFallbackResult<T> {
        val chunkCandidates = candidateChunkLengths(
            requestLength = requestLength,
            preferredChunkSize = preferredChunkSize
        )
        var lastError: IOException? = null
        chunkCandidates.forEachIndexed { index, chunkLength ->
            try {
                return ChunkLengthFallbackResult(
                    chunkLength = chunkLength,
                    value = execute(chunkLength)
                )
            } catch (error: IOException) {
                lastError = error
                val shouldRetry = shouldRetryChunkError(error) && index < chunkCandidates.lastIndex
                if (!shouldRetry) {
                    throw error
                }
            }
        }
        throw lastError ?: IOException("Unable to open chunked YouTube stream")
    }

    fun resolveTotalContentLength(uri: Uri, headers: Map<String, List<String>>): Long? {
        return resolveTotalContentLength(uri.toString(), headers)
    }

    fun resolveTotalContentLength(url: String, headers: Map<String, List<String>>): Long? {
        val fromContentRange = firstHeaderValue(headers, "Content-Range")
            ?.let(::parseContentRangeTotal)
            ?.takeIf { it > 0L }
        if (fromContentRange != null) {
            return fromContentRange
        }

        val fromQuery = resolveQueryContentLength(url)
        if (fromQuery != null) {
            return fromQuery
        }

        return firstHeaderValue(headers, "Content-Length")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    fun resolveChunkResponseLength(
        requestedLength: Long,
        headers: Map<String, List<String>>,
        delegateOpenLength: Long
    ): Long {
        if (delegateOpenLength > 0L && delegateOpenLength != C.LENGTH_UNSET.toLong()) {
            return delegateOpenLength
        }

        val fromRange = firstHeaderValue(headers, "Content-Range")
            ?.let(::parseContentRangeLength)
            ?.takeIf { it > 0L }
        if (fromRange != null) {
            return fromRange
        }

        val fromLength = firstHeaderValue(headers, "Content-Length")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        if (fromLength != null) {
            return fromLength
        }

        return requestedLength
    }

    fun buildChunkedRequest(request: Request, start: Long, length: Long): Request {
        require(start >= 0L) { "start must be non-negative" }
        require(length > 0L) { "length must be positive" }
        val end = start + length - 1L
        return request.newBuilder()
            .header("Range", "bytes=$start-$end")
            .build()
    }

    private fun firstHeaderValue(headers: Map<String, List<String>>, name: String): String? {
        return headers.entries.firstOrNull { (key, _) ->
            key.equals(name, ignoreCase = true)
        }?.value?.firstOrNull()
    }

    private fun parseContentRangeTotal(value: String): Long? {
        val totalPart = value.substringAfter('/').trim()
        return totalPart.toLongOrNull()
    }

    private fun parseContentRangeLength(value: String): Long? {
        val rangePart = value.substringAfter("bytes", "").trim()
            .substringBefore('/')
            .trim()
        val start = rangePart.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = rangePart.substringAfter('-', "").trim().toLongOrNull() ?: return null
        return (end - start + 1L).takeIf { it > 0L }
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        return rawQuery
            .split('&')
            .mapNotNull { segment ->
                val rawKey = segment.substringBefore('=')
                if (rawKey.isBlank()) {
                    null
                } else {
                    val rawValue = segment.substringAfter('=', "")
                    runCatching {
                        java.net.URLDecoder.decode(rawKey, Charsets.UTF_8.name()) to
                            java.net.URLDecoder.decode(rawValue, Charsets.UTF_8.name())
                    }.getOrElse {
                        rawKey to rawValue
                    }
                }
            }
            .toMap()
    }
}

@UnstableApi
internal class ConditionalChunkedHttpDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val transformDataSpec: (DataSpec) -> DataSpec
) : BaseDataSource(true), HttpDataSource {

    companion object {
        private const val TAG = "YouTubeChunkedDs"
    }

    private val requestProperties = linkedMapOf<String, String>()

    private var upstream: HttpDataSource? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var currentResponseHeaders: Map<String, List<String>> = emptyMap()
    private var currentResponseCode: Int = -1

    private var initialSpec: DataSpec? = null
    private var transformedSpec: DataSpec? = null
    private var chunkedMode = false
    private var bytesReadFromRequest = 0L
    private var bytesRemainingInRequest = C.LENGTH_UNSET.toLong()
    private var bytesRemainingInChunk = 0L
    private var totalContentLength: Long? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        closeUpstreamQuietly()

        val mergedSpec = mergeRequestProperties(dataSpec)
        val preparedSpec = transformDataSpec(mergedSpec)
        initialSpec = dataSpec
        transformedSpec = preparedSpec
        bytesReadFromRequest = 0L
        bytesRemainingInChunk = 0L
        currentResponseHeaders = emptyMap()
        currentResponseCode = -1
        currentUri = preparedSpec.uri
        totalContentLength = null
        chunkedMode = shouldChunk(preparedSpec)

        if (chunkedMode) {
            bytesRemainingInRequest = if (preparedSpec.length != C.LENGTH_UNSET.toLong()) {
                preparedSpec.length
            } else {
                C.LENGTH_UNSET.toLong()
            }
            openChunk(startPosition = preparedSpec.position)
        } else {
            val delegate = upstreamFactory.createDataSource()
            val openLength = delegate.open(preparedSpec)
            bindOpenResult(delegate)
            bytesRemainingInRequest = openLength
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemainingInRequest
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }

        while (true) {
            if (bytesRemainingInRequest == 0L) {
                return C.RESULT_END_OF_INPUT
            }
            if (chunkedMode && bytesRemainingInChunk == 0L) {
                if (!openNextChunk()) {
                    return C.RESULT_END_OF_INPUT
                }
            }
            val delegate = upstream ?: return C.RESULT_END_OF_INPUT

            val maxReadable = when {
                chunkedMode && bytesRemainingInRequest > 0L -> {
                    minOf(length.toLong(), bytesRemainingInRequest, bytesRemainingInChunk)
                }
                chunkedMode -> minOf(length.toLong(), bytesRemainingInChunk)
                bytesRemainingInRequest > 0L -> minOf(length.toLong(), bytesRemainingInRequest)
                else -> length.toLong()
            }.toInt()

            val readLength = if (maxReadable > 0) maxReadable else length
            val read = delegate.read(buffer, offset, readLength)
            if (read == C.RESULT_END_OF_INPUT) {
                if (!chunkedMode) {
                    bytesRemainingInRequest = 0L
                    return C.RESULT_END_OF_INPUT
                }
                bytesRemainingInChunk = 0L
                continue
            }

            bytesReadFromRequest += read
            if (bytesRemainingInRequest != C.LENGTH_UNSET.toLong()) {
                bytesRemainingInRequest = (bytesRemainingInRequest - read).coerceAtLeast(0L)
            }
            if (chunkedMode) {
                bytesRemainingInChunk = (bytesRemainingInChunk - read).coerceAtLeast(0L)
            }
            bytesTransferred(read)
            return read
        }
    }

    override fun close() {
        closeUpstreamQuietly()
        if (opened) {
            opened = false
            transferEnded()
        }
        currentUri = null
        currentResponseHeaders = emptyMap()
        currentResponseCode = -1
        initialSpec = null
        transformedSpec = null
        chunkedMode = false
        bytesReadFromRequest = 0L
        bytesRemainingInRequest = C.LENGTH_UNSET.toLong()
        bytesRemainingInChunk = 0L
        totalContentLength = null
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = currentResponseHeaders

    override fun getResponseCode(): Int = currentResponseCode

    override fun setRequestProperty(name: String, value: String) {
        requestProperties[name] = value
        upstream?.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let {
            requestProperties.remove(it)
        }
        upstream?.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
        upstream?.clearAllRequestProperties()
    }

    private fun shouldChunk(dataSpec: DataSpec): Boolean {
        return dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            YouTubeGoogleVideoRangeSupport.shouldUseChunkedRange(dataSpec.uri) &&
            !YouTubeGoogleVideoRangeSupport.hasExplicitRangeHeader(dataSpec.httpRequestHeaders)
    }

    private fun mergeRequestProperties(dataSpec: DataSpec): DataSpec {
        if (requestProperties.isEmpty()) {
            return dataSpec
        }
        val mergedHeaders = LinkedHashMap<String, String>(dataSpec.httpRequestHeaders).apply {
            putAll(requestProperties)
        }
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(mergedHeaders)
            .build()
    }

    private fun openNextChunk(): Boolean {
        val baseSpec = transformedSpec ?: return false
        if (bytesRemainingInRequest == 0L) {
            return false
        }
        return try {
            openChunk(startPosition = baseSpec.position + bytesReadFromRequest)
            true
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode == 416 || error.responseCode == 403) {
                bytesRemainingInRequest = 0L
                false
            } else {
                throw error
            }
        }
    }

    private fun openChunk(startPosition: Long) {
        val baseSpec = transformedSpec ?: throw IOException("Missing transformed DataSpec")
        closeUpstreamQuietly()

        val requestedRemaining = when {
            bytesRemainingInRequest > 0L -> bytesRemainingInRequest
            else -> C.LENGTH_UNSET.toLong()
        }
        val firstChunkLength = YouTubeGoogleVideoRangeSupport
            .candidateChunkLengths(requestedRemaining)
            .first()
        data class OpenedChunk(
            val delegate: HttpDataSource,
            val chunkSpec: DataSpec,
            val effectiveLength: Long,
            val openLength: Long
        )

        val openResult = YouTubeGoogleVideoRangeSupport.executeChunkLengthFallback(requestedRemaining) { chunkLength ->
            val effectiveLength = when {
                requestedRemaining == C.LENGTH_UNSET.toLong() -> chunkLength
                else -> minOf(chunkLength, requestedRemaining)
            }
            val chunkSpec = baseSpec.subrange(startPosition - baseSpec.position, effectiveLength)
            val delegate = upstreamFactory.createDataSource()
            try {
                val openLength = delegate.open(chunkSpec)
                OpenedChunk(
                    delegate = delegate,
                    chunkSpec = chunkSpec,
                    effectiveLength = effectiveLength,
                    openLength = openLength
                )
            } catch (error: IOException) {
                runCatching { delegate.close() }
                throw error
            }
        }

        bindOpenResult(openResult.value.delegate)
        val resolvedChunkLength = YouTubeGoogleVideoRangeSupport.resolveChunkResponseLength(
            requestedLength = openResult.value.effectiveLength,
            headers = currentResponseHeaders,
            delegateOpenLength = openResult.value.openLength
        )
        totalContentLength = YouTubeGoogleVideoRangeSupport.resolveTotalContentLength(
            uri = openResult.value.chunkSpec.uri,
            headers = currentResponseHeaders
        ) ?: totalContentLength
        bytesRemainingInChunk = resolvedChunkLength.coerceAtLeast(0L)

        if (requestedRemaining == C.LENGTH_UNSET.toLong()) {
            bytesRemainingInRequest = when {
                totalContentLength != null -> {
                    (totalContentLength!! - startPosition).coerceAtLeast(0L)
                }
                resolvedChunkLength < openResult.value.effectiveLength -> resolvedChunkLength
                else -> C.LENGTH_UNSET.toLong()
            }
        }

        if (openResult.chunkLength != openResult.value.effectiveLength) {
            NPLogger.w(
                TAG,
                "Chunk size clamped for ${openResult.value.chunkSpec.uri.host}: ${openResult.value.effectiveLength} bytes"
            )
        } else if (openResult.chunkLength != firstChunkLength) {
            NPLogger.w(
                TAG,
                "Chunk size fallback applied for ${openResult.value.chunkSpec.uri.host}: ${openResult.value.effectiveLength} bytes"
            )
        }
    }

    private fun bindOpenResult(delegate: HttpDataSource) {
        upstream = delegate
        currentUri = delegate.uri ?: transformedSpec?.uri
        currentResponseHeaders = delegate.responseHeaders
        currentResponseCode = delegate.responseCode
    }

    private fun closeUpstreamQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}
