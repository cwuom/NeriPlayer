package moe.ouom.neriplayer.platform.bilibili.api.history

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliPlayHistoryTarget
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSource
import moe.ouom.neriplayer.platform.bilibili.api.http.BILI_WEB_REFERER
import moe.ouom.neriplayer.platform.bilibili.api.http.BILI_WEB_USER_AGENT
import moe.ouom.neriplayer.platform.bilibili.api.http.biliCookie
import moe.ouom.neriplayer.platform.bilibili.api.http.toBiliCookieHeader
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val HEARTBEAT_URL = "https://api.bilibili.com/x/click-interface/web/heartbeat"
private const val HISTORY_SHADOW_URL = "https://api.bilibili.com/x/v2/history/shadow"
private const val HEARTBEAT_TYPE_UGC = "3"
private const val HISTORY_SHADOW_CACHE_MS = 10 * 60 * 1000L

/**
 * 观看历史上报，参数与 PiliPlus 的 heartBeat 保持一致（只带 bvid/aid、cid、type、played_time、csrf）。
 *
 * 账号在 B 站暂停了历史记录时，PiliPlus 会整体停掉心跳，这里同样尊重该设置。
 */
internal class BiliHistoryApi(
    private val cookieRepo: BiliCookieSource,
    private val http: OkHttpClient
) {
    private val shadowLock = Mutex()
    private var shadowSession: String? = null
    private var shadowPaused = false
    private var shadowCheckedAtMs = 0L

    suspend fun hasLogin(): Boolean = authenticatedCookies() != null

    suspend fun reportHeartbeat(target: BiliPlayHistoryTarget, playedSec: Long) {
        val authenticated = authenticatedCookies() ?: return
        if (isHistoryPaused(authenticated)) return
        val form = buildBiliHeartbeatForm(target, playedSec, authenticated.csrf)
        val request = authenticated.request(HEARTBEAT_URL).post(form).build()
        val code = executeJson(request).optInt("code", -1)
        if (code != 0) throw IOException("Bili heartbeat rejected: code=$code")
    }

    private suspend fun isHistoryPaused(authenticated: AuthenticatedCookies): Boolean = shadowLock.withLock {
        val now = System.currentTimeMillis()
        val session = authenticated.sessData
        if (session == shadowSession && now - shadowCheckedAtMs < HISTORY_SHADOW_CACHE_MS) {
            return@withLock shadowPaused
        }
        val root = executeJson(authenticated.request(HISTORY_SHADOW_URL).get().build())
        if (root.optInt("code", -1) != 0) throw IOException("Bili history shadow failed: code=${root.optInt("code", -1)}")
        shadowPaused = root.optBoolean("data", false)
        shadowSession = session
        shadowCheckedAtMs = now
        shadowPaused
    }

    private suspend fun executeJson(request: Request): JSONObject =
        http.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("Bili history HTTP ${response.code}")
            JSONObject(response.body.string())
        }

    private suspend fun authenticatedCookies(): AuthenticatedCookies? {
        val cookies = cookieRepo.getCookiesOnce()
        val sessData = cookies["SESSDATA"].orEmpty()
        val csrf = cookies["bili_jct"].orEmpty()
        return if (sessData.isBlank() || csrf.isBlank()) null else AuthenticatedCookies(cookies, sessData, csrf)
    }

    private class AuthenticatedCookies(
        val cookies: Map<String, String>,
        val sessData: String,
        val csrf: String
    ) {
        fun request(url: String): Request.Builder = Request.Builder()
            .url(url)
            .header("User-Agent", BILI_WEB_USER_AGENT)
            .header("Referer", BILI_WEB_REFERER)
            .biliCookie(cookies.toBiliCookieHeader())
    }
}

internal fun buildBiliHeartbeatForm(target: BiliPlayHistoryTarget, playedSec: Long, csrf: String): FormBody =
    FormBody.Builder().apply {
        val bvid = target.bvid?.takeIf { it.isNotBlank() }
        if (bvid != null) add("bvid", bvid) else add("aid", target.aid.toString())
        add("cid", target.cid.toString())
        add("type", HEARTBEAT_TYPE_UGC)
        add("played_time", playedSec.coerceAtLeast(-1L).toString())
        add("csrf", csrf)
    }.build()
