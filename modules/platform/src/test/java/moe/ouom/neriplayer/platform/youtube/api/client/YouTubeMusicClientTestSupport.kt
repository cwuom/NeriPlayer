package moe.ouom.neriplayer.platform.youtube.api.client

import java.security.MessageDigest
import java.util.Collections
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthAutoRefreshResult
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeAuthProvider
import moe.ouom.neriplayer.platform.youtube.api.auth.YouTubeAuthRefresher
import moe.ouom.neriplayer.platform.youtube.api.auth.evaluateYouTubeAuthHealth
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

internal val GUEST_AUTH = YouTubeAuthBundle()
internal val PREFERENCE_ONLY_AUTH = YouTubeAuthBundle(cookieHeader = "PREF=f6=40000000")
internal val LOGIN_AUTH = YouTubeAuthBundle(
    cookieHeader = "SAPISID=test-sapisid; SID=test-sid",
    xGoogAuthUser = "1"
)

internal class FakeYouTubeAuth(@Volatile var auth: YouTubeAuthBundle) : YouTubeAuthProvider {
    override fun getAuthOnce(): YouTubeAuthBundle = auth

    override fun getAuthHealthOnce() = evaluateYouTubeAuthHealth(auth)
}

internal class RecordingYouTubeRefresher(
    private val onRefresh: (reason: String) -> Boolean = { false }
) : YouTubeAuthRefresher {
    val calls: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())

    override suspend fun refreshIfNeeded(reason: String, force: Boolean): YouTubeAuthAutoRefreshResult {
        calls += reason to force
        val refreshed = onRefresh(reason)
        return YouTubeAuthAutoRefreshResult(
            attempted = true,
            refreshed = refreshed,
            authChanged = refreshed,
            reason = reason
        )
    }
}

internal class YouTubeMusicCall(val request: Request, val payload: JSONObject?) {
    val isBootstrap: Boolean get() = request.url.encodedPath == "/"
    val endpoint: String get() = request.url.encodedPath.removePrefix("/youtubei/v1/")
    val hl: String get() = clientContext().optString("hl")
    val gl: String get() = clientContext().optString("gl")

    private fun clientContext(): JSONObject =
        payload?.optJSONObject("context")?.optJSONObject("client") ?: JSONObject()
}

internal class YouTubeMusicReply(val body: String, val code: Int = 200)

internal class YouTubeMusicTestHttp(private val route: (YouTubeMusicCall) -> YouTubeMusicReply) {
    val calls: MutableList<YouTubeMusicCall> = Collections.synchronizedList(mutableListOf())

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val payload = request.body?.let { body ->
                JSONObject(Buffer().also(body::writeTo).readUtf8())
            }
            val call = YouTubeMusicCall(request, payload)
            calls += call
            val reply = route(call)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code)
                .message("test")
                .body(reply.body.toResponseBody())
                .build()
        }
        .build()

    fun bootstrapCalls(): List<YouTubeMusicCall> = calls.filter { it.isBootstrap }

    fun innertubeCalls(endpoint: String): List<YouTubeMusicCall> =
        calls.filter { !it.isBootstrap && it.endpoint == endpoint }
}

internal fun bootstrapPage(loggedIn: Boolean = false, userSessionId: String = ""): YouTubeMusicReply =
    YouTubeMusicReply(
        """{"INNERTUBE_API_KEY":"test-key","INNERTUBE_CLIENT_VERSION":"1.20260101.01.00",""" +
            """"VISITOR_DATA":"test-visitor","LOGGED_IN":$loggedIn,"SESSION_INDEX":0,""" +
            """"USER_SESSION_ID":"$userSessionId"}"""
    )

internal fun json(body: String) = YouTubeMusicReply(body)

internal fun songRow(videoId: String, title: String, artist: String = "Demo Artist"): String =
    """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$videoId"},"flexColumns":[""" +
        """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"$title"}}},""" +
        """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"$artist"}}}]}}"""

internal fun browseTab(vararg sections: String): String =
    """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":""" +
        """{"sectionListRenderer":{"contents":[${sections.joinToString(",")}]}}}}]}}}"""

internal fun libraryPage(vararg playlists: Pair<String, String>): String = browseTab(
    """{"gridRenderer":{"items":[${
        playlists.joinToString(",") { (browseId, title) ->
            """{"musicTwoRowItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},""" +
                """"title":{"simpleText":"$title"},"subtitle":{"simpleText":"12 songs"}}}"""
        }
    }]}}"""
)

internal fun homePage(vararg songs: Pair<String, String>): String = if (songs.isEmpty()) {
    browseTab()
} else {
    browseTab(
        """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":""" +
            """{"title":{"simpleText":"Quick picks"}}},"contents":[${
                songs.joinToString(",") { (videoId, title) ->
                    """{"musicTwoRowItemRenderer":{"title":{"simpleText":"$title"},""" +
                        """"navigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}}"""
                }
            }]}}"""
    )
}

internal fun creatorPage(sectionTitle: String, vararg songs: Pair<String, String>): String = browseTab(
    """{"musicShelfRenderer":{"title":{"simpleText":"$sectionTitle"},"contents":[${
        songs.joinToString(",") { (videoId, title) -> songRow(videoId, title) }
    }]}}"""
)

internal fun searchPage(vararg rows: String): String =
    """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":""" +
        """{"contents":[{"musicShelfRenderer":{"contents":[${rows.joinToString(",")}]}}]}}}}]}}}"""

internal fun sha1Hex(value: String): String = MessageDigest.getInstance("SHA-1")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
