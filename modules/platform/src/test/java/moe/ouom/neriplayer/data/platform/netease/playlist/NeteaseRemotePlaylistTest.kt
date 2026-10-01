package moe.ouom.neriplayer.data.platform.netease.playlist

import moe.ouom.neriplayer.data.model.netease.playlist.NeteaseRemotePlaylist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class NeteaseRemotePlaylistTest {
    @Test
    fun `parse remote playlists keeps only owner playlists and de duplicates ids`() {
        val raw = """
            {
              "code": 200,
              "playlist": [
                {
                  "id": 101,
                  "name": "Daily",
                  "trackCount": 12,
                  "creator": { "userId": 7 }
                },
                {
                  "id": 102,
                  "name": "Subscribed",
                  "trackCount": 30,
                  "creator": { "userId": 8 }
                },
                {
                  "id": 101,
                  "name": "Duplicate",
                  "trackCount": 99,
                  "creator": { "userId": 7 }
                }
              ]
            }
        """.trimIndent()

        val playlists = parseNeteaseRemotePlaylists(raw, ownerUserId = 7L)

        assertEquals(
            listOf(NeteaseRemotePlaylist(id = 101L, name = "Daily", trackCount = 12)),
            playlists
        )
    }

    @Test
    fun `parse remote playlists accepts playlists fallback key`() {
        val raw = """
            {
              "code": 200,
              "playlists": [
                {
                  "id": 201,
                  "name": "Fallback",
                  "trackCount": 3,
                  "creator": { "userId": 9 }
                }
              ]
            }
        """.trimIndent()

        val playlists = parseNeteaseRemotePlaylists(raw, ownerUserId = 9L)

        assertEquals(
            listOf(NeteaseRemotePlaylist(id = 201L, name = "Fallback", trackCount = 3)),
            playlists
        )
    }

    @Test
    fun `parse remote playlists reports api errors`() {
        val error = assertThrows(IOException::class.java) {
            parseNeteaseRemotePlaylists(
                raw = """{"code":301,"msg":"login required"}""",
                ownerUserId = 9L
            )
        }

        assertTrue(error.message.orEmpty().contains("login required"))
    }

    @Test
    fun `invalid and missing playlist responses fail instead of returning an empty list`() {
        val cases = listOf(
            " " to "NetEase playlist response is empty",
            "invalid" to "Failed to parse NetEase playlist response",
            "{\"code\":500,\"msg\":\" \"}" to "NetEase playlist request failed with code 500",
            "{}" to "NetEase playlist request failed with code -1",
            "{\"code\":200}" to "NetEase playlist response is missing the playlist list"
        )
        for ((raw, message) in cases) {
            assertEquals(message, assertThrows(IOException::class.java) { parseNeteaseRemotePlaylists(raw) }.message)
        }
        assertTrue(parseNeteaseRemotePlaylists("{\"code\":200,\"playlist\":[]}").isEmpty())
    }

    @Test
    fun `valid items preserve order defaults and the first valid duplicate`() {
        val playlists = parseNeteaseRemotePlaylists(
            """{"code":200,"playlist":[null,3,{},
                {"id":0,"name":"invalid id"},
                {"id":8,"name":" "},
                {"id":8,"name":" First "},
                {"id":8,"name":"Duplicate","trackCount":99},
                {"id":2,"name":"Second","trackCount":-1,"creator":{"userId":7}}]}"""
        )

        assertEquals(
            listOf(NeteaseRemotePlaylist(8L, "First", 0), NeteaseRemotePlaylist(2L, "Second", -1)),
            playlists
        )
    }

    @Test
    fun `owner filtering retains the legacy missing creator identity`() {
        val raw = """{"code":200,"playlist":[{"id":1,"name":"missing"},{"id":2,"name":"empty","creator":{}},{"id":3,"name":"owned","creator":{"userId":7}}]}"""

        assertEquals(listOf(1L, 2L, 3L), parseNeteaseRemotePlaylists(raw).map { it.id })
        assertEquals(listOf(1L, 2L), parseNeteaseRemotePlaylists(raw, 0L).map { it.id })
        assertEquals(listOf(3L), parseNeteaseRemotePlaylists(raw, 7L).map { it.id })
    }

    @Test
    fun `primary playlist array wins over fallback even when empty`() {
        val fallback = """[{"id":1,"name":"fallback"}]"""

        assertTrue(parseNeteaseRemotePlaylists("""{"code":200,"playlist":[],"playlists":$fallback}""").isEmpty())
        assertEquals(listOf(1L), parseNeteaseRemotePlaylists("""{"code":200,"playlist":{},"playlists":$fallback}""").map { it.id })
    }
}
