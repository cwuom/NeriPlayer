package moe.ouom.neriplayer.api.lyrics.client

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AmllTtmlClientTest {
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val body = """[{"file":"other.ttml","title":"Another song","artist":"Other artist","score":1}]"""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture").body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `transport returns protocol candidates without applying metadata matching`() = runTest {
        val result = AmllTtmlClient(client).searchLyrics("Signal")

        assertEquals("other.ttml", result.single().file)
        assertEquals("Other artist", result.single().artist)
        assertEquals(1, result.single().score)
    }
}
