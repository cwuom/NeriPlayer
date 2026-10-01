package moe.ouom.neriplayer.platform.bilibili.api.http

import java.io.IOException
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

internal const val BILI_FINGERPRINT_URL = "https://api.bilibili.com/x/frontend/finger/spi"
internal const val BILI_WEB_REFERER = "https://www.bilibili.com"
internal const val BILI_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
internal const val BILI_FINGERPRINT_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 " +
        "Mobile/15E148 Safari/604.1 Edg/114.0.0.0"

internal fun Map<String, String>.toBiliCookieHeader(): String? =
    entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }

internal fun Request.Builder.biliCookie(value: String?): Request.Builder =
    if (value.isNullOrBlank()) this else header("Cookie", value)

@Throws(IOException::class)
internal fun Call.executeBiliOrThrow(): Response {
    val response = execute()
    if (!response.isSuccessful) {
        val code = response.code
        val body = response.body.string()
        response.close()
        throw IOException("HTTP $code: $body")
    }
    return response
}
