package moe.ouom.neriplayer.platform.search.api.client

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicSearchApiTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body) = replies.removeFirst()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private val api = QQMusicSearchApi(client)

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `search maps songs and treats missing sections as no results`() = runTest {
        replies += 200 to """
            {"data":{"song":{"list":[
              {"songmid":"mid-1","songname":"Signal","singer":[{"name":"A"},{"name":"B"}],"albummid":"alb","albumname":"Album","interval":185},
              {"songmid":"mid-2","songname":"Single","singer":[],"albummid":null,"albumname":null,"interval":59}
            ]}}}
        """.trimIndent()
        replies += 200 to """{"data":null}"""
        replies += 200 to """{"data":{"song":null}}"""
        replies += 200 to """{"data":{"song":{"list":null}}}"""

        assertEquals(
            listOf(
                SongSearchInfo(
                    id = "mid-1",
                    songName = "Signal",
                    singer = "A/B",
                    duration = "3:05",
                    source = MusicPlatform.QQ_MUSIC,
                    albumName = "Album",
                    coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000alb.jpg"
                ),
                SongSearchInfo("mid-2", "Single", "", "0:59", MusicPlatform.QQ_MUSIC, null, null)
            ),
            api.search("Signal", page = 2)
        )
        repeat(3) { assertTrue(api.search("Signal", page = 1).isEmpty()) }
        val url = requests.first().url
        assertEquals("/soso/fcgi-bin/client_search_cp", url.encodedPath)
        assertEquals(listOf("json", "20", "2", "Signal", "1", "5381"), listOf("format", "n", "p", "w", "cr", "g_tk").map { url.queryParameter(it) })
    }

    @Test
    fun `song metadata converts the interval to milliseconds`() = runTest {
        replies += 200 to detail(interval = 180)
        replies += 200 to detail(interval = 0)

        val metadata = api.getSongMetadata("mid-1")

        assertEquals(180_000L, metadata.durationMs)
        assertEquals("Signal", metadata.details.songName)
        assertEquals("A/B", metadata.details.singer)
        assertEquals("Album", metadata.details.album)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000alb.jpg", metadata.details.coverUrl)
        assertNull(metadata.details.lyric)
        assertEquals(0L, api.getSongMetadata("mid-1").durationMs)
        assertTrue(requests.first().url.queryParameter("data")!!.contains(""""song_mid":"mid-1""""))
    }

    @Test
    fun `song metadata fails when the detail payload is missing`() {
        replies += 200 to """{"other":{}}"""
        replies += 200 to """{"songinfo":{"data":null}}"""
        replies += 200 to """{"songinfo":{"data":{"track_info":null}}}"""
        replies += 500 to "busy"

        assertEquals("响应中找不到 songinfo 字段", metadataFailure().message)
        assertEquals("找不到ID为 mid-1 的歌曲详情", metadataFailure().message)
        assertEquals("找不到ID为 mid-1 的歌曲详情", metadataFailure().message)
        assertTrue(metadataFailure().message!!.startsWith("请求失败: 500"))
    }

    @Test
    fun `native lyrics decode both tracks or quietly give up`() = runTest {
        replies += 200 to """{"req":{"code":0,"data":{"lyric":"[00:01.00]hello","trans":"[00:01.00]你好"}}}"""
        replies += 200 to """{"req":{"code":0}}"""
        replies += 200 to """{"req":{"code":-1}}"""
        replies += 200 to "{}"
        replies += 500 to "busy"

        assertEquals("[00:01.00]hello" to "[00:01.00]你好", api.getNativeLyrics("mid-1"))
        repeat(4) { assertEquals(null to null, api.getNativeLyrics("mid-1")) }
        assertEquals("https://y.qq.com", requests.first().header("Referer"))
        assertEquals("json", requests.first().url.queryParameter("format"))
    }

    @Test
    fun `native song info combines details with lyrics`() = runTest {
        replies += 200 to detail(interval = 180)
        replies += 200 to """{"req":{"code":0,"data":{"lyric":"[00:01.00]hello"}}}"""

        val details = api.getSongInfo("mid-1")

        assertEquals("mid-1", details.id)
        assertEquals("[00:01.00]hello", details.lyric)
        assertNull(details.translatedLyric)
    }

    private fun metadataFailure(): IOException =
        assertThrows(IOException::class.java) { runBlocking { api.getSongMetadata("mid-1") } }

    private fun detail(interval: Int) = """
        {"songinfo":{"data":{"track_info":{"mid":"mid-1","name":"Signal","singer":[{"name":"A"},{"name":"B"}],
        "album":{"name":"Album","mid":"alb"},"interval":$interval}}}}
    """.trimIndent()
}
