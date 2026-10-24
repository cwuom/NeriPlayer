package moe.ouom.neriplayer.platform.netease.playmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心动模式响应解析器单测。
 *
 * 解析器是纯函数，任何非法输入都必须收敛成 [PlaymodeIntelligenceResult.Failure]，
 * 绝不向调用方抛异常（否则会把「未登录」这种预期情况变成崩溃）。
 */
class PlaymodeIntelligenceResponseParserTest {

    @Test
    fun `code 301 means not logged in`() {
        val result = PlaymodeIntelligenceResponseParser.parse("""{"code":301,"message":null}""")

        assertTrue(result is PlaymodeIntelligenceResult.RequiresLogin)
    }

    @Test
    fun `code 200 parses the first song with netease field mapping`() {
        val raw = """
            {
              "code": 200,
              "data": [
                {
                  "id": 1901371647,
                  "name": "Demo Song",
                  "dt": 253000,
                  "ar": [
                    {"id": 1, "name": "Artist A"},
                    {"id": 2, "name": "Artist B"}
                  ],
                  "al": {
                    "id": 99,
                    "name": "Demo Album",
                    "picUrl": "http://example.test/cover.jpg"
                  }
                }
              ]
            }
        """.trimIndent()

        val result = PlaymodeIntelligenceResponseParser.parse(raw)

        assertTrue(result is PlaymodeIntelligenceResult.Success)
        val song = (result as PlaymodeIntelligenceResult.Success).song
        assertEquals(1901371647L, song.id)
        assertEquals("Demo Song", song.name)
        assertEquals("Artist A / Artist B", song.artist)
        assertEquals("Demo Album", song.album)
        assertEquals(99L, song.albumId)
        assertEquals(253000L, song.durationMs)
        assertEquals("https://example.test/cover.jpg", song.coverUrl)
        assertEquals("netease", song.channelId)
        assertEquals("1901371647", song.audioId)
        assertEquals(listOf("Artist A", "Artist B"), song.neteaseArtists?.map { it.name })
    }

    @Test
    fun `wrapped data songs shape is also accepted`() {
        val raw = """
            {
              "code": 200,
              "data": {
                "songs": [
                  {"id": 42, "name": "Wrapped", "dt": 1000, "ar": [], "al": {"id": 7, "name": "A"}}
                ]
              }
            }
        """.trimIndent()

        val result = PlaymodeIntelligenceResponseParser.parse(raw)

        assertTrue(result is PlaymodeIntelligenceResult.Success)
        assertEquals(42L, (result as PlaymodeIntelligenceResult.Success).song.id)
    }

    @Test
    fun `code 200 with empty data array means no recommendation`() {
        val result = PlaymodeIntelligenceResponseParser.parse("""{"code":200,"data":[]}""")

        assertTrue(result is PlaymodeIntelligenceResult.Empty)
    }

    @Test
    fun `code 200 without data field also means no recommendation`() {
        assertTrue(
            PlaymodeIntelligenceResponseParser.parse("""{"code":200}""")
                is PlaymodeIntelligenceResult.Empty
        )
        assertTrue(
            PlaymodeIntelligenceResponseParser.parse("""{"code":200,"data":null}""")
                is PlaymodeIntelligenceResult.Empty
        )
    }

    @Test
    fun `non 200 code becomes failure carrying code and message`() {
        val result = PlaymodeIntelligenceResponseParser.parse("""{"code":400,"message":"bad request"}""")

        assertTrue(result is PlaymodeIntelligenceResult.Failure)
        result as PlaymodeIntelligenceResult.Failure
        assertEquals(400, result.code)
        assertEquals("bad request", result.message)
    }

    @Test
    fun `server error code becomes failure carrying code and message`() {
        // 任务书 §5.1 点名：其它 code（如 400/500）归入通用失败
        val result = PlaymodeIntelligenceResponseParser.parse(
            """{"code":500,"message":"internal server error"}"""
        )

        assertTrue(result is PlaymodeIntelligenceResult.Failure)
        result as PlaymodeIntelligenceResult.Failure
        assertEquals(500, result.code)
        assertEquals("internal server error", result.message)
    }

    @Test
    fun `invalid json becomes failure instead of throwing`() {
        val result = PlaymodeIntelligenceResponseParser.parse("not a json at all")

        assertTrue(result is PlaymodeIntelligenceResult.Failure)
        assertEquals(
            PlaymodeIntelligenceResponseParser.CODE_UNPARSABLE,
            (result as PlaymodeIntelligenceResult.Failure).code
        )
    }

    @Test
    fun `blank response becomes failure instead of throwing`() {
        assertTrue(
            PlaymodeIntelligenceResponseParser.parse("") is PlaymodeIntelligenceResult.Failure
        )
    }

    @Test
    fun `data with unrecognized shape becomes failure`() {
        val result = PlaymodeIntelligenceResponseParser.parse("""{"code":200,"data":"oops"}""")

        assertTrue(result is PlaymodeIntelligenceResult.Failure)
        assertEquals(
            PlaymodeIntelligenceResponseParser.CODE_UNPARSABLE,
            (result as PlaymodeIntelligenceResult.Failure).code
        )
    }

    @Test
    fun `song missing id or name becomes failure`() {
        assertTrue(
            PlaymodeIntelligenceResponseParser.parse("""{"code":200,"data":[{"name":"no id"}]}""")
                is PlaymodeIntelligenceResult.Failure
        )
        assertTrue(
            PlaymodeIntelligenceResponseParser.parse("""{"code":200,"data":[{"id":1,"name":"   "}]}""")
                is PlaymodeIntelligenceResult.Failure
        )
    }

    @Test
    fun `data entries that are not objects are skipped then reported as failure`() {
        val result = PlaymodeIntelligenceResponseParser.parse("""{"code":200,"data":[1,"text",null]}""")

        assertTrue(result is PlaymodeIntelligenceResult.Failure)
        assertNull(
            (result as PlaymodeIntelligenceResult.Failure).message?.takeIf { it.isBlank() }
        )
    }

    @Test
    fun `anonymous probe sample is treated as requires login`() {
        // 2026-10-19 实测：匿名 POST /api/playmode/intelligence/list -> {"code":301,"message":null}
        val result = PlaymodeIntelligenceResponseParser.parse("""{"code":301,"message":null}""")

        assertTrue(result is PlaymodeIntelligenceResult.RequiresLogin)
    }

    @Test
    fun `wrapped songInfo shape parsed from real device sample`() {
        // 2026-10-19 真机实测（登录态）：data[] 元素是包装对象，歌曲字段全在 songInfo 里。
        // 这是生产环境的主要路径；此前按「元素即歌曲」解析导致每个元素都被丢掉，
        // 报「data 中没有可解析的歌曲」并错误降级到本地推荐。
        val raw = """
            {
              "code": 200,
              "message": "SUCCESS",
              "data": [
                {
                  "id": 610141,
                  "alg": "Alg_AI_One_28018269:610141_simArtist-RH",
                  "recommended": false,
                  "songInfo": {
                    "no": 1,
                    "copyright": 1,
                    "rt": "",
                    "fee": 8,
                    "pop": 60.0,
                    "dt": 261870,
                    "rtype": 0,
                    "id": 610141,
                    "name": "Intro",
                    "cd": "1",
                    "pst": 0,
                    "ar": [{"id": 1, "name": "Artist A"}],
                    "al": {"id": 99, "name": "Demo Album", "picUrl": "http://example.test/cover.jpg"}
                  }
                }
              ]
            }
        """.trimIndent()

        val result = PlaymodeIntelligenceResponseParser.parse(raw)

        assertTrue(result is PlaymodeIntelligenceResult.Success)
        val song = (result as PlaymodeIntelligenceResult.Success).song
        assertEquals(610141L, song.id)
        assertEquals("Intro", song.name)
        assertEquals("Artist A", song.artist)
        assertEquals("Demo Album", song.album)
        assertEquals(261870L, song.durationMs)
    }

    @Test
    fun `outer id backfills songInfo missing id`() {
        // 兜底：songInfo 里没有 id 时用外层元素 id 补，否则 parseNeteaseSong 会因 id<=0 丢掉
        val raw = """{"code":200,"data":[{"id":42,"alg":"x","songInfo":{"name":"No Inner Id","dt":1000}}]}"""

        val result = PlaymodeIntelligenceResponseParser.parse(raw)

        assertTrue(result is PlaymodeIntelligenceResult.Success)
        assertEquals(42L, (result as PlaymodeIntelligenceResult.Success).song.id)
    }

    @Test
    fun `wrongly typed songInfo falls through to direct song shape`() {
        // songInfo 不是对象（null/字符串）时应回退到「元素本身即歌曲」，而不是整体失败
        val raw = """{"code":200,"data":[{"id":7,"songInfo":null,"name":"Direct","dt":2000,"ar":[],"al":{"id":3,"name":"A"}}]}"""

        val result = PlaymodeIntelligenceResponseParser.parse(raw)

        assertTrue(result is PlaymodeIntelligenceResult.Success)
        val song = (result as PlaymodeIntelligenceResult.Success).song
        assertEquals(7L, song.id)
        assertEquals("Direct", song.name)
    }

    @Test
    fun `bogus song info key does not misidentify a pure wrapper as a song`() {
        // 纯包装对象（既无歌曲容器也无 name）必须判为不可解析，绝不能靠 alg 之类的键蒙一首歌
        val raw = """{"code":200,"data":[{"id":9,"alg":"only-alg","songDetail":null}]}"""

        val result = PlaymodeIntelligenceResponseParser.parse(raw)

        assertTrue(result is PlaymodeIntelligenceResult.Failure)
    }
}
