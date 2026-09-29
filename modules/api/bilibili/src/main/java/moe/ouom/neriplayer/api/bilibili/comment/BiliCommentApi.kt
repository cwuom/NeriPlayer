package moe.ouom.neriplayer.api.bilibili.comment

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.bilibili.auth.BiliCookieSession
import moe.ouom.neriplayer.api.bilibili.auth.BiliCookieSource
import moe.ouom.neriplayer.api.bilibili.http.BILI_WEB_REFERER
import moe.ouom.neriplayer.api.bilibili.http.BILI_WEB_USER_AGENT
import moe.ouom.neriplayer.api.bilibili.http.biliCookie
import moe.ouom.neriplayer.api.bilibili.http.executeBiliOrThrow
import moe.ouom.neriplayer.api.bilibili.http.toBiliCookieHeader
import moe.ouom.neriplayer.core.api.nonReplayable
import moe.ouom.neriplayer.util.network.awaitResponse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject

internal class BiliCommentApi(
    private val cookieRepo: BiliCookieSource,
    private val cookieSession: BiliCookieSession,
    private val http: OkHttpClient
) {
    suspend fun getVideoComments(aid: Long, page: Int, pageSize: Int, sort: Int): JSONObject {
        require(aid > 0L) { "aid must be positive" }
        return getJson(
            REPLY_URL,
            mapOf(
                "type" to REPLY_TYPE_VIDEO,
                "oid" to aid.toString(),
                "pn" to page.coerceAtLeast(1).toString(),
                "ps" to pageSize.coerceIn(1, 49).toString(),
                "sort" to sort.toString()
            )
        )
    }

    suspend fun getVideoCommentReplies(aid: Long, rootId: String, page: Int, pageSize: Int): JSONObject {
        requirePositiveAid(aid)
        requirePositiveCommentId(rootId)
        require(page > 0)
        require(pageSize in 1..20)
        return getJson(
            "$REPLY_URL/reply",
            mapOf(
                "type" to REPLY_TYPE_VIDEO,
                "oid" to aid.toString(),
                "root" to rootId,
                "pn" to page.toString(),
                "ps" to pageSize.toString()
            )
        )
    }

    suspend fun hasCommentLogin(): Boolean =
        !cookieRepo.getCookiesOnce()["SESSDATA"].isNullOrBlank()

    suspend fun commentCacheSessionKey(): String? =
        cookieRepo.getCookiesOnce()["SESSDATA"]?.takeIf { it.isNotBlank() }?.encodeUtf8()?.sha256()?.hex()

    suspend fun sendVideoComment(
        aid: Long,
        content: String,
        rootId: String?,
        parentId: String?
    ): JSONObject {
        requirePositiveAid(aid)
        require(content.isNotBlank())
        requireReplyTarget(rootId, parentId)
        val authenticated = authenticatedCookies() ?: return loginRequired()
        val form = commentForm(aid, content, rootId, parentId, authenticated.csrf)
        val request = authenticated.request("$REPLY_URL/add")
            .post(form.nonReplayable())
            .build()
        // 网络结果不确定时保留草稿，避免自动重试发出重复评论
        val singleAttempt = http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()
        return executeCommentAction(singleAttempt, request)
    }

    suspend fun setVideoCommentLiked(aid: Long, commentId: String, liked: Boolean): JSONObject {
        requirePositiveAid(aid)
        requirePositiveCommentId(commentId)
        val authenticated = authenticatedCookies() ?: return loginRequired()
        val form = FormBody.Builder()
            .add("type", REPLY_TYPE_VIDEO)
            .add("oid", aid.toString())
            .add("rpid", commentId)
            .add("action", if (liked) "1" else "0")
            .add("csrf", authenticated.csrf)
            .build()
        return executeCommentAction(http, authenticated.request("$REPLY_URL/action").post(form).build())
    }

    private suspend fun getJson(url: String, params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = url.toHttpUrl().newBuilder()
            params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            val request = Request.Builder()
                .url(builder.build())
                .header("User-Agent", BILI_WEB_USER_AGENT)
                .header("Referer", BILI_WEB_REFERER)
                .biliCookie(cookieSession.effectiveCookies().toBiliCookieHeader())
                .get()
                .build()
            JSONObject(http.newCall(request).executeBiliOrThrow().use { it.body.string() })
        }

    private suspend fun authenticatedCookies(): AuthenticatedCookies? {
        val cookies = cookieRepo.getCookiesOnce()
        val csrf = cookies["bili_jct"].orEmpty()
        return if (cookies["SESSDATA"].isNullOrBlank() || csrf.isBlank()) null
        else AuthenticatedCookies(cookies, csrf)
    }

    private fun commentForm(
        aid: Long,
        content: String,
        rootId: String?,
        parentId: String?,
        csrf: String
    ): FormBody = FormBody.Builder()
        .add("type", REPLY_TYPE_VIDEO)
        .add("oid", aid.toString())
        .add("message", content)
        .add("root", rootId ?: "0")
        .add("parent", parentId ?: "0")
        .add("plat", "1")
        .add("statistics", "{\"appId\":100,\"platform\":5}")
        .add("gaia_source", "main_web")
        .add("csrf", csrf)
        .build()

    private fun requireReplyTarget(rootId: String?, parentId: String?) {
        if (rootId == null && parentId == null) return
        require(isPositiveCommentId(rootId) && isPositiveCommentId(parentId))
    }

    private fun requirePositiveAid(aid: Long) {
        require(aid > 0L)
    }

    private fun requirePositiveCommentId(value: String) {
        require(isPositiveCommentId(value))
    }

    private fun isPositiveCommentId(value: String?): Boolean =
        (value?.toLongOrNull() ?: 0L) > 0L

    private suspend fun executeCommentAction(client: OkHttpClient, request: Request): JSONObject =
        client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("Bili comment HTTP ${response.code}")
            JSONObject(response.body.string())
        }

    private fun loginRequired(): JSONObject = JSONObject().put("code", -101)

    private data class AuthenticatedCookies(
        val cookies: Map<String, String>,
        val csrf: String
    ) {
        fun request(url: String): Request.Builder = Request.Builder()
            .url(url)
            .header("User-Agent", BILI_WEB_USER_AGENT)
            .header("Referer", BILI_WEB_REFERER)
            .biliCookie(cookies.toBiliCookieHeader())
    }

    private companion object {
        const val REPLY_URL = "https://api.bilibili.com/x/v2/reply"
        const val REPLY_TYPE_VIDEO = "1"
    }
}
