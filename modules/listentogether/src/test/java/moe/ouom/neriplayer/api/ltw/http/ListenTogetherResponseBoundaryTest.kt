package moe.ouom.neriplayer.api.ltw.http

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherResponseBoundaryTest {
    @Test
    fun `HTTP failures keep status for both room reads and writes`() = runBlocking {
        withApi("denied".toResponseBody(), code = 403) { api ->
            val failure = assertThrows(IOException::class.java) {
                runBlocking { api.getRoomState("https://worker.example", "ABC") }
            }
            assertTrue(failure.message.orEmpty().contains("GET failed (403): denied"))
        }
        withApi("gone".toResponseBody(), code = 410) { api ->
            val failure = assertThrows(IOException::class.java) {
                runBlocking { api.leaveRoom("https://worker.example", "ABC", "test-token") }
            }
            assertTrue(failure.message.orEmpty().contains("POST failed (410): gone"))
        }
    }

    @Test
    fun `response size limit covers advertised and streaming bodies`() = runBlocking {
        val oversized = "x".repeat(2 * 1024 * 1024 + 1)
        val streaming = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(oversized)
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = buffer
        }
        for (body in listOf(oversized.toResponseBody(), streaming)) {
            withApi(body) { api ->
                val failure = assertThrows(IOException::class.java) {
                    runBlocking { api.getRoomState("https://worker.example", "ABC") }
                }
                assertTrue(failure.message.orEmpty().contains("response too large"))
            }
        }
    }

    @Test
    fun `room responses preserve declared charset and optional fields`() = runBlocking {
        val json = """{"ok":true,"nickname":"听众","roomId":"ABC"}"""
        withApi(json.toByteArray(Charsets.UTF_16LE)
            .toResponseBody("application/json; charset=utf-16le".toMediaType())) { api ->
            val result = api.joinRoom("https://worker.example", "ABC", "user", "听众",
                memberSecret = "test-member", bearerToken = "test-token")
            assertTrue(result.ok)
            assertEquals("听众", result.nickname)
        }
    }

    @Test
    fun `availability distinguishes invalid base addresses and non-service responses`() = runBlocking {
        withApi("{}".toResponseBody()) { api ->
            assertEquals("invalid_base_url", api.testServerAvailability("ftp://worker.example").message)
            assertEquals("invalid_response", api.testServerAvailability("https://worker.example").message)
        }
    }

    private suspend fun withApi(body: ResponseBody, code: Int = 200, action: suspend (ListenTogetherApi) -> Unit) {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test response").body(body).build()
        }.build()
        try {
            action(ListenTogetherApi(http))
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
