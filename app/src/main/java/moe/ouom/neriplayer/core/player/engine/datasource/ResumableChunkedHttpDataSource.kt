@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.IOException

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
    }

    private val requestProperties = linkedMapOf<String, String>()

    private var upstream: HttpDataSource? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var currentResponseHeaders: Map<String, List<String>> = emptyMap()
    private var currentResponseCode: Int = -1

    private var transformedSpec: DataSpec? = null
    private var chunkedMode = false
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
                val delegate = upstreamFactory.createDataSource()
                val openLength = try {
                    delegate.open(preparedSpec)
                } catch (error: IOException) {
                    runCatching { delegate.close() }
                    throw error
                }
                bindOpenResult(delegate)
                bytesRemainingInRequest = openLength
            }
        } catch (error: IOException) {
            closeUpstreamQuietly()
            throw error
        }

        opened = true
        transferStarted(dataSpec)
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
        NPLogger.d(
            TAG,
            "open next resumable range: host=${baseSpec.uri.host}, " +
                "position=$nextStartPosition, remaining=$bytesRemainingInRequest"
        )
        return try {
            openChunk(startPosition = nextStartPosition)
            true
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            val knownLength = totalContentLength
            val reachedKnownEnd = error.responseCode == 416 &&
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
        val firstChunkLength = ResumableHttpRangeSupport
            .candidateChunkLengths(requestedRemaining)
            .first()
        data class OpenedChunk(
            val delegate: HttpDataSource,
            val chunkSpec: DataSpec,
            val effectiveLength: Long,
            val openLength: Long
        )

        val openResult = ResumableHttpRangeSupport.executeChunkLengthFallback(
            requestedRemaining
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
        try {
            rangePolicy.validateResponse(
                openResult.value.chunkSpec.uri,
                currentResponseCode,
                currentResponseHeaders,
                startPosition
            )
        } catch (error: IOException) {
            closeUpstreamQuietly()
            throw error
        }

        val resolvedChunkLength = ResumableHttpRangeSupport.resolveChunkResponseLength(
            requestedLength = openResult.value.effectiveLength,
            headers = currentResponseHeaders,
            delegateOpenLength = openResult.value.openLength
        )
        if (resolvedChunkLength <= 0L) {
            closeUpstreamQuietly()
            throw IOException(
                "Resumable range returned an empty response: " +
                    "host=${currentUri?.host}, position=$startPosition"
            )
        }
        val boundedChunkLength = resolvedChunkLength.coerceAtMost(
            openResult.value.effectiveLength
        )
        totalContentLength = ResumableHttpRangeSupport.resolveTotalContentLength(
            uri = openResult.value.chunkSpec.uri,
            headers = currentResponseHeaders
        ) ?: totalContentLength
        bytesRemainingInChunk = boundedChunkLength
        bytesReadAtChunkStart = bytesReadFromRequest

        if (requestedRemaining == C.LENGTH_UNSET.toLong()) {
            bytesRemainingInRequest = when {
                totalContentLength != null -> {
                    (totalContentLength!! - startPosition).coerceAtLeast(0L)
                }
                resolvedChunkLength < openResult.value.effectiveLength -> resolvedChunkLength
                else -> C.LENGTH_UNSET.toLong()
            }
        }
        if (currentResponseCode == 200 && bytesRemainingInRequest == C.LENGTH_UNSET.toLong()) {
            bytesRemainingInRequest = boundedChunkLength
        }

        if (openResult.chunkLength != openResult.value.effectiveLength) {
            NPLogger.w(
                TAG,
                "chunk size clamped for ${openResult.value.chunkSpec.uri.host}: " +
                    "${openResult.value.effectiveLength} bytes"
            )
        } else if (openResult.chunkLength != firstChunkLength) {
            NPLogger.w(
                TAG,
                "chunk size fallback applied for ${openResult.value.chunkSpec.uri.host}: " +
                    "${openResult.value.effectiveLength} bytes"
            )
        }
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
