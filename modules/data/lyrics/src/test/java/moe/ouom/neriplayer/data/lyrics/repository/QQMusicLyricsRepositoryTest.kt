package moe.ouom.neriplayer.data.lyrics.repository

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.lyrics.client.AmllTtmlClient
import moe.ouom.neriplayer.api.search.client.QQMusicSearchApi
import moe.ouom.neriplayer.core.model.music.MusicPlatform
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QQMusicLyricsRepositoryTest {
    private val requests = CopyOnWriteArrayList<Request>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val body = when {
            request.url.host == "amll.test" -> "[]"
            request.url.encodedPath.contains("client_search") ->
                """{"data":{"song":{"list":[{"songmid":"id","songname":"Signal","singer":[{"name":"Artist"}],"albummid":"album","albumname":"Album","interval":180}]}}}"""
            request.url.queryParameter("data").orEmpty().contains("get_song_detail_yqq") ->
                """{"songinfo":{"data":{"track_info":{"mid":"id","name":"Signal","singer":[{"name":"Artist"}],"album":{"name":"Album","mid":"album"},"interval":180}}}}"""
            else -> """{"req":{"code":0,"data":{"lyric":"[00:01.00]hello","trans":"[00:01.00]你好"}}}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `search uses injected transport and preserves query and page`() = runTest {
        val api = api { false }
        val result = api.search("Signal Artist", 3).single()
        assertEquals("id", result.id)
        assertEquals("3:00", result.duration)
        assertEquals(MusicPlatform.QQ_MUSIC, result.source)
        assertEquals("Signal Artist", requests.single().url.queryParameter("w"))
        assertEquals("3", requests.single().url.queryParameter("p"))
    }

    @Test
    fun `AMLL setting is read for each request and does not replace native lyrics when empty`() = runTest {
        val enabled = AtomicBoolean(false)
        val api = api(debugLogging = true) { enabled.get() }
        repeat(3) { index ->
            enabled.set(index == 1)
            val result = api.getSongInfo("id")
            assertEquals("[00:01.00]hello", result.lyric)
            assertEquals("[00:01.00]你好", result.translatedLyric)
        }
        assertEquals(1, requests.count { it.url.host == "amll.test" })
        assertEquals(
            3,
            requests.count { it.url.queryParameter("data").orEmpty().contains("get_song_detail_yqq") }
        )
    }

    @Test
    fun `native details never consult AMLL preference`() = runTest {
        val result = api { throw AssertionError("native lookup must not read AMLL preference") }.getNativeSongInfo("id")
        assertEquals("Signal", result.songName)
        assertEquals("[00:01.00]hello", result.lyric)
        assertEquals(0, requests.count { it.url.host == "amll.test" })
    }

    @Test
    fun `AMLL preference cancellation propagates`() {
        val cancellation = CancellationException("cancelled")
        val actual = assertThrows(CancellationException::class.java) {
            runTest { api { throw cancellation }.getSongInfo("id") }
        }
        assertSame(cancellation, generateSequence<Throwable>(actual) { it.cause }.last())
    }

    @Test
    fun `AMLL preference failure retains native lyrics`() = runTest {
        val result = api { throw IllegalStateException("settings unavailable") }.getSongInfo("id")
        assertEquals("[00:01.00]hello", result.lyric)
        assertEquals("[00:01.00]你好", result.translatedLyric)
    }

    private fun api(debugLogging: Boolean = false, enabled: suspend () -> Boolean) = QQMusicLyricsRepository(
        api = QQMusicSearchApi(client, debugLogging),
        amllTtmlClient = AmllLyricsRepository(AmllTtmlClient(client, "https://amll.test")),
        amllLyricsEnabledProvider = enabled
    )
}
