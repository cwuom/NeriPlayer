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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BiliCommentActionTest {
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
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
