package moe.ouom.neriplayer.platform.youtube.api.transport

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer

const val YOUTUBE_TEXT_RESPONSE_MAX_BYTES = 8L * 1024L * 1024L
const val YOUTUBE_ERROR_RESPONSE_MAX_BYTES = 64L * 1024L

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

fun ResponseBody.readTextWithLimit(maxBytes: Long): String {
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

fun ResponseBody.readErrorPreviewWithLimit(maxBytes: Long): String {
    return try {
        summarizeYouTubeErrorBody(readTextWithLimit(maxBytes))
    } catch (_: YouTubeResponseTooLargeException) {
        "<response body exceeds $maxBytes bytes>"
    }
}

private const val YOUTUBE_ERROR_PREVIEW_CHARS = 160
private val YOUTUBE_ERROR_MESSAGE_FIELD = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/**
 * Google API 的错误体是整段 JSON，异常消息会原样显示在界面上，只保留其中的 error.message；
 * 预览截断后 JSON 可能不完整，所以按字段匹配而不是整体解析
 */
internal fun summarizeYouTubeErrorBody(body: String): String {
    if (body.trimStart().startsWith("{")) {
        val message = YOUTUBE_ERROR_MESSAGE_FIELD.find(body)?.groupValues?.get(1)
        if (!message.isNullOrBlank()) return message.take(YOUTUBE_ERROR_PREVIEW_CHARS)
    }
    return body.take(YOUTUBE_ERROR_PREVIEW_CHARS)
}
