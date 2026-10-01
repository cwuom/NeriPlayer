package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONException

class NeteaseSongMappingTest {
    @Test
    fun `mixed song arrays preserve valid order and retain business field validation`() {
        val songs = parseNeteaseSearchSongs(
            """{"code":200,"result":{"songs":[
                null, 7, "text", [],
                {"id":0,"name":"invalid id"},
                {"id":9,"name":"   "},
                {"id":2,"name":"second"},
                {"id":1,"name":"first"}
            ]}}"""
        )

        assertEquals(listOf(2L, 1L), songs.map { it.id })
        assertEquals(listOf("second", "first"), songs.map { it.name })
    }

    @Test
    fun `search parser preserves album id and netease source metadata`() {
        val raw = """
            {
              "code": 200,
              "result": {
                "songs": [
                  {
                    "id": 7,
                    "name": "Demo Song",
                    "dt": 1234,
                    "ar": [{ "id": 8, "name": "Demo Artist" }],
                    "al": {
                      "id": 99,
                      "name": "Demo Album",
                      "picUrl": "http://example.test/cover.jpg"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val song = parseNeteaseSearchSongs(raw).single()

        assertEquals(99L, song.albumId)
        assertEquals("Demo Album", song.album)
        assertEquals("https://example.test/cover.jpg", song.coverUrl)
        assertEquals("netease", song.channelId)
        assertEquals("7", song.audioId)
    }

    @Test
    fun unsuccessfulOrMissingSongResponsesReturnNoModels() {
        listOf(
            """{}""",
            """{"code":500}""",
            """{"code":200}""",
            """{"code":200,"result":{}}""",
            """{"code":200,"result":{"songs":null},"songs":null}""",
            """{"code":200,"result":{"songs":[]},"songs":[]}"""
        ).forEach { raw ->
            assertTrue(parseNeteaseSearchSongs(raw).isEmpty())
            assertNull(parseNeteaseSongDetail(raw))
        }
    }

    @Test
    fun songWithoutAlbumKeepsDefaultsAndLiteralSongName() {
        val song = parseNeteaseSongDetail(
            """{"code":200,"songs":[{"id":7,"name":" title ","duration":1234}]}"""
        )!!

        assertEquals(" title ", song.name)
        assertEquals("", song.album)
        assertEquals(0L, song.albumId)
        assertNull(song.coverUrl)
        assertEquals(0L, song.durationMs)
        assertEquals("", song.artist)
        assertEquals(emptyList<NeteaseArtistSummary>(), song.neteaseArtists)
    }

    @Test
    fun legacyArtistAndAlbumFieldsKeepFallbackAndTrimming() {
        val song = parseNeteaseSongDetail(
            """{"code":200,"songs":[{
                "id":7,"name":"song","dt":1234,
                "ar":[{"id":0,"name":"invalid"}],
                "artists":[{"id":8,"name":" artist "}],
                "album":{"id":9,"name":"legacy","picUrl":"http://cover"}
            }]}"""
        )!!

        assertEquals("artist", song.artist)
        assertEquals(listOf(8L), song.neteaseArtists.orEmpty().map { it.id })
        assertEquals("legacy", song.album)
        assertEquals(9L, song.albumId)
        assertEquals("https://cover", song.coverUrl)
        assertEquals(1234L, song.durationMs)
    }

    @Test
    fun songDetailChoosesFirstValidSongAfterInvalidMembers() {
        val song = parseNeteaseSongDetail(
            """{"code":200,"songs":[null,7,
                {"id":0,"name":"invalid"},{"id":-1,"name":"negative"},{"id":8,"name":" "},
                {"id":9,"name":"first"},{"id":10,"name":"second"}
            ]}"""
        )!!

        assertEquals(9L, song.id)
        assertEquals("first", song.name)
    }

    @Test
    fun artistDetailKeepsFirstSlotWithoutRequiringSongIdentity() {
        val raw = """{"code":200,"songs":[
            {"ar":[{"id":1,"name":"first-slot"}]},
            {"id":9,"name":"valid-song","ar":[{"id":2,"name":"valid-song-artist"}]}
        ]}"""

        assertEquals(listOf(1L), parseNeteaseArtistsFromSongDetail(raw).map { it.id })
        val song = parseNeteaseSongDetail(raw)!!
        assertEquals(9L, song.id)
        assertEquals(listOf(2L), song.neteaseArtists.orEmpty().map { it.id })
    }

    @Test
    fun artistDetailDoesNotSkipInvalidFirstSlot() {
        val raw = """{"code":200,"songs":[null,
            {"id":9,"name":"valid-song","ar":[{"id":2,"name":"artist"}]}
        ]}"""

        assertTrue(parseNeteaseArtistsFromSongDetail(raw).isEmpty())
        assertEquals(9L, parseNeteaseSongDetail(raw)!!.id)
    }

    @Test
    fun malformedResponsesKeepJsonFailureClassification() {
        assertThrows(JSONException::class.java) { parseNeteaseSearchSongs("{") }
        assertThrows(JSONException::class.java) { parseNeteaseSongDetail("{") }
        assertThrows(JSONException::class.java) { parseNeteaseArtistsFromSongDetail("{") }
    }

    @Test
    fun blankOrAlternateAlbumCoverFieldsDoNotCreateCoverUrls() {
        val songs = parseNeteaseSearchSongs(
            """{"code":200,"result":{"songs":[
                {"id":1,"name":"empty","al":{"picUrl":""}},
                {"id":2,"name":"blank","al":{"picUrl":" "}},
                {"id":3,"name":"alternate","al":{"picUrl_str":"http://alternate"}}
            ]}}"""
        )

        assertEquals(listOf(1L, 2L, 3L), songs.map { it.id })
        assertEquals(listOf(null, null, null), songs.map { it.coverUrl })
    }

    @Test
    fun existingModernAlbumDoesNotMergeLegacyAlbumFields() {
        val song = parseNeteaseSongDetail(
            """{"code":200,"songs":[{
                "id":7,"name":"song","al":{},
                "album":{"id":9,"name":"legacy","picUrl":"http://legacy"}
            }]}"""
        )!!

        assertEquals("", song.album)
        assertEquals(0L, song.albumId)
        assertNull(song.coverUrl)
    }

}
