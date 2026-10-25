package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException
import moe.ouom.neriplayer.platform.bilibili.lyrics.parseBiliSubtitleCues
import moe.ouom.neriplayer.platform.bilibili.lyrics.parseBiliSubtitleTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliClientSubtitleTest {
    @Test
    fun `subtitle tracks read the player endpoint with aid cid and wbi signature`() = biliClientTest(
        route = { request ->
            json(PLAYER_WITH_SUBTITLE).takeIf { request.url.encodedPath == PLAYER_PATH }
        }
    ) { http, client ->
        val tracks = parseBiliSubtitleTracks(client.getPlayerSubtitlePayload(aid = 170001L, cid = 101L))

        val request = http.requestsTo(PLAYER_PATH).single()
        assertEquals("170001", request.url.queryParameter("aid"))
        assertEquals("101", request.url.queryParameter("cid"))
        assertEquals(expectedWbiSignature(request), request.url.queryParameter("w_rid"))
        assertEquals("SESSDATA=test-session", request.header("Cookie"))

        val track = tracks.single()
        assertEquals("ai-zh", track.lan)
        assertEquals("中文（自动生成）", track.lanDoc)
        assertTrue(track.isAiGenerated)
        assertEquals(
            "https://aisubtitle.hdslb.com/bfs/ai_subtitle/prod/a.json?auth_key=1",
            track.url
        )
    }

    @Test
    fun `subtitle cues download from the returned url and parse the body`() = biliClientTest(
        route = { request ->
            when (request.url.host) {
                "aisubtitle.hdslb.com" -> json(SUBTITLE_BODY)
                else -> json(PLAYER_WITH_SUBTITLE)
                    .takeIf { request.url.encodedPath == PLAYER_PATH }
            }
        }
    ) { http, client ->
        val track = parseBiliSubtitleTracks(client.getPlayerSubtitlePayload(170001L, 101L)).single()

        val cues = parseBiliSubtitleCues(client.getSubtitlePayload(track.url))

        assertEquals(listOf("第一句", "第二句"), cues.map { it.content })
        assertEquals(listOf(1_860L, 5_820L), cues.map { it.startTimeMs })
        val subtitleRequest = http.requests.single { it.url.host == "aisubtitle.hdslb.com" }
        assertTrue(subtitleRequest.url.isHttps)
        assertEquals("SESSDATA=test-session", subtitleRequest.header("Cookie"))
    }

    @Test
    fun `protocol relative subtitle urls are upgraded before the request`() = biliClientTest(
        route = { request ->
            json(SUBTITLE_BODY).takeIf { request.url.host == "aisubtitle.hdslb.com" }
        }
    ) { http, client ->
        val cues = parseBiliSubtitleCues(client.getSubtitlePayload("//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/a.json"))

        assertEquals(2, cues.size)
        val request = http.requests.single()
        assertTrue(request.url.isHttps)
        assertEquals("/bfs/ai_subtitle/prod/a.json", request.url.encodedPath)
    }

    @Test
    fun `blank subtitle url sends no request`() = biliClientTest(route = { null }) { http, client ->
        assertTrue(parseBiliSubtitleCues(client.getSubtitlePayload("   ")).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `subtitle url outside bilibili hosts is rejected before any request`() = biliClientTest(
        route = { null }
    ) { http, client ->
        val error = runCatching {
            client.getSubtitlePayload("https://evil.example/a.json")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `player error surfaces as an io exception`() = biliClientTest(
        route = { request ->
            json("""{"code":-404,"message":"啥都木有"}""")
                .takeIf { request.url.encodedPath == PLAYER_PATH }
        }
    ) { _, client ->
        val error = runCatching { client.getPlayerSubtitlePayload(1L, 2L) }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    private companion object {
        const val PLAYER_PATH = "/x/player/wbi/v2"

        const val PLAYER_WITH_SUBTITLE = """
            {"code":0,"message":"0","data":{"aid":170001,"cid":101,"need_login_subtitle":true,
             "subtitle":{"allow_submit":false,"lan":"","lan_doc":"","subtitles":[
               {"id":1497922385058359296,"lan":"ai-zh","lan_doc":"中文（自动生成）","is_lock":false,
                "subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/a.json?auth_key=1",
                "type":1,"id_str":"1497922385058359296","ai_type":0,"ai_status":2}
             ]}}}
        """

        const val SUBTITLE_BODY = """
            {"font_size":0.4,"type":"AIsubtitle","lang":"zh","body":[
              {"from":1.86,"to":4.46,"sid":1,"location":2,"content":"第一句","music":0.0},
              {"from":5.82,"to":13.25,"sid":2,"location":2,"content":"第二句","music":0.0}
            ]}
        """
    }
}
