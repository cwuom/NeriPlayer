package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer

internal const val YOUTUBE_TEXT_RESPONSE_MAX_BYTES = 8L * 1024L * 1024L
internal const val YOUTUBE_ERROR_RESPONSE_MAX_BYTES = 64L * 1024L

internal class YouTubeResponseTooLargeException(
    limitBytes: Long,
    declaredBytes: Long? = null
) : IOException(
    buildString {
        append("YouTube response exceeds ")
        append(limitBytes)
        append(" bytes")
        declaredBytes?.let {
            append(" (declared ")
            append(it)
            append(')')
        }
    }
)

internal fun ResponseBody.readTextWithLimit(maxBytes: Long): String {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val declaredBytes = contentLength()
    if (declaredBytes > maxBytes) {
        throw YouTubeResponseTooLargeException(maxBytes, declaredBytes)
    }

    val sink = Buffer()
    val source = source()
    var totalBytes = 0L
    while (true) {
        val remainingBytes = maxBytes - totalBytes
        val readBytes = source.read(
            sink,
            minOf(remainingBytes + 1L, 64L * 1024L)
        )
        if (readBytes == -1L) {
            break
        }
        totalBytes += readBytes
        if (totalBytes > maxBytes) {
            throw YouTubeResponseTooLargeException(maxBytes)
        }
    }

    val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return sink.readString(charset)
}

internal fun ResponseBody.readErrorPreviewWithLimit(maxBytes: Long): String {
    return try {
        readTextWithLimit(maxBytes).take(160)
    } catch (_: YouTubeResponseTooLargeException) {
        "<response body exceeds $maxBytes bytes>"
    }
}
