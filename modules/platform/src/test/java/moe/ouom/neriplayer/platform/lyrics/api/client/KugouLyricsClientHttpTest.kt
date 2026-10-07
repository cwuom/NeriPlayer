package moe.ouom.neriplayer.platform.lyrics.api.client

import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouLyricsClientHttpTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body) = replies.removeFirstOrNull() ?: throw IOException("offline")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private val candidate = KugouLyricCandidate(id = "220297734", accessKey = "key", durationMs = 206_000L, score = 60)

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `song search sends the mobile query with a capped page size`() = runTest {
        replies += 200 to """
            {"status":1,"data":{"info":[{"hash":"abc123","songname":"爱你","singername":"陈芳语",
            "album_name":"爱你","album_audio_id":42,"duration":206}]}}
        """.trimIndent()

        val results = KugouLyricsClient(client).searchSongs("爱你 陈芳语", limit = 50)

        assertEquals(listOf("42" to 206_000L), results.map { it.id to it.durationMs })
        val request = requests.single()
        assertEquals("mobilecdn.kugou.com", request.url.host)
        assertEquals("/api/v3/search/song", request.url.encodedPath)
        assertEquals("爱你 陈芳语", request.url.queryParameter("keyword"))
        assertEquals("8", request.url.queryParameter("pagesize"))
        assertEquals("NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)", request.header("User-Agent"))
    }

    @Test
    fun `song search skips blank keywords and swallows transport failures`() = runTest {
        val kugou = KugouLyricsClient(client)

        assertTrue(kugou.searchSongs("  ").isEmpty())
        assertTrue(requests.isEmpty())

        replies += 503 to "busy"
        replies += 200 to "   "
        repeat(3) { assertTrue(kugou.searchSongs("Signal").isEmpty()) }
        assertEquals(3, requests.size)
    }

    @Test
    fun `lrc downloads request the pc client and decode base64 content`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("[00:01.00]Hello".toByteArray())
        replies += 200 to """{"status":200,"error_code":0,"content":"$encoded"}"""
        replies += 200 to """{"status":200,"error_code":0,"content":""}"""
        replies += 404 to "missing"
        val kugou = KugouLyricsClient(client)

        assertEquals(KugouLyricsPayload("[00:01.00]Hello"), kugou.downloadLrcLyric(candidate))
        assertNull(kugou.downloadLrcLyric(candidate))
        assertNull(kugou.downloadLrcLyric(candidate))
        assertEquals(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=220297734&accesskey=key&fmt=lrc&charset=utf8",
            requests.first().url.toString()
        )
    }
}
