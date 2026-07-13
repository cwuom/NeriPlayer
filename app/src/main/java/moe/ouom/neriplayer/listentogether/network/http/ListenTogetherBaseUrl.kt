package moe.ouom.neriplayer.listentogether.network.http

import java.net.URI
import java.util.Locale

internal fun String.normalizeBaseUrl(): String {
    return normalizedHttpBaseUrlOrNull()
        ?: throw IllegalArgumentException("ListenTogether baseUrl must use http or https")
}

fun String.normalizedHttpBaseUrlOrNull(): String? {
    val candidate = trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val authority = parsed.rawAuthority?.takeIf { it.isNotBlank() } ?: return null
    if (parsed.rawQuery != null || parsed.rawFragment != null) return null
    val normalizedPath = parsed.normalize()
        .rawPath
        ?.trimEnd('/')
        .orEmpty()
        .takeIf { it.isNotBlank() && it != "/" }
        .orEmpty()
    return buildString {
        append(scheme)
        append("://")
        append(authority)
        if (normalizedPath.isNotEmpty()) {
            if (!normalizedPath.startsWith('/')) {
                append('/')
            }
            append(normalizedPath)
        }
    }
}
