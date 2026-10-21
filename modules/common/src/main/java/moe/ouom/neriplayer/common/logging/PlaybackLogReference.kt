package moe.ouom.neriplayer.common.logging

import java.net.URI
import java.util.Locale

fun playbackLogReference(reference: String?): String {
    val value = reference?.trim().orEmpty()
    if (value.isEmpty()) return "none"
    val description = if (value.startsWith('/') && !value.startsWith("//")) value else describePlaybackUri(value)
    return buildString {
        description.take(768).forEach { append(if (it.isISOControl()) ' ' else it) }
    }
}

private fun describePlaybackUri(reference: String): String {
    val uri = runCatching { URI(reference) }.getOrNull() ?: return "invalid"
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return "invalid"
    val authority = uri.rawAuthority?.substringAfterLast('@').orEmpty()
    return when (scheme) {
        "content", "file", "android.resource" -> "$scheme://$authority${uri.path.orEmpty()}"
        else -> "$scheme://$authority/<redacted>"
    }
}
