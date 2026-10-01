package moe.ouom.neriplayer.api.ltw.ws

import moe.ouom.neriplayer.api.ltw.http.normalizeBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildListenTogetherWsUrl(baseUrl: String, roomId: String, token: String): String {
    val normalizedBase = baseUrl.normalizeBaseUrl()
    val url = normalizedBase.toHttpUrl().newBuilder()
        .encodedPath("/api/rooms/$roomId/ws")
        .setQueryParameter("token", token)
        .build()
    val httpUrl = url.toString()
    return when {
        url.isHttps -> httpUrl.replaceFirst("https://", "wss://")
        else -> httpUrl.replaceFirst("http://", "ws://")
    }
}

fun String?.redactListenTogetherWsUrlForLog(): String? {
    val raw = this ?: return null
    val parsed = raw.toHttpUrlOrNull() ?: return raw.substringBefore('?') + "?token=<redacted>"
    val redacted = parsed.newBuilder()
        .setQueryParameter("token", "<redacted>")
        .build()
    return redacted.toString()
}
