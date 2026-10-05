package moe.ouom.neriplayer.platform.youtube.api.client

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAccountProfile
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicBootstrapConfig
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.youtube.api.auth.hasEffectiveAuth
import moe.ouom.neriplayer.platform.youtube.api.auth.normalized
import moe.ouom.neriplayer.platform.youtube.api.parser.YouTubeMusicParser
import moe.ouom.neriplayer.platform.youtube.api.parser.parseYouTubeAccountProfile
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeMusicLocaleResolver
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_TEXT_RESPONSE_MAX_BYTES
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeInnertubeRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubePageRequestHeaders
import moe.ouom.neriplayer.platform.youtube.api.transport.effectiveCookieHeader
import moe.ouom.neriplayer.platform.youtube.api.transport.readTextWithLimit
import moe.ouom.neriplayer.platform.youtube.api.transport.resolveBootstrapUserAgent
import moe.ouom.neriplayer.platform.youtube.api.transport.resolveXGoogAuthUser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TimeZone

class YouTubeAccountProfileClient(private val okHttpClient: OkHttpClient) {
    suspend fun getAccountProfile(auth: YouTubeAuthBundle): YouTubeAccountProfile? {
        val snapshot = auth.normalized()
        if (!snapshot.hasEffectiveAuth()) return null
        val locale = YouTubeMusicLocaleResolver.preferred()
        val userAgent = snapshot.resolveBootstrapUserAgent()
        val html = executeText(
            Request.Builder()
                .url("$YOUTUBE_MUSIC_ORIGIN/")
                .apply {
                    snapshot.buildYouTubePageRequestHeaders(
                        original = mapOf("Accept-Language" to locale.acceptLanguage),
                        userAgent = userAgent,
                        includeAuthUser = true
                    ).forEach { (name, value) -> header(name, value) }
                }
                .build()
        )
        currentCoroutineContext().ensureActive()
        val bootstrap = YouTubeMusicParser.parseBootstrapConfig(html, snapshot.effectiveCookieHeader(), userAgent)
        if (!bootstrap.loggedIn) return null
        val root = JSONObject(executeText(accountMenuRequest(snapshot, bootstrap, locale)))
        currentCoroutineContext().ensureActive()
        return parseYouTubeAccountProfile(root)
    }

    private suspend fun executeText(request: Request): String =
        okHttpClient.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) {
                throw IOException("YouTube account profile request failed: HTTP ${response.code}")
            }
            response.body.readTextWithLimit(YOUTUBE_TEXT_RESPONSE_MAX_BYTES)
        }
}

private fun accountMenuRequest(
    auth: YouTubeAuthBundle,
    bootstrap: YouTubeMusicBootstrapConfig,
    locale: YouTubeMusicRequestLocale
): Request {
    val sessionIndex = auth.resolveXGoogAuthUser(bootstrap.sessionIndex)
    val headers = auth.buildYouTubeInnertubeRequestHeaders(
        original = linkedMapOf(
            "User-Agent" to bootstrap.webUserAgent,
            "Accept-Language" to locale.acceptLanguage,
            "Content-Type" to "application/json",
            "Origin" to YOUTUBE_MUSIC_ORIGIN,
            "X-Origin" to YOUTUBE_MUSIC_ORIGIN,
            "Referer" to "$YOUTUBE_MUSIC_ORIGIN/",
            "X-Goog-AuthUser" to sessionIndex,
            "X-Goog-Visitor-Id" to bootstrap.visitorData,
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to bootstrap.webRemixClientVersion,
            "X-YouTube-Bootstrap-Logged-In" to "true"
        ),
        authorizationOrigin = YOUTUBE_MUSIC_ORIGIN,
        userSessionId = bootstrap.userSessionId
    )
    val context = JSONObject()
        .put("client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", bootstrap.webRemixClientVersion)
            .put("hl", locale.hl)
            .put("gl", locale.gl)
            .put("visitorData", bootstrap.visitorData)
            .put("platform", "DESKTOP")
            .put("userAgent", bootstrap.webUserAgent)
            .put("originalUrl", "$YOUTUBE_MUSIC_ORIGIN/")
            .put("utcOffsetMinutes", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / (60 * 1000)))
        .put("request", JSONObject()
            .put("useSsl", true)
            .put("sessionIndex", sessionIndex)
            .put("internalExperimentFlags", JSONArray())
            .put("consistencyTokenJars", JSONArray()))
        .put("user", JSONObject().put("lockedSafetyMode", false))
    return Request.Builder()
        .url("$YOUTUBE_MUSIC_ORIGIN/youtubei/v1/account/account_menu?prettyPrint=false&key=${bootstrap.apiKey}")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .post(JSONObject().put("context", context).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
}
