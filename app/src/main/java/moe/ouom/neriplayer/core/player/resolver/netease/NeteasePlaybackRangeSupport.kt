package moe.ouom.neriplayer.core.player.resolver.netease

import android.net.Uri
import androidx.media3.common.MimeTypes
import java.util.Locale

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
    if (contentType == MimeTypes.AUDIO_FLAC) return responseHeaders

    return buildMap {
        responseHeaders.forEach { (name, values) ->
            if (!name.equals("Content-Type", ignoreCase = true)) {
                put(name, values)
            }
        }
        put("Content-Type", listOf(MimeTypes.AUDIO_FLAC))
    }
}

private fun Map<String, List<String>>.firstHeaderValue(name: String): String? {
    return entries.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }
}
