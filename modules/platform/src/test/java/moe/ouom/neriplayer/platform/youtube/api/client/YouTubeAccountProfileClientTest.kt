package moe.ouom.neriplayer.platform.youtube.api.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAccountProfile
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YouTubeAccountProfileClientTest {
    @Test
    fun `account request uses PC account menu protocol and one authorization snapshot`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val cookies = linkedMapOf("SAPISID" to "synthetic-sid", "SID" to "synthetic-sid")
        val auth = YouTubeAuthBundle(cookies = cookies, xGoogAuthUser = "2")
        val http = fixtureHttpClient { request ->
            requests += request
            if (request.url.encodedPath == "/") {
                cookies["SAPISID"] = "different-account"
                response(request, BOOTSTRAP)
            } else response(request, PROFILE)
        }
        try {
            assertEquals(
                YouTubeAccountProfile("Fixture User", "https://yt3.ggpht.com/avatar=s88"),
                YouTubeAccountProfileClient(http).getAccountProfile(auth)
            )
            assertEquals(2, requests.size)
            requests.forEach { request ->
                assertEquals("music.youtube.com", request.url.host)
                assertTrue(request.header("Cookie").orEmpty().contains("SAPISID=synthetic-sid"))
                assertFalse(request.header("Cookie").orEmpty().contains("different-account"))
                assertEquals("2", request.header("X-Goog-AuthUser"))
            }
            val menu = requests.last()
            assertEquals("POST", menu.method)
            assertEquals("/youtubei/v1/account/account_menu", menu.url.encodedPath)
            assertEquals("fixture-key", menu.url.queryParameter("key"))
            assertEquals("false", menu.url.queryParameter("prettyPrint"))
            assertEquals("https://music.youtube.com", menu.header("Origin"))
            assertEquals("https://music.youtube.com", menu.header("X-Origin"))
            assertEquals("https://music.youtube.com/", menu.header("Referer"))
            assertEquals("fixture-visitor", menu.header("X-Goog-Visitor-Id"))
            assertEquals("67", menu.header("X-YouTube-Client-Name"))
            assertEquals("fixture-version", menu.header("X-YouTube-Client-Version"))
            assertEquals("true", menu.header("X-YouTube-Bootstrap-Logged-In"))
            val authorization = menu.header("Authorization").orEmpty()
            val timestamp = authorization.substringAfter("SAPISIDHASH ").substringBefore('_')
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("fixture-session $timestamp synthetic-sid https://music.youtube.com".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            assertEquals("SAPISIDHASH ${timestamp}_${digest}_u", authorization)
            val body = Buffer().also { menu.body!!.writeTo(it) }.readUtf8()
            val context = JSONObject(body).getJSONObject("context")
            assertEquals("WEB_REMIX", context.getJSONObject("client").getString("clientName"))
            assertEquals("fixture-version", context.getJSONObject("client").getString("clientVersion"))
            assertEquals("DESKTOP", context.getJSONObject("client").getString("platform"))
            assertEquals("2", context.getJSONObject("request").getString("sessionIndex"))
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `missing authorization does not send a bootstrap request`() = runBlocking {
        var requests = 0
        val http = fixtureHttpClient { request -> requests++; response(request, PROFILE) }
        try {
            assertNull(YouTubeAccountProfileClient(http).getAccountProfile(YouTubeAuthBundle()))
            assertEquals(0, requests)
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `signed out bootstrap never requests an account menu`() = runBlocking {
        var requests = 0
        val http = fixtureHttpClient { request ->
            requests++
            response(request, BOOTSTRAP.replace("\"LOGGED_IN\":true", "\"LOGGED_IN\":false"))
        }
        try {
            assertNull(YouTubeAccountProfileClient(http).getAccountProfile(AUTH))
            assertEquals(1, requests)
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `bootstrap session index is used when authorization has no explicit selection`() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val http = fixtureHttpClient { request ->
            requests += request
            response(request, if (request.url.encodedPath == "/") BOOTSTRAP else PROFILE)
        }
        try {
            YouTubeAccountProfileClient(http).getAccountProfile(AUTH)
            assertEquals("3", requests.last().header("X-Goog-AuthUser"))
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `HTTP failure reports only status and never returns account response details`() = runBlocking {
        var requests = 0
        val http = fixtureHttpClient { request ->
            requests++
            if (request.url.encodedPath == "/") response(request, BOOTSTRAP)
            else response(request, "private account response", code = 403)
        }
        try {
            val error = runCatching { YouTubeAccountProfileClient(http).getAccountProfile(AUTH) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("YouTube account profile request failed: HTTP 403", error?.message)
            assertEquals(2, requests)
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `malformed account menu is rejected without changing the supplied authorization`() = runBlocking {
        val http = fixtureHttpClient { request ->
            response(request, if (request.url.encodedPath == "/") BOOTSTRAP else "malformed fixture")
        }
        try {
            val error = runCatching { YouTubeAccountProfileClient(http).getAccountProfile(AUTH) }.exceptionOrNull()
            assertTrue(error is JSONException)
            assertEquals("SAPISID=synthetic-sid; SID=synthetic-sid", AUTH.cookieHeader)
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `account menu without current profile returns no fabricated account`() = runBlocking {
        val http = fixtureHttpClient { request ->
            response(request, if (request.url.encodedPath == "/") BOOTSTRAP else "{}")
        }
        try {
            assertNull(YouTubeAccountProfileClient(http).getAccountProfile(AUTH))
        } finally {
            http.closeFixture()
        }
    }

    @Test
    fun `cancelling account profile load cancels the in flight HTTP call`() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val enteredMenu = CompletableDeferred<Unit>()
        val releaseMenu = CountDownLatch(1)
        val http = fixtureHttpClient { request ->
            if (request.url.encodedPath == "/") response(request, BOOTSTRAP)
            else {
                enteredMenu.complete(Unit)
                check(releaseMenu.await(5, TimeUnit.SECONDS))
                response(request, PROFILE)
            }
        }.newBuilder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
        }).build()
        try {
            val job = launch { YouTubeAccountProfileClient(http).getAccountProfile(AUTH) }
            withTimeout(5_000L) { enteredMenu.await() }
            job.cancelAndJoin()
            assertEquals(2, calls.size)
            assertTrue(calls.last().isCanceled())
        } finally {
            releaseMenu.countDown()
            http.closeFixture()
        }
    }

    private fun fixtureHttpClient(respond: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> respond(chain.request()) }.build()

    private fun response(request: Request, body: String, code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("Fixture").body(body.toResponseBody()).build()

    private fun OkHttpClient.closeFixture() {
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    companion object {
        private val AUTH = YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic-sid; SID=synthetic-sid")
        private const val BOOTSTRAP = """{"INNERTUBE_API_KEY":"fixture-key","INNERTUBE_CLIENT_VERSION":"fixture-version","VISITOR_DATA":"fixture-visitor","LOGGED_IN":true,"SESSION_INDEX":3,"DATASYNC_ID":"fixture-channel||fixture-session"}"""
        private const val PROFILE = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"Fixture User"},"accountPhoto":{"thumbnails":[{"url":"//yt3.ggpht.com/avatar=s88"}]}}}}}}}]}"""
    }
}
