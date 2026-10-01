package moe.ouom.neriplayer.data.platform.netease.mapping

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseArtistJsonTest {

    @Test
    fun `mixed artists retain trimming and invalid identity filtering`() {
        val artists = parseNeteaseArtistSummaries(
            JSONArray("""[null, "text", [], {"id":0,"name":"invalid"},
                {"id":1,"name":"  "}, {"id":2,"name":" artist "}]""")
        )

        assertEquals(listOf(NeteaseArtistSummary(id = 2L, name = "artist")), artists)
    }

    @Test
    fun `song json falls back to artists array when ar is missing`() {
        val song = JSONObject(
            """
            {
              "id": 1,
              "name": "demo",
              "artists": [
                { "id": 10, "name": "尹美莱" },
                { "id": 11, "name": "Tiger JK" },
                { "id": 12, "name": "Bizzy" }
              ]
            }
            """.trimIndent()
        )

        val artists = parseNeteaseArtistsFromSongJson(song)

        assertEquals(
            listOf(
                NeteaseArtistSummary(id = 10L, name = "尹美莱"),
                NeteaseArtistSummary(id = 11L, name = "Tiger JK"),
                NeteaseArtistSummary(id = 12L, name = "Bizzy")
            ),
            artists
        )
    }

    @Test
    fun `song detail parser returns every artist`() {
        val raw = """
            {
              "code": 200,
              "songs": [
                {
                  "id": 1,
                  "ar": [
                    { "id": 20, "name": "A" },
                    { "id": 21, "name": "B" }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                NeteaseArtistSummary(id = 20L, name = "A"),
                NeteaseArtistSummary(id = 21L, name = "B")
            ),
            parseNeteaseArtistsFromSongDetail(raw)
        )
    }

    @Test
    fun missingArtistsAndUnsuccessfulDetailReturnEmptyLists() {
        assertTrue(parseNeteaseArtistSummaries(null).isEmpty())
        assertTrue(parseNeteaseArtistSummaries(JSONArray()).isEmpty())
        assertTrue(parseNeteaseArtistsFromSongJson(null).isEmpty())
        assertTrue(parseNeteaseArtistsFromSongJson(JSONObject()).isEmpty())
        assertTrue(parseNeteaseArtistsFromSongDetail("""{"code":500}""").isEmpty())
        assertTrue(parseNeteaseArtistsFromSongDetail("""{"code":200}""").isEmpty())
    }

    @Test
    fun validArArrayTakesPriorityOverLegacyArtists() {
        val song = JSONObject(
            """{"ar":[{"id":1,"name":"primary"}],"artists":[{"id":2,"name":"fallback"}]}"""
        )

        assertEquals(listOf(NeteaseArtistSummary(1L, "primary")), parseNeteaseArtistsFromSongJson(song))
    }

    @Test
    fun invalidArMembersFallBackToLegacyArtists() {
        val song = JSONObject(
            """{"ar":[null,{"id":0,"name":"invalid"},{"id":1,"name":" "}],
                "artists":[{"id":2,"name":" fallback "}]}"""
        )

        assertEquals(listOf(NeteaseArtistSummary(2L, "fallback")), parseNeteaseArtistsFromSongJson(song))
    }
}
