package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.bilibili.playback.DashStream
import moe.ouom.neriplayer.data.model.bilibili.playback.Durl
import moe.ouom.neriplayer.data.model.bilibili.playback.PlayOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliClientPlayUrlTest {
    @Test
    fun `play url request sends every explicit option and parses all stream sections`() = biliClientTest(
        route = { request -> json(FULL_PLAY_URL).takeIf { request.url.encodedPath == PLAY_URL_PATH } }
    ) { http, client ->
        val info = client.getPlayInfoByBvid(
            bvid = "BV1xx411c7mD",
            cid = 1001L,
            opts = PlayOptions(
                qn = 80,
                fnval = 4048,
                fnver = 1,
                fourk = 1,
                platform = "pc",
                highQuality = 1,
                tryLook = 1,
                session = "sess01",
                gaiaSource = "viewcard",
                isGaiaAvoided = false
            )
        )

        val request = http.requestsTo(PLAY_URL_PATH).single()
        val url = request.url
        assertEquals(
            mapOf(
                "bvid" to "BV1xx411c7mD", "cid" to "1001", "qn" to "80", "fnval" to "4048",
                "fnver" to "1", "fourk" to "1", "otype" to "json", "platform" to "pc",
                "high_quality" to "1", "try_look" to "1", "session" to "sess01",
                "gaia_source" to "viewcard", "isGaiaAvoided" to "false"
            ),
            url.queryParameterNames.filter { it != "wts" && it != "w_rid" }.associateWith { url.queryParameter(it) }
        )
        assertEquals(expectedWbiSignature(request), url.queryParameter("w_rid"))
        assertEquals("https://www.bilibili.com", request.header("Referer"))
        assertEquals("SESSDATA=test-session", request.header("Cookie"))

        assertEquals(0, info.code)
        assertEquals(80, info.qnSelected)
        assertEquals("flv480", info.format)
        assertEquals(215_000L, info.timeLengthMs)
        assertEquals(listOf("高清 1080P", "清晰 480P"), info.acceptDescription)
        assertEquals(listOf(80, 32), info.acceptQuality)
        assertEquals(
            listOf(Durl(1, 215_000L, 5_000_000L, "https://cn-gd.bilivideo.com/a.flv", listOf("https://upos-sz.bilivideo.com/a.flv"))),
            info.durl
        )
        assertEquals(
            listOf(
                DashStream(
                    id = 80, baseUrl = "https://upos-sz.bilivideo.com/v.m4s",
                    backupUrls = listOf("https://cn-gd.bilivideo.com/v.m4s"), bandwidth = 1_200_000L,
                    mimeType = "video/mp4", codecs = "avc1.640032", width = 1920, height = 1080,
                    frameRate = "29.970", codecid = 7
                )
            ),
            info.dashVideo
        )
        assertEquals(
            listOf(
                DashStream(
                    id = 30280, baseUrl = "https://x.mountaintoys.cn/a.m4s",
                    backupUrls = listOf("https://upos-hz.bilivideo.com/a.m4s"), bandwidth = 320_000L,
                    mimeType = "audio/mp4", codecs = "mp4a.40.2", width = 0, height = 0,
                    frameRate = "", codecid = 0
                )
            ),
            info.dashAudio
        )
        assertEquals(1, info.dolby?.type)
        assertEquals(listOf(30250), info.dolby?.audios?.map { it.id })
        assertEquals(true, info.flac?.display)
        assertEquals(30251, info.flac?.audio?.id)

        assertEquals(
            listOf(
                BiliAudioStreamInfo(
                    id = 30280, mimeType = "audio/mp4", bitrateKbps = 320, qualityTag = null,
                    url = "https://upos-hz.bilivideo.com/a.m4s",
                    candidateUrls = listOf("https://upos-hz.bilivideo.com/a.m4s", "https://x.mountaintoys.cn/a.m4s")
                ),
                BiliAudioStreamInfo(
                    id = 30250, mimeType = "audio/eac3", bitrateKbps = 448, qualityTag = "dolby",
                    url = "https://upos-sz.bilivideo.com/dolby.m4s"
                ),
                BiliAudioStreamInfo(
                    id = 30251, mimeType = "audio/flac", bitrateKbps = 1600, qualityTag = "hires",
                    url = "https://upos-sz.bilivideo.com/flac.m4s"
                )
            ),
            with(client) { info.toAudioStreamInfos() }
        )
    }

    @Test
    fun `play url by avid omits unset options and tolerates absent stream sections`() = biliClientTest(
        route = { request -> json(MP4_ONLY_PLAY_URL).takeIf { request.url.encodedPath == PLAY_URL_PATH } }
    ) { http, client ->
        val info = client.getPlayInfoByAvid(avid = 170001L, cid = 2002L)

        val url = http.requestsTo(PLAY_URL_PATH).single().url
        assertEquals("170001", url.queryParameter("avid"))
        assertEquals("2002", url.queryParameter("cid"))
        assertEquals("272", url.queryParameter("fnval"))
        assertEquals("0", url.queryParameter("fourk"))
        assertEquals("pc", url.queryParameter("platform"))
        for (absent in listOf("qn", "high_quality", "try_look", "session", "gaia_source", "isGaiaAvoided")) {
            assertNull(absent, url.queryParameter(absent))
        }
        assertNull(info.qnSelected)
        assertNull(info.format)
        assertNull(info.timeLengthMs)
        assertEquals(emptyList<String>(), info.acceptDescription)
        assertEquals(emptyList<Int>(), info.acceptQuality)
        assertEquals(
            listOf(Durl(1, 0L, 0L, "https://cn-gd.bilivideo.com/x.mp4", listOf("https://upos-sz.bilivideo.com/x.mp4"))),
            info.durl
        )
        assertTrue(info.dashVideo.isEmpty())
        assertTrue(info.dashAudio.isEmpty())
        assertNull(info.dolby)
        assertNull(info.flac)
    }

    @Test
    fun `play url api error is reported with code and message`() = biliClientTest(
        route = { request ->
            json("""{"code":-404,"message":"啥都木有"}""").takeIf { request.url.encodedPath == PLAY_URL_PATH }
        }
    ) { _, client ->
        val error = assertThrows(IOException::class.java) {
            runBlocking { client.getPlayInfoByBvid("BV1xx411c7mD", 1001L) }
        }
        assertEquals("requestPlayUrl failed: code=-404, message=啥都木有", error.message)
    }

    @Test
    fun `available dash audio is returned without retrying`() = biliClientTest(
        route = { request -> json(FULL_PLAY_URL).takeIf { request.url.encodedPath == PLAY_URL_PATH } }
    ) { http, client ->
        val streams = client.getAllAudioStreams("BV1xx411c7mD", 1001L)

        assertEquals(listOf(null, "dolby", "hires"), streams.map { it.qualityTag })
        assertEquals(1, http.requestsTo(PLAY_URL_PATH).size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `empty dash audio is retried with backoff before using the html5 progressive stream`() = biliClientTest(
        route = { request ->
            when {
                request.url.encodedPath != PLAY_URL_PATH -> null
                request.url.queryParameter("platform") == "html5" -> json(HTML5_PLAY_URL)
                else -> json(VIDEO_ONLY_PLAY_URL)
            }
        }
    ) { http, client ->
        val streams = client.getAllAudioStreams("BV1xx411c7mD", 1001L, PlayOptions(qn = 64, session = "sess01"))

        val playRequests = http.requestsTo(PLAY_URL_PATH)
        assertEquals(listOf("pc", "pc", "pc", "html5"), playRequests.map { it.url.queryParameter("platform") })
        val html5 = playRequests.last().url
        assertEquals("0", html5.queryParameter("fnval"))
        assertEquals("0", html5.queryParameter("fourk"))
        assertEquals("1", html5.queryParameter("high_quality"))
        assertEquals("64", html5.queryParameter("qn"))
        assertEquals("sess01", html5.queryParameter("session"))
        assertEquals(750L, testScheduler.currentTime)
        assertEquals(
            listOf(
                BiliAudioStreamInfo(
                    id = null, mimeType = "video/mp4", bitrateKbps = 500, qualityTag = null,
                    url = "https://upos-sz.bilivideo.com/h5.mp4",
                    candidateUrls = listOf("https://upos-sz.bilivideo.com/h5.mp4", "https://cn-hk.bilivideo.com/h5.mp4")
                )
            ),
            streams
        )
    }

    @Test
    fun `unusable html5 fallback reuses the last progressive stream from the dash response`() = biliClientTest(
        route = { request ->
            when {
                request.url.encodedPath != PLAY_URL_PATH -> null
                request.url.queryParameter("platform") == "html5" -> json("""{"code":0,"data":{"durl":[{"url":""}]}}""")
                else -> json("""{"code":0,"data":{"durl":[{"length":0,"size":0,"url":"https://upos-sz.bilivideo.com/pc.mp4"}]}}""")
            }
        }
    ) { http, client ->
        val streams = client.getAllAudioStreams("BV1xx411c7mD", 1001L)

        assertEquals(4, http.requestsTo(PLAY_URL_PATH).size)
        assertEquals(
            listOf(
                BiliAudioStreamInfo(
                    id = null, mimeType = "video/mp4", bitrateKbps = 0, qualityTag = null,
                    url = "https://upos-sz.bilivideo.com/pc.mp4"
                )
            ),
            streams
        )
    }

    @Test
    fun `play info without any media is not retried`() = biliClientTest(
        route = { request -> json("""{"code":0,"data":{}}""").takeIf { request.url.encodedPath == PLAY_URL_PATH } }
    ) { http, client ->
        assertEquals(emptyList<BiliAudioStreamInfo>(), client.getAllAudioStreams("BV1xx411c7mD", 1001L))
        assertEquals(1, http.requestsTo(PLAY_URL_PATH).size)
    }

    private companion object {
        const val PLAY_URL_PATH = "/x/player/wbi/playurl"

        val FULL_PLAY_URL = """
            {"code":0,"message":"0","data":{
              "quality":80,"format":"flv480","timelength":215000,
              "accept_description":["高清 1080P","清晰 480P"],"accept_quality":[80,32],
              "durl":[
                {"order":1,"length":215000,"size":5000000,"url":"https://cn-gd.bilivideo.com/a.flv",
                 "backup_url":["https://upos-sz.bilivideo.com/a.flv"]},
                7
              ],
              "dash":{
                "video":[{"id":80,"baseUrl":"https://upos-sz.bilivideo.com/v.m4s",
                  "backupUrl":["https://cn-gd.bilivideo.com/v.m4s"],"bandwidth":1200000,
                  "mimeType":"video/mp4","codecs":"avc1.640032","width":1920,"height":1080,
                  "frameRate":"29.970","codecid":7}],
                "audio":[
                  {"id":30280,"base_url":"https://x.mountaintoys.cn/a.m4s",
                   "backup_url":["https://upos-hz.bilivideo.com/a.m4s"],"bandwidth":320000,
                   "mime_type":"audio/mp4","codecs":"mp4a.40.2"},
                  {"id":30216,"baseUrl":""},
                  "not-an-object"
                ],
                "dolby":{"type":1,"audio":[{"id":30250,"baseUrl":"https://upos-sz.bilivideo.com/dolby.m4s",
                  "bandwidth":448000,"mimeType":"","codecs":"ec-3"}]},
                "flac":{"display":true,"audio":{"id":30251,"baseUrl":"https://upos-sz.bilivideo.com/flac.m4s",
                  "bandwidth":1600000,"mimeType":"","codecs":"fLaC"}}
              }
            }}
        """.trimIndent()

        val MP4_ONLY_PLAY_URL = """
            {"code":0,"data":{"durl":[{"url":"https://cn-gd.bilivideo.com/x.mp4",
              "backupUrl":["https://upos-sz.bilivideo.com/x.mp4"]}]}}
        """.trimIndent()

        val VIDEO_ONLY_PLAY_URL = """
            {"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"https://upos-sz.bilivideo.com/v.m4s"}],"audio":[]}}}
        """.trimIndent()

        val HTML5_PLAY_URL = """
            {"code":0,"data":{"durl":[{"order":1,"length":80000,"size":5000000,
              "url":"https://cn-hk.bilivideo.com/h5.mp4","backup_url":["https://upos-sz.bilivideo.com/h5.mp4"]}]}}
        """.trimIndent()
    }
}
