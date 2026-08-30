package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.datasource.HttpDataSource
import okhttp3.Request
import java.io.IOException

internal data class ChunkLengthFallbackResult<T>(
    val chunkLength: Long,
    val value: T
)

internal class ChunkRequestIOException(
    val responseCode: Int,
    message: String
) : IOException(message)

internal object ResumableHttpRangeSupport {
    private const val DEFAULT_CHUNK_SIZE_BYTES = 1024L * 1024L
    private const val MIN_CHUNK_SIZE_BYTES = 128L * 1024L

    fun resolveQueryContentLength(url: String): Long? {
        return Regex("""(?:\?|&)clen=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
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
        return when (error) {
            is HttpDataSource.InvalidResponseCodeException -> error.responseCode == 416
            is ChunkRequestIOException -> error.responseCode == 416
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
        throw lastError ?: IOException("Unable to open resumable HTTP range")
    }

    fun resolveTotalContentLength(
        url: String,
        headers: Map<String, List<String>>
    ): Long? {
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

    fun resolveTotalContentLength(
        uri: Uri,
        headers: Map<String, List<String>>
    ): Long? {
        return resolveTotalContentLength(uri.toString(), headers)
    }

    fun resolveChunkResponseLength(
        requestedLength: Long,
        headers: Map<String, List<String>>,
        delegateOpenLength: Long
    ): Long {
        if (delegateOpenLength > 0L) {
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
        return value.substringAfter('/').trim().toLongOrNull()
    }

    private fun parseContentRangeLength(value: String): Long? {
        val rangePart = value.substringAfter("bytes", "")
            .trim()
            .substringBefore('/')
            .trim()
        val start = rangePart.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = rangePart.substringAfter('-', "").trim().toLongOrNull() ?: return null
        return (end - start + 1L).takeIf { it > 0L }
    }
}
