package moe.ouom.neriplayer.platform.bilibili.api.client

import java.io.IOException
import moe.ouom.neriplayer.data.model.bilibili.uploader.UploaderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliClientExploreSearchTest {

    @Test
    fun musicUsesMusicRegionAndVideoUsesAllRegions() = biliClientTest(
        route = { request ->
            json(
                """
                {"code":0,"data":{"page":2,"numPages":3,"result":[
                  {"type":"video","aid":170001,"title":"<em>Mili</em> &amp; Co","author":"<em>UP</em>"},
                  {"type":"video","aid":0,"title":"missing identity"},
                  {"type":"video","aid":9,"title":null}
                ]}}
                """.trimIndent()
            ).takeIf { request.url.encodedPath == SEARCH_PATH }
        }
    ) { http, client ->
        val music = client.searchMusicVideos("Mili", 2)
        val video = client.searchVideos("Mili", 2)
        val requests = http.requestsTo(SEARCH_PATH)

        assertEquals(listOf("3", "0"), requests.map { it.url.queryParameter("tids") })
        assertTrue(requests.all { it.url.queryParameter("search_type") == "video" })
        assertTrue(requests.all { it.url.queryParameter("page") == "2" })
        assertEquals(2, music.page)
        assertEquals(3, music.numPages)
        val song = music.items.single()
        assertEquals("Mili & Co", song.titlePlain)
        assertEquals("UP", song.author)
        assertEquals("BV17x411w7KC", song.bvid)
        assertEquals(music, video)
    }

    @Test
    fun uploaderSearchMapsAccountIdentityAndHonorsLastPage() = biliClientTest(
        route = { request ->
            json(
                """
                {"code":0,"data":{"page":2,"numPages":2,"result":[
                  {"type":"bili_user","mid":9876543210,"uname":"<em class='keyword'>Mili</em> &amp; Co",
                   "upic":"//i0.hdslb.com/face.jpg","usign":"I <3 &lt;music&gt; &#39;live&#39; &amp;lt;"},
                  {"type":"bili_user","mid":0,"uname":"invalid"},
                  {"type":"bili_user","mid":12,"uname":null},
                  {"type":"video","mid":15,"uname":"not an account result"},null
                ]}}
                """.trimIndent()
            ).takeIf { request.url.encodedPath == SEARCH_PATH }
        }
    ) { http, client ->
        val page = client.searchUploaders("Mili", 2)
        val url = http.requestsTo(SEARCH_PATH).single().url

        assertEquals("bili_user", url.queryParameter("search_type"))
        assertEquals("Mili", url.queryParameter("keyword"))
        assertEquals("2", url.queryParameter("page"))
        assertNull(url.queryParameter("tids"))
        assertEquals(2, page.page)
        assertEquals(2, page.numPages)
        assertEquals(
            listOf(
                UploaderProfile(
                    mid = 9876543210L,
                    name = "Mili & Co",
                    faceUrl = "https://i0.hdslb.com/face.jpg",
                    sign = "I <3 <music> 'live' &lt;",
                    topPhotoUrl = ""
                )
            ),
            page.items
        )
    }

    @Test
    fun serviceErrorsRemainErrorsAcrossAllCategories() = biliClientTest(
        route = { request ->
            json("""{"code":-412,"message":"blocked"}""").takeIf { request.url.encodedPath == SEARCH_PATH }
        }
    ) { _, client ->
        val errors = listOf(
            runCatching { client.searchMusicVideos("test") }.exceptionOrNull(),
            runCatching { client.searchVideos("test") }.exceptionOrNull(),
            runCatching { client.searchUploaders("test") }.exceptionOrNull()
        )
        assertTrue(errors.all { it is IOException && it.message.orEmpty().contains("-412") })
    }

    private companion object {
        const val SEARCH_PATH = "/x/web-interface/wbi/search/type"
    }
}
