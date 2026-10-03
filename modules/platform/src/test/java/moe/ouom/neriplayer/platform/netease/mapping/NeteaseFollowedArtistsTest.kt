package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.platform.netease.api.request.buildNeteaseFollowedArtistsParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NeteaseFollowedArtistsTest {
    @Test
    fun `followed artist page retains identity artwork aliases and raw pagination count`() {
        val page = parseNeteaseFollowedArtists(
            """{"code":200,"hasMore":true,"data":[
                {"id":7,"name":" Artist ","picUrl":"http://image.test/7","musicSize":20,"alias":[" Alias ","","Alias","Second"]},
                {"id":7,"name":"Duplicate"}, {"id":0,"name":"Invalid"}, null]}"""
        )

        assertEquals(4, page.rawCount)
        assertTrue(page.hasMore)
        assertEquals(listOf(NeteaseFollowedArtist(7L, "Artist", "https://image.test/7", 20, "Alias / Second")), page.artists)
    }

    @Test
    fun `successful empty final page is distinct from a malformed response`() {
        val page = parseNeteaseFollowedArtists("""{"code":200,"hasMore":false,"data":[]}""")

        assertEquals(emptyList<NeteaseFollowedArtist>(), page.artists)
        assertEquals(0, page.rawCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun `avatar fallback and negative track count are normalized`() {
        val page = parseNeteaseFollowedArtists(
            """{"code":200,"hasMore":false,"data":[{"id":8,"name":"Artist","img1v1Url":"https://image.test/avatar","musicSize":-1}]}"""
        )

        assertEquals("https://image.test/avatar", page.artists.single().coverUrl)
        assertEquals(0, page.artists.single().musicSize)
    }

    @Test
    fun `failed or malformed responses cannot be mistaken for complete remote follows`() {
        listOf(
            """{"code":301,"message":"Login required"}""",
            """{"code":200,"hasMore":false}""",
            """{"code":200,"data":[]}""",
            """{"code":200,"hasMore":"false","data":[]}""",
            """{"code":200,"hasMore":true,"data":[]}""",
            "{"
        ).forEach { raw ->
            assertTrue(runCatching { parseNeteaseFollowedArtists(raw) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun `request parameters preserve offset and total context`() {
        assertEquals(mapOf("offset" to "50", "limit" to "25", "total" to "true"), buildNeteaseFollowedArtistsParams(50, 25))
        assertTrue(runCatching { buildNeteaseFollowedArtistsParams(-1, 50) }.isFailure)
        assertTrue(runCatching { buildNeteaseFollowedArtistsParams(0, 0) }.isFailure)
    }
}
