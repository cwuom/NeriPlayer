package moe.ouom.neriplayer.core.api.netease

import android.content.Context
import moe.ouom.neriplayer.util.network.DynamicProxySelector
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.brotli.dec.BrotliInputStream
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

private const val NETEASE_QR_ORIGIN = "https://music.163.com/"
private const val NETEASE_QR_SCAN_HOST = "music.163.com"
private const val NETEASE_QR_UNIKEY_PATH = "/weapi/login/qrcode/unikey"
private const val NETEASE_QR_CHECK_PATH = "/weapi/login/qrcode/client/login"
private const val NETEASE_ACCOUNT_PATH = "/weapi/w/nuser/account/get"
private const val NETEASE_REFRESH_TOKEN_HEADER = "x-refresh-token"
private const val NETEASE_QR_LOG_TAG = "NERI-NeteaseQrClient"
private const val NETEASE_QR_MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val NETEASE_QR_DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"

data class NeteaseQrLoginSession(
    val key: String,
    val chainId: String,
    val ydDeviceToken: String,
    val seedCookieKeys: Set<String>,
    val qrContent: String
)

data class NeteaseQrLoginCheckResult(
    val code: Int,
    val message: String,
    val cookies: Map<String, String> = emptyMap()
) {
    val isConfirmed: Boolean
        get() = code == 803
}

private data class NeteaseQrHttpResult(
    val text: String,
    val refreshToken: String = ""
)

class NeteaseQrLoginClient(
    context: Context
) {
    private val ydDeviceTokenProvider = NeteaseYdDeviceTokenProvider(context)
    private val cookieStore: MutableMap<String, MutableList<Cookie>> = mutableMapOf()
    private val cookieLock = Any()
    private val http = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieLock) {
                    cookies.forEach { fresh ->
                        removeStoredCookie(fresh)
                        if (fresh.isUsableCookie()) {
                            cookieStore.getOrPut(fresh.domain) { mutableListOf() }.add(fresh)
                        }
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return synchronized(cookieLock) {
                    val now = System.currentTimeMillis()
                    cookieStore.values.forEach { cookies ->
                        cookies.removeAll { it.expiresAt <= now }
                    }
                    cookieStore.values
                        .asSequence()
                        .flatMap { it.asSequence() }
                        .filter { it.expiresAt > now && it.matches(url) }
                        .toList()
                }
            }
        })
        .proxySelector(DynamicProxySelector)
        .build()

    fun reset() {
        synchronized(cookieLock) {
            cookieStore.clear()
        }
        NPLogger.d(NETEASE_QR_LOG_TAG, "Reset QR login cookie store")
    }

    @Throws(IOException::class)
    suspend fun createSession(): NeteaseQrLoginSession {
        NPLogger.d(NETEASE_QR_LOG_TAG, "Create QR session start")
        val json = executeWeApiJsonPost(
            path = NETEASE_QR_UNIKEY_PATH,
            params = mapOf(
                "type" to 1,
                "noCheckToken" to true
            )
        )
        val code = json.optInt("code", -1)
        val key = json.optString("unikey").trim()
        if (code != 200 || key.isBlank()) {
            throw IOException(json.readMessage().ifBlank { "Failed to create QR login session, code=$code" })
        }
        val deviceSnapshot = runCatching { ydDeviceTokenProvider.getSnapshot() }
            .onFailure { error ->
                NPLogger.w(NETEASE_QR_LOG_TAG, "Failed to prepare ydDevice snapshot", error)
            }
            .getOrDefault(NeteaseYdDeviceSnapshot())
        seedCookieStoreFromSnapshot(deviceSnapshot.cookies)
        val chainId = createLoginChainId(deviceSnapshot.sDeviceId)
        val ydDeviceToken = deviceSnapshot.token
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Create QR session success key=${key.redactedKey()} chainId=$chainId " +
                "ydDeviceTokenLength=${ydDeviceToken.length} seedCookieKeys=${deviceSnapshot.cookies.keys}"
        )
        return NeteaseQrLoginSession(
            key = key,
            chainId = chainId,
            ydDeviceToken = ydDeviceToken,
            seedCookieKeys = deviceSnapshot.cookies.keys,
            qrContent = buildScanLoginUrl(key, chainId)
        )
    }

    @Throws(IOException::class)
    fun checkLogin(session: NeteaseQrLoginSession): NeteaseQrLoginCheckResult {
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Poll QR status key=${session.key.redactedKey()} chainId=${session.chainId} " +
                "ydDeviceTokenLength=${session.ydDeviceToken.length}"
        )
        val result = executeWeApiPost(
            path = NETEASE_QR_CHECK_PATH,
            params = mapOf(
                "type" to 1,
                "noCheckToken" to true,
                "key" to session.key,
                "ydDeviceToken" to session.ydDeviceToken
            ),
            headers = mapOf(
                "x-loginmethod" to "QrCode",
                "x-login-chain-id" to session.chainId
            )
        )
        val json = JSONObject(result.text)
        val code = json.optInt("code", -1)
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Poll QR response code=$code message=${json.readMessage()} " +
                "refreshTokenLength=${result.refreshToken.length} cookieKeys=${currentCookies().keys}"
        )
        if (code == 803) {
            val verifiedCookies = verifyConfirmedLogin(result.refreshToken)
            NPLogger.d(
                NETEASE_QR_LOG_TAG,
                "Poll QR confirmed verifiedCookieKeys=${verifiedCookies.keys}"
            )
            return NeteaseQrLoginCheckResult(
                code = code,
                message = json.readMessage(),
                cookies = verifiedCookies.ifEmpty { currentCookies() }
            )
        }
        return NeteaseQrLoginCheckResult(
            code = code,
            message = json.readMessage()
        )
    }

    fun currentCookies(): Map<String, String> {
        return synchronized(cookieLock) {
            val result = linkedMapOf<String, String>()
            cookieStore.values.forEach { cookies ->
                cookies.forEach { cookie -> result[cookie.name] = cookie.value }
            }
            result
        }
    }

    private fun executeWeApiJsonPost(
        path: String,
        params: Map<String, Any>,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        val raw = executeWeApiPost(path, params, headers).text
        if (raw.isBlank()) {
            throw IOException("Empty NetEase QR login response")
        }
        return JSONObject(raw)
    }

    private fun executeWeApiPost(
        path: String,
        params: Map<String, Any>,
        headers: Map<String, String> = emptyMap()
    ): NeteaseQrHttpResult {
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "HTTP POST path=$path params=${params.keys} headers=${headers.keys}"
        )
        val url = NETEASE_QR_ORIGIN.toHttpUrl()
            .newBuilder()
            .encodedPath(path)
            .applyCsrfIfNeeded(path, params)
            .build()

        val encryptedParams = NeteaseCrypto.weApiEncrypt(params)
        val formBodyBuilder = FormBody.Builder(StandardCharsets.UTF_8)
        encryptedParams.forEach { (key, value) -> formBodyBuilder.add(key, value) }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "gzip, br")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Referer", NETEASE_QR_ORIGIN)
            .header("Origin", NETEASE_QR_ORIGIN.removeSuffix("/"))
            .header("User-Agent", NETEASE_QR_DESKTOP_UA)
            .header("x-os", "web")
            .header("x-channelsource", "undefined")
            .header("nm-gcore-status", "1")
            .post(formBodyBuilder.build())
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        http.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.readBodyText()
            NPLogger.d(
                NETEASE_QR_LOG_TAG,
                "HTTP POST done path=$path status=${response.code} " +
                    "refreshTokenLength=${response.header(NETEASE_REFRESH_TOKEN_HEADER).orEmpty().length} " +
                    "bodyPreview=${text.compactForLog()}"
            )
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $text")
            }
            return NeteaseQrHttpResult(
                text = text,
                refreshToken = response.header(NETEASE_REFRESH_TOKEN_HEADER).orEmpty()
            )
        }
    }

    private fun verifyAccountIfPossible(): Map<String, String> {
        val snapshot = currentCookies()
        val csrf = snapshot["__csrf"].orEmpty()
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Verify account start csrfPresent=${csrf.isNotBlank()} cookieKeys=${snapshot.keys}"
        )
        val params = if (csrf.isBlank()) {
            emptyMap()
        } else {
            mapOf("csrf_token" to csrf)
        }
        return runCatching {
            val json = executeWeApiJsonPost(NETEASE_ACCOUNT_PATH, params)
            val hasAccount = json.optJSONObject("account") != null || json.optJSONObject("profile") != null
            NPLogger.d(
                NETEASE_QR_LOG_TAG,
                "Verify account result code=${json.optInt("code", -1)} hasAccount=$hasAccount " +
                    "cookieKeys=${currentCookies().keys}"
            )
            if (json.optInt("code", -1) == 200 && hasAccount) {
                currentCookies()
            } else {
                emptyMap()
            }
        }.onFailure { error ->
            NPLogger.w(NETEASE_QR_LOG_TAG, "Verify account failed", error)
        }.getOrDefault(emptyMap())
    }

    private fun verifyConfirmedLogin(refreshToken: String): Map<String, String> {
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Verify confirmed login start refreshTokenLength=${refreshToken.length} " +
                "cookieKeys=${currentCookies().keys}"
        )
        val directCookies = verifyAccountIfPossible()
        if (directCookies.isNotEmpty()) {
            NPLogger.d(NETEASE_QR_LOG_TAG, "Verify confirmed login succeeded with direct cookies")
            return directCookies
        }

        if (refreshToken.isBlank() || currentCookies()["MUSIC_U"].isNullOrBlank().not()) {
            NPLogger.d(
                NETEASE_QR_LOG_TAG,
                "Verify confirmed login stop refreshFallbackAllowed=${refreshToken.isNotBlank()} " +
                    "hasMusicU=${currentCookies()["MUSIC_U"].isNullOrBlank().not()}"
            )
            return emptyMap()
        }

        setMusicUCookie(refreshToken)
        val refreshedCookies = verifyAccountIfPossible()
        if (refreshedCookies.isNotEmpty()) {
            NPLogger.d(NETEASE_QR_LOG_TAG, "Verify confirmed login succeeded after refresh token fallback")
            return refreshedCookies
        }
        removeMusicUCookie()
        NPLogger.d(NETEASE_QR_LOG_TAG, "Verify confirmed login failed after refresh token fallback")
        return emptyMap()
    }

    private fun HttpUrl.Builder.applyCsrfIfNeeded(
        path: String,
        params: Map<String, Any>
    ): HttpUrl.Builder {
        if (path != NETEASE_ACCOUNT_PATH) {
            return this
        }
        val csrf = params["csrf_token"]?.toString().orEmpty()
        if (csrf.isNotBlank()) {
            addQueryParameter("csrf_token", csrf)
        }
        return this
    }

    private fun okhttp3.Response.readBodyText(): String {
        val body = body ?: throw IOException("Empty response body")
        val encoding = header("Content-Encoding")?.lowercase(Locale.getDefault())
        val bytes = when (encoding) {
            "br" -> BrotliInputStream(body.byteStream()).use { it.readBytesLimited(NETEASE_QR_MAX_RESPONSE_BYTES) }
            "gzip" -> GZIPInputStream(body.byteStream()).use { it.readBytesLimited(NETEASE_QR_MAX_RESPONSE_BYTES) }
            else -> body.byteStream().use { it.readBytesLimited(NETEASE_QR_MAX_RESPONSE_BYTES) }
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun setMusicUCookie(refreshToken: String) {
        synchronized(cookieLock) {
            val hostCookies = cookieStore.getOrPut(NETEASE_QR_SCAN_HOST) { mutableListOf() }
            val cookie = Cookie.Builder()
                .name("MUSIC_U")
                .value(refreshToken)
                .domain(NETEASE_QR_SCAN_HOST)
                .path("/")
                .build()
            hostCookies.removeAll { it.sameCookieIdentity(cookie) }
            hostCookies.add(cookie)
        }
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Applied MUSIC_U fallback cookie refreshTokenLength=${refreshToken.length}"
        )
    }

    private fun seedCookieStoreFromSnapshot(cookies: Map<String, String>) {
        if (cookies.isEmpty()) {
            NPLogger.d(NETEASE_QR_LOG_TAG, "No WebView cookies available for QR seed")
            return
        }
        synchronized(cookieLock) {
            val hostCookies = cookieStore.getOrPut(NETEASE_QR_SCAN_HOST) { mutableListOf() }
            cookies.forEach { (name, value) ->
                if (name.isBlank() || value.isBlank()) {
                    return@forEach
                }
                val cookie = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(NETEASE_QR_SCAN_HOST)
                    .path("/")
                    .build()
                hostCookies.removeAll { it.sameCookieIdentity(cookie) }
                hostCookies.add(cookie)
            }
        }
        NPLogger.d(NETEASE_QR_LOG_TAG, "Seeded QR cookie store keys=${cookies.keys}")
    }

    private fun removeMusicUCookie() {
        synchronized(cookieLock) {
            cookieStore[NETEASE_QR_SCAN_HOST]?.removeAll { it.name == "MUSIC_U" }
        }
        NPLogger.d(NETEASE_QR_LOG_TAG, "Removed MUSIC_U fallback cookie")
    }

    private fun removeStoredCookie(cookie: Cookie) {
        cookieStore.values.forEach { cookies ->
            cookies.removeAll { it.sameCookieIdentity(cookie) }
        }
    }

    private fun Cookie.isUsableCookie(): Boolean {
        return value.isNotBlank() && expiresAt > System.currentTimeMillis()
    }

    private fun Cookie.sameCookieIdentity(other: Cookie): Boolean {
        return name == other.name && domain == other.domain && path == other.path
    }

    private fun createLoginChainId(sDeviceId: String): String {
        val randomPart = (Math.random() * 1_000_000).toInt()
        val deviceId = sDeviceId.ifBlank { "unknown-$randomPart" }
        return "v1_${deviceId}_web_login_${System.currentTimeMillis()}"
    }

    private fun buildScanLoginUrl(key: String, chainId: String): String {
        val url = NETEASE_QR_ORIGIN.toHttpUrl()
            .newBuilder()
            .addPathSegments("st/platform/scanlogin")
            .addQueryParameter("codekey", key)
            .addQueryParameter("chainId", chainId)
            .addQueryParameter("hdw_device", "web")
            .addQueryParameter("hdw_appid", "web")
            .addQueryParameter("hitExp", "1")
            .build()
            .toString()
        NPLogger.d(
            NETEASE_QR_LOG_TAG,
            "Built scanlogin url key=${key.redactedKey()} chainId=$chainId"
        )
        return url
    }

    private fun JSONObject.readMessage(): String {
        return optString("message").ifBlank { optString("msg") }
    }

    private fun String.compactForLog(maxLength: Int = 200): String {
        val compact = replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        if (compact.length <= maxLength) {
            return compact
        }
        return compact.take(maxLength) + "..."
    }

    private fun String.redactedKey(): String {
        if (length <= 8) {
            return this
        }
        return take(4) + "..." + takeLast(4)
    }
}
