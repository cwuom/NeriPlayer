package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseSearchSongParserTest {

    @Test
    fun `playlist search parser returns summaries and total count`() {
        val raw = """
            {
              "code": 200,
              "result": {
                "playlistCount": 31,
                "playlists": [
                  {
                    "id": 44,
                    "name": "Demo Playlist",
                    "coverImgUrl": "http://example.test/playlist.jpg",
                    "playCount": 12345,
                    "trackCount": 20
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = parseNeteaseSearchResults(raw, NeteaseExploreSearchType.PLAYLIST)
        val playlist = (parsed.items.single() as ExploreSearchResult.Playlist).playlist

        assertEquals(31, parsed.totalCount)
        assertEquals(44L, playlist.id)
        assertEquals("Demo Playlist", playlist.name)
        assertEquals("https://example.test/playlist.jpg", playlist.picUrl)
        assertEquals(12345L, playlist.playCount)
        assertEquals(20, playlist.trackCount)
    }

    @Test
    fun `artist search parser returns artist metadata and total count`() {
        val raw = """
            {
              "code": 200,
              "result": {
                "artistCount": 9,
                "artists": [
                  {
                    "id": 55,
                    "name": "Demo Artist",
                    "picUrl": "http://example.test/artist.jpg",
                    "musicSize": 18,
                    "albumSize": 4
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = parseNeteaseSearchResults(raw, NeteaseExploreSearchType.ARTIST)
        val artist = (parsed.items.single() as ExploreSearchResult.Artist).result

        assertEquals(9, parsed.totalCount)
        assertEquals(55L, artist.artist.id)
        assertEquals("Demo Artist", artist.artist.name)
        assertEquals("https://example.test/artist.jpg", artist.picUrl)
        assertEquals(18, artist.musicSize)
        assertEquals(4, artist.albumSize)
    }

    @Test
    fun `non success response returns no search results`() {
        val parsed = parseNeteaseSearchResults(
            raw = """{"code":500}""",
            type = NeteaseExploreSearchType.SONG
        )

        assertTrue(parsed.items.isEmpty())
        assertEquals(null, parsed.totalCount)
    }
}
