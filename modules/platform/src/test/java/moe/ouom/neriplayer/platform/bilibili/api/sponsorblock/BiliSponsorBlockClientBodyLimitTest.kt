package moe.ouom.neriplayer.platform.bilibili.api.sponsorblock

import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BiliSponsorBlockClientBodyLimitTest {
    private val limitBytes = 512 * 1024

    @Test
    fun `bodies are decoded with the declared charset or utf8`() {
        assertEquals("[\"片段\"]", load("[\"片段\"]".toResponseBody(null)))
        assertEquals(
            "[\"片段\"]",
            load("[\"片段\"]".toByteArray(Charsets.UTF_16).toResponseBody("application/json; charset=UTF-16".toMediaType()))
        )
    }

    @Test
    fun `oversized bodies are rejected whether declared or streamed`() {
        val declared = ByteArray(limitBytes + 1).toResponseBody("application/json".toMediaType())
        val streamed = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = Buffer().write(ByteArray(limitBytes + 10))
        }

        listOf(declared, streamed).forEach { body ->
            val error = assertThrows(IOException::class.java) { load(body) }
            assertEquals("BilibiliSponsorBlock response exceeds $limitBytes bytes", error.message)
        }
    }

    private fun load(body: ResponseBody): String? {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        return try {
            BiliSponsorBlockClient(client).loadSegments("BV14741127BN")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
