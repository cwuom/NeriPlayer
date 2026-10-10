package moe.ouom.neriplayer.platform.bilibili.api.client

import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.bilibili.video.UgcSeason
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliClientVideoInfoTest {
    @Test
    fun `basic info joins desc_v2 text and maps owner stats pages and season`() = biliClientTest(
        route = { request -> json(FULL_VIEW).takeIf { request.url.encodedPath == VIEW_PATH } }
    ) { http, client ->
        val info = client.getVideoBasicInfoByBvid("BV17x411w7KC")

        val request = http.requestsTo(VIEW_PATH).single()
        assertEquals("BV17x411w7KC", request.url.queryParameter("bvid"))
        assertEquals(expectedWbiSignature(request), request.url.queryParameter("w_rid"))
        assertEquals(170001L, info.aid)
        assertEquals("测试视频", info.title)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", info.coverUrl)
        assertEquals("第一段\n第二段", info.desc)
        assertEquals(245, info.durationSec)
        assertEquals(12L, info.ownerMid)
        assertEquals("UP", info.ownerName)
        assertEquals("https://i1.hdslb.com/bfs/face/up.jpg", info.ownerFace)
        assertEquals(VideoStats(view = 1, danmaku = 2, reply = 3, favorite = 4, coin = 5, share = 6, like = 7), info.stats)
        assertEquals(
            listOf(
                VideoPage(cid = 101L, page = 1, part = "P1", durationSec = 120, width = 1920, height = 1080),
                VideoPage(cid = 102L, page = 2, part = "P2", durationSec = 125, width = 0, height = 0)
            ),
            info.pages
        )
        assertEquals(UgcSeason(id = 555L, mid = 12L, title = "合集"), info.ugcSeason)
    }

    @Test
    fun `basic info by avid falls back to plain desc and empty objects`() = biliClientTest(
        route = { request ->
            when (request.url.queryParameter("aid")) {
                "9" -> json("""{"code":0,"data":{"aid":9,"pic":"","desc":"plain","desc_v2":[],"ugc_season":{"id":0}}}""")
                "10" -> json("""{"code":0}""")
                else -> null
            }
        }
    ) { _, client ->
        val sparse = client.getVideoBasicInfoByAvid(9L)
        assertEquals("plain", sparse.desc)
        assertEquals("", sparse.coverUrl)
        assertEquals("", sparse.ownerFace)
        assertEquals(VideoStats(0, 0, 0, 0, 0, 0, 0), sparse.stats)
        assertEquals(emptyList<VideoPage>(), sparse.pages)
        assertNull(sparse.ugcSeason)

        val missing = client.getVideoStatsByAvid(10L)
        assertEquals(VideoStats(0, 0, 0, 0, 0, 0, 0), missing)
    }

    @Test
    fun `stats by bvid read the view endpoint`() = biliClientTest(
        route = { request -> json(FULL_VIEW).takeIf { request.url.encodedPath == VIEW_PATH } }
    ) { _, client ->
        assertEquals(7L, client.getVideoStatsByBvid("BV17x411w7KC").like)
    }

    @Test
    fun `search keeps only video results and parses durations and optional counters`() = biliClientTest(
        route = { request -> json(SEARCH_RESULT).takeIf { request.url.encodedPath == SEARCH_PATH } }
    ) { http, client ->
        val page = client.searchVideos(keyword = "lofi (live)*!", page = 2, order = "pubdate", duration = 2, tids = 3)

        val request = http.requestsTo(SEARCH_PATH).single()
        val url = request.url
        assertEquals("video", url.queryParameter("search_type"))
        assertEquals("lofi live", url.queryParameter("keyword"))
        assertEquals("pubdate", url.queryParameter("order"))
        assertEquals("2", url.queryParameter("duration"))
        assertEquals("3", url.queryParameter("tids"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals(expectedWbiSignature(request), url.queryParameter("w_rid"))

        assertEquals(2, page.page)
        assertEquals(20, page.pageSize)
        assertEquals(1000, page.numResults)
        assertEquals(50, page.numPages)
        assertEquals(
            listOf(
                SearchVideoItem(
                    aid = 1L, bvid = "BV1", titleHtml = "<em class=\"keyword\">lofi</em> mix", titlePlain = "lofi mix",
                    author = "author", mid = 2L, coverUrl = "https://i0.hdslb.com/x.jpg", durationSec = 205,
                    play = 100L, pubdate = 1_700_000_000L
                ),
                SearchVideoItem(
                    aid = 3L, bvid = "BV3", titleHtml = "long", titlePlain = "long", author = "", mid = 0L,
                    coverUrl = "", durationSec = 3723, play = null, pubdate = null
                ),
                SearchVideoItem(
                    aid = 4L, bvid = "BV4", titleHtml = "unknown", titlePlain = "unknown", author = "", mid = 0L,
                    coverUrl = "", durationSec = 0, play = null, pubdate = null
                ),
                SearchVideoItem(
                    aid = 5L, bvid = "BV5", titleHtml = "blank", titlePlain = "blank", author = "", mid = 0L,
                    coverUrl = "", durationSec = 0, play = null, pubdate = null
                )
            ),
            page.items
        )
    }

    @Test
    fun `search without data falls back to the requested page`() = biliClientTest(
        route = { request -> json("""{"code":0}""").takeIf { request.url.encodedPath == SEARCH_PATH } }
    ) { _, client ->
        val page = client.searchVideos(keyword = "nothing", page = 3)

        assertEquals(3, page.page)
        assertEquals(0, page.pageSize)
        assertEquals(0, page.numResults)
        assertEquals(1, page.numPages)
        assertEquals(emptyList<SearchVideoItem>(), page.items)
    }

    @Test
    fun `page list is read by bvid or aid and skips malformed entries`() = biliClientTest(
        route = { request ->
            when {
                request.url.encodedPath != PAGE_LIST_PATH -> null
                request.url.queryParameter("bvid") == "BV17x411w7KC" -> json(
                    """
                    {"code":0,"data":[
                      {"cid":101,"page":1,"part":"P1","duration":120,"dimension":{"width":1280,"height":720}},
                      null,
                      {"cid":102,"page":2,"part":"P2","duration":30}
                    ]}
                    """.trimIndent()
                )
                request.url.queryParameter("aid") == "170001" -> json("""{"code":-404}""")
                else -> null
            }
        }
    ) { http, client ->
        assertEquals(
            listOf(
                VideoPage(cid = 101L, page = 1, part = "P1", durationSec = 120, width = 1280, height = 720),
                VideoPage(cid = 102L, page = 2, part = "P2", durationSec = 30, width = 0, height = 0)
            ),
            client.getVideoPageList("BV17x411w7KC")
        )
        assertEquals(emptyList<VideoPage>(), client.getVideoPageList(170001L))
        assertNull(http.requestsTo(PAGE_LIST_PATH).first().url.queryParameter("w_rid"))
    }

    private companion object {
        const val VIEW_PATH = "/x/web-interface/wbi/view"
        const val SEARCH_PATH = "/x/web-interface/wbi/search/type"
        const val PAGE_LIST_PATH = "/x/player/pagelist"

        val FULL_VIEW = """
            {"code":0,"data":{
              "aid":170001,"bvid":"BV17x411w7KC","title":"测试视频",
              "pic":"//i0.hdslb.com/bfs/archive/cover.jpg",
              "desc":"旧简介",
              "desc_v2":[{"raw_text":"第一段"},3,{"raw_text":"  "},{"raw_text":"第二段"}],
              "duration":245,
              "owner":{"mid":12,"name":"UP","face":"https://i1.hdslb.com/bfs/face/up.jpg"},
              "stat":{"view":1,"danmaku":2,"reply":3,"favorite":4,"coin":5,"share":6,"like":7},
              "pages":[
                {"cid":101,"page":1,"part":"P1","duration":120,"dimension":{"width":1920,"height":1080}},
                "bad",
                {"cid":102,"page":2,"part":"P2","duration":125}
              ],
              "ugc_season":{"season_id":555,"mid":12,"title":"合集"}
            }}
        """.trimIndent()

        val SEARCH_RESULT = """
            {"code":0,"data":{"page":2,"pagesize":20,"numResults":1000,"numPages":50,"result":[
              {"type":"video","aid":1,"bvid":"BV1","title":"<em class=\"keyword\">lofi</em> mix","author":"author",
               "mid":2,"pic":"//i0.hdslb.com/x.jpg","duration":"3:25","play":100,"pubdate":1700000000},
              {"type":"ketang","aid":2,"bvid":"BV2","title":"course"},
              5,
              {"type":"video","aid":3,"bvid":"BV3","title":"long","duration":"1:02:03"},
              {"type":"video","aid":4,"bvid":"BV4","title":"unknown","duration":"--:--"},
              {"type":"video","aid":5,"bvid":"BV5","title":"blank","duration":""}
            ]}}
        """.trimIndent()
    }
}
