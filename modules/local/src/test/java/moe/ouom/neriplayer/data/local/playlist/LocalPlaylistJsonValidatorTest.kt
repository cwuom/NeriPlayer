package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPlaylistJsonValidatorTest {
    @Test
    fun `complete playlists pass validation`() {
        validateLocalPlaylistJson("[]", "main")
        validateLocalPlaylistJson(playlists(playlist(songs = listOf(song(), song()))), "main")
    }

    @Test
    fun `root and entry shapes are reported with the playlist source`() {
        assertEquals("Playlist main root must be an array", failure("{}"))
        assertEquals("Playlist main contains a non-object entry", failure("[1]"))
        assertEquals("Playlist main contains an invalid song entry", failure("""[{"id":1,"name":"P","songs":[1]}]"""))
    }

    @Test
    fun `every required playlist field is checked by name and type`() {
        assertEquals("Playlist main entry is missing numeric id", failure(playlists(playlist().without("id"))))
        assertEquals("Playlist main entry is missing numeric id", failure(playlists(playlist().with("id", "1"))))
        assertEquals("Playlist main entry is missing name", failure(playlists(playlist().with("name", 1))))
        assertEquals("Playlist main entry is missing songs", failure(playlists(playlist().without("songs"))))
        assertEquals("Playlist main entry is missing songs", failure(playlists(playlist().with("songs", "none"))))
    }

    @Test
    fun `every required song field is checked by name and type`() {
        val expected = mapOf(
            "id" to "missing numeric id",
            "name" to "missing name",
            "artist" to "missing artist",
            "album" to "missing album",
            "albumId" to "missing numeric albumId",
            "durationMs" to "missing numeric durationMs"
        )
        expected.forEach { (field, problem) ->
            assertEquals("Playlist main song is $problem", failure(playlists(playlist(songs = listOf(song().without(field))))))
            val wrongType = if (problem.contains("numeric")) song().with(field, "1") else song().with(field, 1)
            assertEquals("Playlist main song is $problem", failure(playlists(playlist(songs = listOf(wrongType)))))
        }
    }

    @Test
    fun `non primitive field values fail as malformed json`() {
        val nested = playlist().apply { add("id", JsonObject()) }

        assertThrows(IllegalStateException::class.java) { validateLocalPlaylistJson(playlists(nested), "main") }
    }

    private fun failure(text: String): String? =
        assertThrows(IllegalArgumentException::class.java) { validateLocalPlaylistJson(text, "main") }.message

    private fun playlists(vararg entries: JsonObject): String = JsonArray().apply { entries.forEach(::add) }.toString()

    private fun playlist(songs: List<JsonObject> = emptyList()) = JsonObject().apply {
        addProperty("id", 1)
        addProperty("name", "Playlist")
        add("songs", JsonArray().apply { songs.forEach(::add) })
    }

    private fun song() = JsonParser.parseString(
        """{"id":2,"name":"Song","artist":"Artist","album":"Album","albumId":0,"durationMs":1000}"""
    ).asJsonObject

    private fun JsonObject.without(field: String) = apply { remove(field) }

    private fun JsonObject.with(field: String, value: Any) = apply {
        when (value) {
            is Number -> addProperty(field, value)
            else -> addProperty(field, value.toString())
        }
    }
}
