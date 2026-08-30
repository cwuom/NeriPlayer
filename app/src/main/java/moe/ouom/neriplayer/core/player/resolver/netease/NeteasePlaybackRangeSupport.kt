package moe.ouom.neriplayer.core.player.resolver.netease

import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.IOException
import java.util.Locale

private val NETEASE_CONTENT_RANGE_PATTERN = Regex(
    "bytes\\s+(\\d+)\\s*-\\s*(\\d+)\\s*/\\s*(?:\\d+|\\*)",
    RegexOption.IGNORE_CASE
)

internal fun shouldUseNeteaseFlacResumableRange(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host = uri.host?.lowercase(Locale.US) ?: return false
    if (host != "music.126.net" && !host.endsWith(".music.126.net")) return false

    return uri.path?.lowercase(Locale.US)?.endsWith(".flac") == true
}

internal fun normalizeNeteaseFlacResponseContentType(
    uri: Uri,
    responseHeaders: Map<String, List<String>>
): Map<String, List<String>> {
    if (!shouldUseNeteaseFlacResumableRange(uri)) return responseHeaders

    val contentType = responseHeaders.firstHeaderValue("Content-Type")
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
    if (contentType == MimeTypes.AUDIO_FLAC) return responseHeaders
    if (
        contentType != null &&
        contentType !in setOf(
            MimeTypes.AUDIO_MPEG,
            "audio/x-flac",
            "application/flac",
            "application/octet-stream"
        )
    ) {
        return responseHeaders
    }

    return buildMap {
        responseHeaders.forEach { (name, values) ->
            if (!name.equals("Content-Type", ignoreCase = true)) {
                put(name, values)
            }
        }
        put("Content-Type", listOf(MimeTypes.AUDIO_FLAC))
    }
}

internal fun validateNeteaseFlacRangeResponse(
    uri: Uri,
    responseCode: Int,
    responseHeaders: Map<String, List<String>>,
    requestedStartPosition: Long
) {
    if (!shouldUseNeteaseFlacResumableRange(uri) || responseCode != 206) return
    val contentRange = responseHeaders.firstHeaderValue("Content-Range")
        ?: throw IOException(
            "Netease FLAC 206 response is missing Content-Range: " +
                "host=${uri.host}, position=$requestedStartPosition"
        )
    val match = NETEASE_CONTENT_RANGE_PATTERN.matchEntire(contentRange.trim())
        ?: throw IOException(
            "Netease FLAC returned malformed Content-Range: " +
                "host=${uri.host}, position=$requestedStartPosition"
        )
    val responseStart = match.groupValues[1].toLongOrNull()
    val responseEnd = match.groupValues[2].toLongOrNull()
    if (responseStart == null || responseEnd == null || responseEnd < responseStart) {
        throw IOException(
            "Netease FLAC returned invalid Content-Range: " +
                "host=${uri.host}, position=$requestedStartPosition"
        )
    }
    if (responseStart != requestedStartPosition) {
        throw IOException(
            "Netease FLAC Content-Range offset mismatch: " +
                "host=${uri.host}, requested=$requestedStartPosition, " +
                "received=$responseStart"
        )
    }
    val total = contentRange.substringAfter('/').trim().toLongOrNull()
    if (total != null && responseEnd >= total) {
        throw IOException(
            "Netease FLAC Content-Range exceeds total length: " +
                "host=${uri.host}, position=$requestedStartPosition, total=$total"
        )
    }
}

private fun Map<String, List<String>>.firstHeaderValue(name: String): String? {
    return entries.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }
}
