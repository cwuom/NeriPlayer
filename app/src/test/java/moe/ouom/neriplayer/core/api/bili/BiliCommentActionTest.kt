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
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BiliCommentActionTest {
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
