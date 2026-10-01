package moe.ouom.neriplayer.platform.lyrics.api.client

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibClientTest {
    private var body = ""
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture").body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `transport preserves collapsed timeline and unrelated metadata for repository matching`() = runTest {
        body = """{"trackName":"Other song","artistName":"Other artist","duration":180,"syncedLyrics":"[00:00.00]one\n[00:00.00]two\n[00:00.00]three"}"""

        val result = LrcLibClient(client).getLyrics("Signal", "Artist", 180)

        assertEquals("Other song", result?.trackName)
        assertEquals("[00:00.00]one\n[00:00.00]two\n[00:00.00]three", result?.syncedLyrics)
        assertNull(result?.plainLyrics)
    }

    @Test
    fun `search excludes malformed duration but leaves lyric availability to repository`() = runTest {
        body = """[{"trackName":"invalid","duration":0},{"trackName":"valid","artistName":"Artist","duration":180}]"""

        val result = LrcLibClient(client).searchLyrics("Signal")

        assertEquals("valid", result.single().trackName)
        assertNull(result.single().syncedLyrics)
        assertNull(result.single().plainLyrics)
    }
}
