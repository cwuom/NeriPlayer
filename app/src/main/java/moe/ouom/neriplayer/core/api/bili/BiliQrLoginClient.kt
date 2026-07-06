package moe.ouom.neriplayer.core.api.bili

import moe.ouom.neriplayer.util.DynamicProxySelector
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val BILI_QR_LOG_TAG = "NERI-BiliQrClient"
private const val BILI_QR_GENERATE_URL =
    "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
private const val BILI_QR_POLL_URL =
    "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
private const val BILI_REFERER = "https://passport.bilibili.com/login"
private const val BILI_WEB_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
private const val BILI_QR_NETWORK_RETRY_COUNT = 3
private const val BILI_QR_NETWORK_RETRY_DELAY_MS = 100L

data class BiliQrLoginSession(
    val key: String,
    val qrContent: String
)

data class BiliQrLoginCheckResult(
    val code: Int,
    val message: String,
    val cookies: Map<String, String> = emptyMap()
) {
    val isConfirmed: Boolean
        get() = code == 0
}

class BiliQrLoginClient {
    private val cookieStore: LinkedHashMap<String, String> = linkedMapOf()
    private val cookieLock = Any()
    private val http = OkHttpClient.Builder()
        .proxySelector(DynamicProxySelector)
        .build()

    fun reset() {
        synchronized(cookieLock) {
            cookieStore.clear()
        }
        NPLogger.d(BILI_QR_LOG_TAG, "Reset QR login cookie store")
    }

    @Throws(IOException::class)
    fun createSession(): BiliQrLoginSession {
        NPLogger.d(BILI_QR_LOG_TAG, "Create QR session start")
        val json = executeJson(
            BILI_QR_GENERATE_URL.toHttpUrl()
        )
        val code = json.optInt("code", -1)
        val data = json.optJSONObject("data") ?: JSONObject()
        val key = data.optString("qrcode_key").trim()
        val url = data.optString("url").trim()
        if (code != 0 || key.isBlank() || url.isBlank()) {
            throw IOException(json.readMessage().ifBlank { "Failed to create Bilibili QR session, code=$code" })
        }
        NPLogger.d(BILI_QR_LOG_TAG, "Create QR session success key=${key.redactedKey()}")
        return BiliQrLoginSession(key = key, qrContent = url)
    }

    @Throws(IOException::class)
    fun checkLogin(session: BiliQrLoginSession): BiliQrLoginCheckResult {
        val url = BILI_QR_POLL_URL.toHttpUrl()
            .newBuilder()
            .addQueryParameter("qrcode_key", session.key)
            .build()
        NPLogger.d(BILI_QR_LOG_TAG, "Poll QR status key=${session.key.redactedKey()}")
        val json = executeJson(url)
        val rootCode = json.optInt("code", -1)
        if (rootCode != 0) {
            throw IOException(json.readMessage().ifBlank { "Bilibili QR poll failed, code=$rootCode" })
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val code = data.optInt("code", rootCode)
        val message = data.optString("message").ifBlank { json.readMessage() }
        val cookies = if (code == 0) currentCookies() else emptyMap()
        NPLogger.d(
            BILI_QR_LOG_TAG,
            "Poll QR response code=$code message=$message cookieKeys=${cookies.keys}"
        )
        return BiliQrLoginCheckResult(
            code = code,
            message = message,
            cookies = cookies
        )
    }

    fun currentCookies(): Map<String, String> {
        return synchronized(cookieLock) {
            LinkedHashMap(cookieStore)
        }
    }

    private fun executeJson(url: HttpUrl): JSONObject {
        var lastError: IOException? = null
        repeat(BILI_QR_NETWORK_RETRY_COUNT) { attemptIndex ->
            try {
                return executeJsonOnce(url)
            } catch (error: IOException) {
                lastError = error
                if (!error.isRetryableNetworkError() || attemptIndex == BILI_QR_NETWORK_RETRY_COUNT - 1) {
                    throw error
                }
                NPLogger.w(
                    BILI_QR_LOG_TAG,
                    "Retry QR HTTP GET url=${url.encodedPath} attempt=${attemptIndex + 1} " +
                        "reason=${error.message.orEmpty().compactForLog()}"
                )
                Thread.sleep(BILI_QR_NETWORK_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("Bilibili QR request failed")
    }

    private fun executeJsonOnce(url: HttpUrl): JSONObject {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Referer", BILI_REFERER)
            .header("User-Agent", BILI_WEB_UA)
            .get()
        currentCookieHeader().takeIf { it.isNotBlank() }?.let { cookieHeader ->
            requestBuilder.header("Cookie", cookieHeader)
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            storeSetCookieHeaders(response.headers("Set-Cookie"))
            val text = response.body?.string().orEmpty()
            NPLogger.d(
                BILI_QR_LOG_TAG,
                "HTTP GET done url=${url.encodedPath} status=${response.code} " +
                    "setCookieCount=${response.headers("Set-Cookie").size} " +
                    "cookieKeys=${currentCookies().keys} bodyPreview=${text.compactForLog()}"
            )
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $text")
            }
            if (text.isBlank()) {
                throw IOException("Empty Bilibili QR login response")
            }
            return JSONObject(text)
        }
    }

    private fun IOException.isRetryableNetworkError(): Boolean {
        if (this is UnknownHostException || this is ConnectException || this is SocketTimeoutException) {
            return true
        }
        val text = message.orEmpty().lowercase()
        return text.contains("dns") ||
            text.contains("resolve") ||
            text.contains("hostname") ||
            text.contains("no address associated with hostname")
    }

    private fun currentCookieHeader(): String {
        return synchronized(cookieLock) {
            cookieStore.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        }
    }

    private fun storeSetCookieHeaders(headers: List<String>) {
        if (headers.isEmpty()) {
            return
        }
        synchronized(cookieLock) {
            headers.forEach { header ->
                val update = parseSetCookieHeader(header) ?: return@forEach
                if (update.removed) {
                    cookieStore.remove(update.name)
                } else {
                    cookieStore[update.name] = update.value
                }
            }
        }
    }

    private fun parseSetCookieHeader(header: String): CookieUpdate? {
        val parts = header.split(';').map { it.trim() }
        val nameValue = parts.firstOrNull().orEmpty()
        val separatorIndex = nameValue.indexOf('=')
        if (separatorIndex <= 0) {
            return null
        }
        val name = nameValue.substring(0, separatorIndex).trim()
        val value = nameValue.substring(separatorIndex + 1).trim()
        if (name.isBlank()) {
            return null
        }
        val removed = value.isBlank() || parts.any { part ->
            part.equals("Max-Age=0", ignoreCase = true) ||
                part.startsWith("Expires=Thu, 01 Jan 1970", ignoreCase = true)
        }
        return CookieUpdate(name = name, value = value, removed = removed)
    }

    private fun JSONObject.readMessage(): String {
        return optString("message").ifBlank { optString("msg") }
    }

    private fun String.compactForLog(maxLength: Int = 360): String {
        val compact = replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        if (compact.length <= maxLength) {
            return compact
        }
        return "${compact.take(maxLength)}..."
    }

    private fun String.redactedKey(): String {
        if (length <= 8) {
            return "***"
        }
        return "${take(4)}...${takeLast(4)}"
    }

    private data class CookieUpdate(
        val name: String,
        val value: String,
        val removed: Boolean
    )
}
