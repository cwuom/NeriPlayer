package moe.ouom.neriplayer.api.ltw.http

import java.net.URI
import java.util.Locale

fun String.normalizeBaseUrl(): String {
    return normalizedHttpBaseUrlOrNull()
        ?: throw IllegalArgumentException("ListenTogether baseUrl must use http or https")
}

fun String.normalizedHttpBaseUrlOrNull(): String? {
    val parsed = parseBaseUriOrNull() ?: return null
    val scheme = parsed.httpSchemeOrNull() ?: return null
    if (!parsed.hasBaseAuthority()) return null
    return "$scheme://${parsed.rawAuthority}${parsed.normalizedBasePath()}"
}

private fun String.parseBaseUriOrNull(): URI? {
    val candidate = trim().trimEnd('/')
    if (candidate.isBlank()) return null
    return runCatching { URI(candidate) }.getOrNull()
}

private fun URI.httpSchemeOrNull(): String? {
    val value = scheme?.lowercase(Locale.ROOT) ?: return null
    return if (value == "http" || value == "https") value else null
}

private fun URI.hasBaseAuthority(): Boolean =
    !rawAuthority.isNullOrBlank() && rawQuery == null && rawFragment == null

// 已校验 authority，层级 URI 的路径只能为空或以斜杠开头，保留原始转义
private fun URI.normalizedBasePath(): String = normalize().rawPath.orEmpty().trimEnd('/')
