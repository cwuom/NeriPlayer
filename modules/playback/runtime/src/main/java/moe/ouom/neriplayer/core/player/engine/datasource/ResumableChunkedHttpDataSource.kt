@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import moe.ouom.neriplayer.network.range.ResumableHttpRangeSupport
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.resolver.netease.shouldUseNeteaseFlacResumableRange
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 负责把可恢复的 HTTP 媒体请求拆成有界的 Range，并在短读后从准确偏移继续
 */
@UnstableApi
internal class ResumableChunkedHttpDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val transformDataSpec: (DataSpec) -> DataSpec,
    private val rangePolicy: ResumableChunkedHttpRangePolicy =
        defaultResumableChunkedHttpRangePolicy
) : BaseDataSource(true), HttpDataSource {

    companion object {
        private const val TAG = "ResumableChunkedDs"
        private val UNSATISFIED_RANGE = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
    }

    private data class OpenedRange(val spec: DataSpec, val openLength: Long)

    private val requestProperties = linkedMapOf<String, String>()

    private var upstream: HttpDataSource? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var currentResponseHeaders: Map<String, List<String>> = emptyMap()
    private var currentResponseCode: Int = -1

    private var transformedSpec: DataSpec? = null
    private var chunkedMode = false
    private var neteaseChunkSize: Long? = null
    private var bytesReadFromRequest = 0L
    private var bytesReadAtChunkStart = 0L
    private var retriedZeroProgressChunk = false
    private var bytesRemainingInRequest = C.LENGTH_UNSET.toLong()
    private var bytesRemainingInChunk = 0L
    private var totalContentLength: Long? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        closeUpstreamQuietly()

        val mergedSpec = mergeRequestProperties(dataSpec)
        val preparedSpec = transformDataSpec(mergedSpec)
        transformedSpec = preparedSpec
        bytesReadFromRequest = 0L
        bytesReadAtChunkStart = 0L
        retriedZeroProgressChunk = false
        bytesRemainingInChunk = 0L
        currentResponseHeaders = emptyMap()
        currentResponseCode = -1
        currentUri = preparedSpec.uri
        totalContentLength = null
        chunkedMode = shouldChunk(preparedSpec)
        if (chunkedMode) {
            neteaseChunkSize = if (shouldUseNeteaseFlacResumableRange(preparedSpec.uri)) {
                rangePolicy.initialChunkSizeOverride(preparedSpec.uri) ?: ResumableHttpRangeSupport.DEFAULT_CHUNK_SIZE_BYTES
            } else null
            NPLogger.d(
                TAG,
                "enable resumable HTTP ranges: host=${preparedSpec.uri.host}, " +
                    "position=${preparedSpec.position}, length=${preparedSpec.length}"
            )
        }

        try {
            if (chunkedMode) {
                bytesRemainingInRequest = if (
                    preparedSpec.length != C.LENGTH_UNSET.toLong()
                ) {
                    preparedSpec.length
                } else {
                    C.LENGTH_UNSET.toLong()
                }
                openChunk(startPosition = preparedSpec.position)
            } else {
                bytesRemainingInRequest = openUpstream(preparedSpec).openLength
            }
        } catch (error: IOException) {
            closeUpstreamQuietly()
            throw error
        }

        opened = true
        transferStarted(dataSpec)
        if (chunkedMode && neteaseChunkSize != null && dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec.length
        return bytesRemainingInRequest
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        while (true) {
            if (bytesRemainingInRequest == 0L) return C.RESULT_END_OF_INPUT
            if (chunkedMode && bytesRemainingInChunk == 0L) {
                if (!openNextChunk()) return C.RESULT_END_OF_INPUT
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
            val read = try {
                delegate.read(buffer, offset, readLength)
            } catch (error: IOException) {
                closeUpstreamQuietly()
                throw error
            }
            if (read == C.RESULT_END_OF_INPUT) {
                if (!chunkedMode) {
                    bytesRemainingInRequest = 0L
                    return C.RESULT_END_OF_INPUT
                }
                val madeProgressInChunk = bytesReadFromRequest > bytesReadAtChunkStart
                if (!madeProgressInChunk) {
                    if (retriedZeroProgressChunk) {
                        closeUpstreamQuietly()
                        throw IOException(
                            "Resumable range returned EOF twice without progress: " +
                                "host=${currentUri?.host}, " +
                                "position=${basePositionForDiagnostics()}"
                        )
                    }
                    retriedZeroProgressChunk = true
                    NPLogger.w(
                        TAG,
                        "retry resumable range after zero-byte EOF: " +
                            "host=${currentUri?.host}, " +
                            "position=${basePositionForDiagnostics()}"
                    )
                } else {
                    retriedZeroProgressChunk = false
                }
                if (bytesRemainingInChunk > 0L) {
                    NPLogger.w(
                        TAG,
                        "resumable range ended early: host=${currentUri?.host}, " +
                            "position=${basePositionForDiagnostics()}, " +
                            "remainingInRange=$bytesRemainingInChunk"
                    )
                }
                bytesRemainingInChunk = 0L
                continue
            }

            if (read <= 0) {
                closeUpstreamQuietly()
                throw IOException(
                    "Resumable range returned an invalid read size: " +
                        "host=${currentUri?.host}, read=$read"
                )
            }
            bytesReadFromRequest += read
            retriedZeroProgressChunk = false
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
        transformedSpec = null
        chunkedMode = false
        neteaseChunkSize = null
        bytesReadFromRequest = 0L
        bytesReadAtChunkStart = 0L
        retriedZeroProgressChunk = false
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

    private fun shouldChunk(dataSpec: DataSpec): Boolean = rangePolicy.shouldUse(dataSpec)

    private fun mergeRequestProperties(dataSpec: DataSpec): DataSpec {
        if (requestProperties.isEmpty()) return dataSpec
        val mergedHeaders = LinkedHashMap<String, String>(dataSpec.httpRequestHeaders).apply {
            putAll(requestProperties)
        }
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(mergedHeaders)
            .build()
    }

    private fun openNextChunk(): Boolean {
        val baseSpec = transformedSpec ?: return false
        if (bytesRemainingInRequest == 0L) return false
        val nextStartPosition = baseSpec.position + bytesReadFromRequest
        val openTime = if (NPLogger.isFileLoggingEnabled) " atMs=${SystemClock.elapsedRealtime()}" else ""
        NPLogger.d(
            TAG,
            "open next resumable range: host=${baseSpec.uri.host}, " +
                "position=$nextStartPosition, remaining=$bytesRemainingInRequest$openTime"
        )
        return try {
            openChunk(startPosition = nextStartPosition)
            bytesRemainingInRequest != 0L
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            val knownLength = totalContentLength
            val reachedKnownEnd = neteaseChunkSize == null && error.responseCode == 416 &&
                knownLength != null &&
                nextStartPosition >= knownLength
            if (reachedKnownEnd) {
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

        val requestedRemaining = if (bytesRemainingInRequest > 0L) {
            bytesRemainingInRequest
        } else {
            C.LENGTH_UNSET.toLong()
        }
        val preferredChunkSize = neteaseChunkSize ?: ResumableHttpRangeSupport.DEFAULT_CHUNK_SIZE_BYTES
        val firstChunkLength = ResumableHttpRangeSupport
            .candidateChunkLengths(requestedRemaining, preferredChunkSize)
            .first()
        val openResult = ResumableHttpRangeSupport.executeChunkLengthFallback(
            requestedRemaining, preferredChunkSize
        ) { chunkLength ->
            val effectiveLength = if (requestedRemaining == C.LENGTH_UNSET.toLong()) {
                chunkLength
            } else {
                minOf(chunkLength, requestedRemaining)
            }
            val chunkSpec = baseSpec.subrange(
                startPosition - baseSpec.position,
                effectiveLength
            )
            try {
                val wireSpec = if (neteaseChunkSize != null) {
                    // 单独发送 Range，让 OkHttp 保留代理忽略范围时返回的整个 200 响应
                    val range = requireNotNull(HttpUtil.buildRangeRequestHeader(startPosition, effectiveLength))
                    chunkSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong())
                        .setHttpRequestHeaders(chunkSpec.httpRequestHeaders + ("Range" to range)).build()
                } else chunkSpec
                openUpstream(wireSpec, chunkSpec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (error.responseCode == 416 && finishNeteaseRangeAtEnd(startPosition, error.headerFields)) return
                throw error
            }
        }

        val range = openResult.value
        try {
            if (neteaseChunkSize != null && handleNeteaseResponse(baseSpec, range)) return
            rangePolicy.validateResponse(
                range.spec.uri,
                currentResponseCode,
                currentResponseHeaders,
                startPosition
            )
        } catch (error: IOException) {
            closeUpstreamQuietly()
            throw error
        }

        // 代理或 CDN 接受较小范围后，本次读取沿用该大小
        neteaseChunkSize = neteaseChunkSize?.coerceAtMost(openResult.chunkLength)
        updateRangeLengths(range, requestedRemaining, startPosition)

        val requestedLength = openResult.value.spec.length
        if (openResult.chunkLength != requestedLength) {
            NPLogger.w(TAG, "chunk size clamped for ${range.spec.uri.host}: $requestedLength bytes")
        } else if (openResult.chunkLength != firstChunkLength) {
            NPLogger.w(TAG, "chunk size fallback applied for ${range.spec.uri.host}: $requestedLength bytes")
        }
    }

    private fun updateRangeLengths(range: OpenedRange, requestedRemaining: Long, startPosition: Long) {
        val neteasePartial = neteaseChunkSize != null && currentResponseCode == 206
        val resolvedChunkLength = ResumableHttpRangeSupport.resolveChunkResponseLength(
            requestedLength = range.spec.length,
            headers = currentResponseHeaders,
            delegateOpenLength = if (neteasePartial) C.LENGTH_UNSET.toLong() else range.openLength
        )
        if (resolvedChunkLength <= 0L) {
            closeUpstreamQuietly()
            throw IOException(
                "Resumable range returned an empty response: " +
                    "host=${currentUri?.host}, position=$startPosition"
            )
        }
        val boundedChunkLength = resolvedChunkLength.coerceAtMost(range.spec.length)
        // 206 的 Content-Length 只描述这一段，文件总长可以是未知的
        totalContentLength = if (neteasePartial) {
            currentResponseHeaders.headerValue("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
        } else {
            ResumableHttpRangeSupport.resolveTotalContentLength(range.spec.uri, currentResponseHeaders)
        } ?: totalContentLength
        bytesRemainingInChunk = boundedChunkLength
        bytesReadAtChunkStart = bytesReadFromRequest

        if (requestedRemaining == C.LENGTH_UNSET.toLong()) {
            bytesRemainingInRequest = when {
                totalContentLength != null -> {
                    (totalContentLength!! - startPosition).coerceAtLeast(0L)
                }
                !neteasePartial && resolvedChunkLength < range.spec.length -> resolvedChunkLength
                else -> C.LENGTH_UNSET.toLong()
            }
        }
        if (currentResponseCode == 200 && bytesRemainingInRequest == C.LENGTH_UNSET.toLong()) {
            bytesRemainingInRequest = boundedChunkLength
        }
    }

    private fun openUpstream(wireSpec: DataSpec, logicalSpec: DataSpec = wireSpec): OpenedRange {
        val delegate = upstreamFactory.createDataSource()
        val length = try {
            delegate.open(wireSpec)
        } catch (error: IOException) {
            runCatching { delegate.close() }
            throw error
        }
        bindOpenResult(delegate)
        return OpenedRange(logicalSpec, length)
    }

    private fun handleNeteaseResponse(baseSpec: DataSpec, range: OpenedRange): Boolean {
        val start = range.spec.position
        if (currentResponseCode == 416) {
            if (finishNeteaseRangeAtEnd(start, currentResponseHeaders)) return true
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        if (currentResponseCode != 200) return false
        val total = range.openLength
        if (total != C.LENGTH_UNSET.toLong() && start > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        skipFullResponsePrefix(start)
        chunkedMode = false
        val remainder = baseSpec.subrange(start - baseSpec.position)
        bytesRemainingInRequest = when {
            remainder.length != C.LENGTH_UNSET.toLong() -> remainder.length
            total != C.LENGTH_UNSET.toLong() -> total - start
            else -> C.LENGTH_UNSET.toLong()
        }
        return true
    }

    private fun skipFullResponsePrefix(start: Long) {
        if (start == 0L) return
        val source = requireNotNull(upstream)
        val buffer = ByteArray(minOf(start, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        var remaining = start
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val count = try {
                source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException().apply { initCause(error) }
                throw error
            }
            if (count == C.RESULT_END_OF_INPUT) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            if (count <= 0) throw IOException("No progress while skipping Netease FLAC response prefix")
            remaining -= count
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
    }

    private fun finishNeteaseRangeAtEnd(start: Long, headers: Map<String, List<String>>): Boolean {
        if (neteaseChunkSize == null) return false
        val contentRange = headers.headerValue("Content-Range")
        val end = if (contentRange == null) totalContentLength else
            UNSATISFIED_RANGE.matchEntire(contentRange.trim())?.groupValues?.get(1)?.toLongOrNull()
        if (start != end) return false
        closeUpstreamQuietly()
        currentResponseCode = 416
        currentResponseHeaders = headers
        bytesRemainingInRequest = 0L
        bytesRemainingInChunk = 0L
        return true
    }

    private fun bindOpenResult(delegate: HttpDataSource) {
        upstream = delegate
        currentUri = delegate.uri ?: transformedSpec?.uri
        val upstreamResponseHeaders = delegate.responseHeaders
        val policyUri = transformedSpec?.uri ?: currentUri
        currentResponseHeaders = policyUri?.let { uri ->
            rangePolicy.normalizeResponseHeaders(uri, upstreamResponseHeaders)
        } ?: upstreamResponseHeaders
        currentResponseCode = delegate.responseCode
        if (currentResponseHeaders != upstreamResponseHeaders) {
            NPLogger.d(
                TAG,
                "normalized response headers for extractor: host=${policyUri?.host}, " +
                    "upstreamContentType=${upstreamResponseHeaders.headerValue("Content-Type")}, " +
                    "extractorContentType=${currentResponseHeaders.headerValue("Content-Type")}"
            )
        }
    }

    private fun basePositionForDiagnostics(): Long {
        return (transformedSpec?.position ?: 0L) + bytesReadFromRequest
    }

    private fun closeUpstreamQuietly() {
        runCatching { upstream?.close() }
        upstream = null
    }
}

private fun Map<String, List<String>>.headerValue(name: String): String? {
    return entries.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.joinToString(",")
        ?.takeIf { it.isNotBlank() }
}
