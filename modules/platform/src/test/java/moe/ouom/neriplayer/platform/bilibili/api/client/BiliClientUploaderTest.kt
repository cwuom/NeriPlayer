package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliClientUploaderTest {
    @Test
    fun `uploader profile is signed and parsed from successful space data`() = biliClientTest(
        route = { request ->
            json(
                """{"code":0,"data":{"mid":42,"name":"UP","face":"//i0.hdslb.com/f.jpg","sign":"签名","top_photo":"http://i0.hdslb.com/t.jpg"}}"""
            ).takeIf { request.url.encodedPath == ACC_INFO_PATH }
        }
    ) { http, client ->
        val profile = client.getUploaderProfile(42L)

        val request = http.requestsTo(ACC_INFO_PATH).single()
        assertEquals("42", request.url.queryParameter("mid"))
        assertEquals("web", request.url.queryParameter("platform"))
        assertEquals("1550101", request.url.queryParameter("web_location"))
        assertEquals(expectedWbiSignature(request), request.url.queryParameter("w_rid"))
        assertEquals(
            UploaderProfile(42L, "UP", "https://i0.hdslb.com/f.jpg", "签名", "https://i0.hdslb.com/t.jpg"),
            profile
        )
    }

    @Test
    fun `space api failures report message or msg and invalid ids skip the network`() = biliClientTest(
        route = { request ->
            when (request.url.encodedPath) {
                ACC_INFO_PATH -> json("""{"code":-352,"message":"","msg":"风控校验失败"}""")
                ARCHIVES_PATH -> json("""{"code":-400,"message":"请求错误"}""")
                else -> null
            }
        }
    ) { http, client ->
        val profileError = assertThrows(IOException::class.java) { runBlocking { client.getUploaderProfile(42L) } }
        assertEquals("Bili uploader profile failed: code=-352, message=风控校验失败", profileError.message)
        val videosError = assertThrows(IOException::class.java) { runBlocking { client.getUploaderVideos(42L) } }
        assertEquals("Bili uploader videos failed: code=-400, message=请求错误", videosError.message)

        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.getUploaderProfile(0L) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.getUploaderVideos(-1L) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.getUploaderContents(0L) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.getSeriesArchives(0L, 7L) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.getSeriesArchives(42L, 0L) } }
        assertEquals(2, http.requests.count { it.url.encodedPath != NAV_PATH })
    }

    @Test
    fun `uploader videos and contents clamp paging before requesting`() = biliClientTest(
        route = { request ->
            when (request.url.encodedPath) {
                ARCHIVES_PATH -> json(
                    """
                    {"code":0,"data":{"page":{"pn":1,"ps":30,"count":31},"list":{"vlist":[
                      {"aid":1,"bvid":"BV1","title":"first","length":"02:05","created":1700000000}
                    ]}}}
                    """.trimIndent()
                )
                SEASONS_SERIES_PATH -> json(
                    """
                    {"code":0,"data":{"items_lists":{"page":{"page_num":2,"page_size":20,"total":41},
                      "seasons_list":[{"meta":{"season_id":10,"name":"合集"}}],
                      "series_list":[{"meta":{"series_id":20,"name":"系列"}}]}}}
                    """.trimIndent()
                )
                else -> null
            }
        }
    ) { http, client ->
        val videos = client.getUploaderVideos(mid = 42L, page = 0, pageSize = 100, order = "click")
        val contents = client.getUploaderContents(mid = 42L, page = 2, pageSize = 50)

        val videoUrl = http.requestsTo(ARCHIVES_PATH).single().url
        assertEquals("1", videoUrl.queryParameter("pn"))
        assertEquals("30", videoUrl.queryParameter("ps"))
        assertEquals("click", videoUrl.queryParameter("order"))
        assertEquals(listOf("BV1"), videos.items.map { it.bvid })
        assertEquals(125, videos.items.single().durationSec)
        assertTrue(videos.hasMore)

        val contentUrl = http.requestsTo(SEASONS_SERIES_PATH).single().url
        assertEquals("2", contentUrl.queryParameter("page_num"))
        assertEquals("20", contentUrl.queryParameter("page_size"))
        assertEquals("333.999", contentUrl.queryParameter("web_location"))
        assertEquals(listOf(10L), contents.collections.map { it.id })
        assertEquals(listOf(20L), contents.series.map { it.id })
        assertTrue(contents.hasMore)
    }

    @Test
    fun `series archives use the unsigned endpoint and merge remaining pages without duplicates`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != SERIES_PATH) {
                null
            } else when (request.url.queryParameter("pn")) {
                "1" -> json(seriesPage(num = 1, total = 35, "BV1", "BV2"))
                "2" -> json(seriesPage(num = 2, total = 35, "BV2", "BV3"))
                else -> null
            }
        }
    ) { http, client ->
        val items = client.getAllSeriesArchives(mid = 42L, seriesId = 7L)

        assertEquals(listOf("BV1", "BV2", "BV3"), items.map { it.bvid })
        val firstUrl = http.requestsTo(SERIES_PATH).first { it.url.queryParameter("pn") == "1" }.url
        assertEquals("42", firstUrl.queryParameter("mid"))
        assertEquals("7", firstUrl.queryParameter("series_id"))
        assertEquals("true", firstUrl.queryParameter("only_normal"))
        assertEquals("desc", firstUrl.queryParameter("sort"))
        assertEquals("30", firstUrl.queryParameter("ps"))
        assertNull(firstUrl.queryParameter("w_rid"))
        assertTrue(http.requestsTo(NAV_PATH).isEmpty())
    }

    @Test
    fun `single page series archives stop after the first request`() = biliClientTest(
        route = { request -> json(seriesPage(num = 1, total = 2, "BV1", "BV2")).takeIf { request.url.encodedPath == SERIES_PATH } }
    ) { http, client ->
        val page = client.getSeriesArchives(mid = 42L, seriesId = 7L, page = -3, pageSize = 99, sort = "asc")
        assertFalse(page.hasMore)
        assertEquals("1", http.requestsTo(SERIES_PATH).single().url.queryParameter("pn"))
        assertEquals("asc", http.requestsTo(SERIES_PATH).single().url.queryParameter("sort"))

        assertEquals(listOf("BV1", "BV2"), client.getAllSeriesArchives(mid = 42L, seriesId = 7L).map { it.bvid })
        assertEquals(2, http.requestsTo(SERIES_PATH).size)
    }

    @Test
    fun `recent like requires a successful response with data one`() = biliClientTest(
        route = { request ->
            if (request.url.encodedPath != HAS_LIKE_PATH) {
                null
            } else when (request.url.queryParameter("bvid") ?: request.url.queryParameter("aid")) {
                "BV1liked" -> json("""{"code":0,"message":"0","data":1}""")
                "BV1unliked" -> json("""{"code":0,"message":"0","data":0}""")
                "170001" -> json("""{"code":-101,"message":"账号未登录","data":1}""")
                else -> null
            }
        }
    ) { http, client ->
        assertTrue(client.hasLikedRecentlyByBvid("BV1liked"))
        assertFalse(client.hasLikedRecentlyByBvid("BV1unliked"))
        assertFalse(client.hasLikedRecentlyByAvid(170001L))
        assertEquals("170001", http.requestsTo(HAS_LIKE_PATH).last().url.queryParameter("aid"))
    }

    private fun seriesPage(num: Int, total: Int, vararg bvids: String): String {
        val archives = bvids.joinToString(",") { bvid ->
            """{"aid":${bvid.removePrefix("BV")},"bvid":"$bvid","title":"title $bvid","pic":"//i0.hdslb.com/$bvid.jpg"}"""
        }
        return """{"code":0,"data":{"archives":[$archives],"page":{"num":$num,"size":30,"total":$total}}}"""
    }

    private companion object {
        const val ACC_INFO_PATH = "/x/space/wbi/acc/info"
        const val ARCHIVES_PATH = "/x/space/wbi/arc/search"
        const val SEASONS_SERIES_PATH = "/x/polymer/web-space/seasons_series_list"
        const val SERIES_PATH = "/x/series/archives"
        const val HAS_LIKE_PATH = "/x/web-interface/archive/has/like"
    }
}
