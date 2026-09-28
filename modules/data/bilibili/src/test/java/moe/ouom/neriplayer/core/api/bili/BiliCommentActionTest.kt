package moe.ouom.neriplayer.core.api.bili

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BiliCommentActionTest {
    @Test
    fun `cookie request helper omits absent values`() {
        val empty = Request.Builder().url("https://api.bilibili.com/").biliCookie(null).build()
        val blank = Request.Builder().url("https://api.bilibili.com/").biliCookie(" ").build()
        val present = Request.Builder().url("https://api.bilibili.com/")
            .biliCookie("SESSDATA=session").build()
        assertNull(empty.header("Cookie"))
        assertNull(blank.header("Cookie"))
        assertEquals("SESSDATA=session", present.header("Cookie"))
    }

    @Test
    fun `anonymous comment reads reuse fingerprint cookies then switch to stored login`(): Unit = runBlocking {
        val cookies = mock(BiliCookieRepository::class.java)
        `when`(cookies.getCookiesOnce()).thenReturn(emptyMap())
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (request.url.encodedPath.endsWith("/finger/spi")) {
                """{"data":{"b_3":"anon-3","b_4":"","buvid_fp":"anon-fp"}}"""
            } else {
                """{"code":0,"data":{}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val client = BiliClient(cookies, http)
            client.getVideoComments(170001L)
            client.getVideoComments(170001L, page = 2, pageSize = 100, sort = 0)
            assertEquals(1, requests.count { it.url.encodedPath.endsWith("/finger/spi") })
            val anonymous = requests.filter { it.url.encodedPath == "/x/v2/reply" }
            assertEquals(2, anonymous.size)
            assertEquals("buvid3=anon-3; buvid_fp=anon-fp", anonymous.first().header("Cookie"))
            assertEquals("49", anonymous.last().url.queryParameter("ps"))
            assertEquals("0", anonymous.last().url.queryParameter("sort"))

            `when`(cookies.getCookiesOnce()).thenReturn(mapOf("SESSDATA" to "logged-in"))
            client.getVideoCommentReplies(170001L, "42", 1, 20)
            val loggedIn = requests.last()
            assertEquals("/x/v2/reply/reply", loggedIn.url.encodedPath)
            assertEquals("SESSDATA=logged-in", loggedIn.header("Cookie"))
            assertEquals("42", loggedIn.url.queryParameter("root"))
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `invalid comment identifiers fail before network request`(): Unit = runBlocking {
        val cookies = mock(BiliCookieRepository::class.java)
        val http = OkHttpClient.Builder().addInterceptor { error("Unexpected request") }.build()
        val client = BiliClient(cookies, http)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.getVideoComments(0L) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.getVideoCommentReplies(170001L, "0", 1, 20) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.getVideoCommentReplies(170001L, "42", 0, 20) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.getVideoCommentReplies(170001L, "42", 1, 21) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.sendVideoComment(170001L, "text", "42", null) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.sendVideoComment(170001L, "text", null, "42") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.setVideoCommentLiked(170001L, "0", true) }
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `cache session key follows login without depending on rotating csrf`(): Unit = runBlocking {
        val cookies = mock(BiliCookieRepository::class.java)
        val http = OkHttpClient.Builder().addInterceptor { error("Unexpected request") }.build()
        val client = BiliClient(cookies, http)
        try {
            `when`(cookies.getCookiesOnce()).thenReturn(emptyMap())
            assertNull(client.commentCacheSessionKey())
            assertEquals(false, client.hasCommentLogin())
            `when`(cookies.getCookiesOnce()).thenReturn(mapOf("SESSDATA" to "test-account-a"))
            assertEquals(true, client.hasCommentLogin())
            val first = client.commentCacheSessionKey()
            assertEquals(64, first?.length)
            `when`(cookies.getCookiesOnce()).thenReturn(mapOf("SESSDATA" to "test-account-a", "bili_jct" to "rotated"))
            assertEquals(first, client.commentCacheSessionKey())
            `when`(cookies.getCookiesOnce()).thenReturn(mapOf("SESSDATA" to "test-account-b"))
            assertNotEquals(first, client.commentCacheSessionKey())
            `when`(cookies.getCookiesOnce()).thenReturn(emptyMap())
            assertNull(client.commentCacheSessionKey())
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `posting and nested replies use exact root parent and csrf without retry`(): Unit = runBlocking {
        val cookies = mock(BiliCookieRepository::class.java)
        `when`(cookies.getCookiesOnce()).thenReturn(mapOf("SESSDATA" to "test-session", "bili_jct" to "test-csrf"))
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"code":0}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val client = BiliClient(cookies, http)
            client.sendVideoComment(170001L, "text & 中文\nsecond line")
            client.sendVideoComment(170001L, "reply", "42", "42")
            client.sendVideoComment(170001L, "nested", "42", "43")
            val forms = requests.map { request ->
                assertEquals("POST", request.method)
                assertEquals("https://api.bilibili.com/x/v2/reply/add", request.url.toString())
                assertTrue(request.header("Cookie").orEmpty().contains("SESSDATA=test-session"))
                val body = requireNotNull(request.body)
                assertTrue(body.isOneShot())
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8().split("&").associate { field ->
                    val (key, value) = field.split("=", limit = 2)
                    key to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                }.also {
                    assertEquals("170001", it["oid"])
                    assertEquals("1", it["type"])
                    assertEquals("test-csrf", it["csrf"])
                    assertEquals("main_web", it["gaia_source"])
                    assertEquals("{\"appId\":100,\"platform\":5}", it["statistics"])
                }
            }
            assertEquals(listOf("0", "42", "42"), forms.map { it["root"] })
            assertEquals(listOf("0", "42", "43"), forms.map { it["parent"] })
            assertEquals("text & 中文\nsecond line", forms.first()["message"])
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
    @Test
    fun `like and unlike send authenticated forms to the native reply endpoint`(): Unit = runBlocking {
        val cookies = mock(BiliCookieRepository::class.java)
        `when`(cookies.getCookiesOnce()).thenReturn(mapOf("SESSDATA" to "test-session", "bili_jct" to "test-csrf"))
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"code":0}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = BiliClient(cookies, http)
        try {
            client.setVideoCommentLiked(170001L, "42", true)
            client.setVideoCommentLiked(170001L, "42", false)
            assertEquals(listOf("1", "0"), requests.map { request ->
                assertEquals("POST", request.method)
                assertEquals("https://api.bilibili.com/x/v2/reply/action", request.url.toString())
                assertTrue(request.header("Cookie").orEmpty().contains("SESSDATA=test-session"))
                assertEquals("https://www.bilibili.com", request.header("Referer"))
                val body = request.body as FormBody
                val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
                assertEquals("170001", fields["oid"])
                assertEquals("42", fields["rpid"])
                assertEquals("1", fields["type"])
                assertEquals("test-csrf", fields["csrf"])
                fields["action"]
            })
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `missing login or csrf does not send a write request`(): Unit = runBlocking {
        val cookies = mock(BiliCookieRepository::class.java)
        val http = OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
        val client = BiliClient(cookies, http)
        try {
            for (stored in listOf(emptyMap(), mapOf("SESSDATA" to "test-session"))) {
                `when`(cookies.getCookiesOnce()).thenReturn(stored)
                assertEquals(-101, client.setVideoCommentLiked(170001L, "42", true).getInt("code"))
                assertEquals(-101, client.sendVideoComment(170001L, "text").getInt("code"))
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
