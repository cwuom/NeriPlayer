package moe.ouom.neriplayer.platform.bilibili.api.history

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliPlayHistoryTarget
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSource
import moe.ouom.neriplayer.platform.bilibili.history.directBiliPlayHistoryTarget
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BiliHistoryApiTest {
    private val loggedIn = BiliCookieSource { mapOf("SESSDATA" to "session", "bili_jct" to "csrf-token") }
    private val target = BiliPlayHistoryTarget(aid = 170001L, cid = 279786L, bvid = "BV17x411w7KC")

    @Test
    fun `heartbeat form follows the minimal ugc parameter set`() {
        val form = buildBiliHeartbeatForm(target, playedSec = 42L, csrf = "csrf-token")

        assertEquals(
            mapOf(
                "bvid" to "BV17x411w7KC",
                "cid" to "279786",
                "type" to "3",
                "played_time" to "42",
                "csrf" to "csrf-token"
            ),
            form.toMap()
        )
    }

    @Test
    fun `heartbeat falls back to aid and keeps the completed marker`() {
        val form = buildBiliHeartbeatForm(target.copy(bvid = null), playedSec = -1L, csrf = "c")

        assertEquals("170001", form.toMap()["aid"])
        assertNull(form.toMap()["bvid"])
        assertEquals("-1", form.toMap()["played_time"])
    }

    @Test
    fun `paused account history skips the heartbeat and reuses the cached state`() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = BiliHistoryApi(loggedIn, fakeHttp(requests) { """{"code":0,"data":true}""" })

        api.reportHeartbeat(target, 10L)
        api.reportHeartbeat(target, 20L)

        assertEquals(listOf("/x/v2/history/shadow"), requests.map { it.url.encodedPath })
    }

    @Test
    fun `active history posts the heartbeat with the stored login cookies`() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = BiliHistoryApi(loggedIn, fakeHttp(requests) { request ->
            if (request.url.encodedPath.endsWith("shadow")) """{"code":0,"data":false}""" else """{"code":0}"""
        })

        api.reportHeartbeat(target, 15L)

        val heartbeat = requests.last()
        assertEquals("/x/click-interface/web/heartbeat", heartbeat.url.encodedPath)
        assertEquals("POST", heartbeat.method)
        assertTrue(heartbeat.header("Cookie").orEmpty().contains("SESSDATA=session"))
        assertEquals("15", (heartbeat.body as FormBody).toMap()["played_time"])
    }

    @Test
    fun `rejected heartbeat surfaces as an error`() {
        val api = BiliHistoryApi(loggedIn, fakeHttp(mutableListOf()) { request ->
            if (request.url.encodedPath.endsWith("shadow")) """{"code":0,"data":false}""" else """{"code":-111}"""
        })

        assertThrows(IOException::class.java) { runBlocking { api.reportHeartbeat(target, 1L) } }
    }

    @Test
    fun `missing csrf means no login and no request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = BiliHistoryApi(BiliCookieSource { mapOf("SESSDATA" to "session") }, fakeHttp(requests) { "{}" })

        assertFalse(api.hasLogin())
        api.reportHeartbeat(target, 1L)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `direct target needs an explicit aid and cid`() {
        val song = SongItem(
            id = 170001L,
            name = "part",
            artist = "up",
            album = "Bilibili|279786|BV17x411w7KC",
            albumId = 0L,
            durationMs = 1L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "170001",
            subAudioId = "279786"
        )

        assertEquals(target, directBiliPlayHistoryTarget(song))
        assertNull(directBiliPlayHistoryTarget(song.copy(audioId = null)))
        assertNull(directBiliPlayHistoryTarget(song.copy(audioId = "BV17x411w7KC")))
    }

    private fun FormBody.toMap(): Map<String, String> = (0 until size).associate { name(it) to value(it) }

    private fun fakeHttp(requests: MutableList<Request>, body: (Request) -> String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body(request).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
}
