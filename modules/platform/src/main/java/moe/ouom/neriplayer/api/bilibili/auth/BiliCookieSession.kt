package moe.ouom.neriplayer.api.bilibili.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.bilibili.http.BILI_FINGERPRINT_URL
import moe.ouom.neriplayer.api.bilibili.http.BILI_FINGERPRINT_USER_AGENT
import moe.ouom.neriplayer.api.bilibili.http.executeBiliOrThrow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class BiliCookieSession(
    private val cookieRepo: BiliCookieSource,
    private val http: OkHttpClient
) {
    private val anonymousCookieMutex = Mutex()

    @Volatile
    private var cachedAnonymousCookies: Map<String, String>? = null

    @Volatile
    private var anonymousCookiesCachedAt = 0L

    suspend fun effectiveCookies(): Map<String, String> {
        val stored = cookieRepo.getCookiesOnce()
        return if (stored.isNotEmpty()) stored else ensureAnonymousCookies()
    }

    private suspend fun ensureAnonymousCookies(): Map<String, String> {
        freshAnonymousCookies(System.currentTimeMillis())?.let { return it }
        return anonymousCookieMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            freshAnonymousCookies(lockedNow)?.let { return@withLock it }
            fetchAnonymousCookies().also { cookies ->
                cachedAnonymousCookies = cookies
                anonymousCookiesCachedAt = lockedNow
            }
        }
    }

    private fun freshAnonymousCookies(now: Long): Map<String, String>? =
        cachedAnonymousCookies?.takeIf { now - anonymousCookiesCachedAt < ANONYMOUS_COOKIE_CACHE_MS }

    private suspend fun fetchAnonymousCookies(): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BILI_FINGERPRINT_URL)
            .header("User-Agent", BILI_FINGERPRINT_USER_AGENT)
            .get()
            .build()
        val data = http.newCall(request).executeBiliOrThrow().use { response ->
            JSONObject(response.body.string()).optJSONObject("data") ?: JSONObject()
        }
        listOf(
            "buvid3" to data.optString("b_3", data.optString("buvid3", "")),
            "buvid4" to data.optString("b_4", data.optString("buvid4", "")),
            "buvid_fp" to data.optString("buvid_fp", ""),
            "buvid_fp_plain" to data.optString("buvid_fp_plain", ""),
            "b_lsid" to data.optString("b_lsid", "")
        ).filter { (_, value) -> value.isNotBlank() }.toMap()
    }

    private companion object {
        const val ANONYMOUS_COOKIE_CACHE_MS = 60 * 60 * 1000L
    }
}
