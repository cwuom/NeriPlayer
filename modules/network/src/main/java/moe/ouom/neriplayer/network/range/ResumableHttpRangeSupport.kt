package moe.ouom.neriplayer.network.range

import android.net.Uri
import androidx.media3.datasource.HttpDataSource
import okhttp3.Request
import java.io.IOException

data class ChunkLengthFallbackResult<T>(
    val chunkLength: Long,
    val value: T
)

class ChunkRequestIOException(
    val responseCode: Int,
    message: String
) : IOException(message)

object ResumableHttpRangeSupport {
    @PublishedApi
    internal const val DEFAULT_CHUNK_SIZE_BYTES = 1024L * 1024L
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
        val candidates = halvedChunkLengths(firstChunkLength(requestLength, normalizedPreferredChunkSize))
        if (requestLength in 1 until MIN_CHUNK_SIZE_BYTES) {
            candidates += requestLength
        }
        return candidates.sortedDescending()
    }

    private fun firstChunkLength(requestLength: Long, preferredChunkSize: Long): Long =
        if (requestLength in 1 until preferredChunkSize) requestLength else preferredChunkSize

    private fun halvedChunkLengths(firstChunkLength: Long): MutableSet<Long> {
        val candidates = linkedSetOf<Long>()
        var chunkSize = firstChunkLength
        while (chunkSize > MIN_CHUNK_SIZE_BYTES) {
            candidates += chunkSize
            chunkSize = (chunkSize / 2L).coerceAtLeast(MIN_CHUNK_SIZE_BYTES)
        }
        candidates += MIN_CHUNK_SIZE_BYTES
        return candidates
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
    ): Long? = positiveHeaderValue(headers, "Content-Range", ::parseContentRangeTotal)
        ?: resolveQueryContentLength(url)
        ?: positiveHeaderValue(headers, "Content-Length", String::toLongOrNull)

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
        return positiveHeaderValue(headers, "Content-Range", ::parseContentRangeLength)
            ?: positiveHeaderValue(headers, "Content-Length", String::toLongOrNull)
            ?: requestedLength
    }

    private fun positiveHeaderValue(
        headers: Map<String, List<String>>,
        name: String,
        parse: (String) -> Long?
    ): Long? {
        val value = firstHeaderValue(headers, name) ?: return null
        return parse(value)?.takeIf { it > 0L }
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
