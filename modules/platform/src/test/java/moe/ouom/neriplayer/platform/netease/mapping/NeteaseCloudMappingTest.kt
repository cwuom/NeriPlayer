package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCloudMappingTest {

    @Test
    fun `cloud page converts simple songs into playable netease tracks`() {
        val raw = """
            {
              "code": 200,
              "count": 3,
              "size": 1024,
              "maxSize": 2048,
              "hasMore": true,
              "data": [
                {
                  "songId": 11,
                  "fileName": "upload.mp3",
                  "fileSize": 100,
                  "cover": "http://cover.test/item.jpg",
                  "simpleSong": {
                    "id": 11,
                    "name": "Cloud Song",
                    "dt": 1234,
                    "ar": [{"id": 7, "name": "Singer"}],
                    "al": {"id": 99, "name": "Cloud Album", "picUrl": "http://cover.test/album.jpg"}
                  }
                },
                {
                  "songId": 12,
                  "fileName": "fallback.flac",
                  "songName": "Fallback Song",
                  "artist": "Fallback Artist",
                  "album": "Fallback Album",
                  "duration": 4321,
                  "cover": "http://cover.test/fallback.jpg"
                }
              ]
            }
        """.trimIndent()

        val page = parseNeteaseCloudPage(raw, offset = 0)

        assertEquals(3, page.totalCount)
        assertEquals(2, page.consumed)
        assertEquals(1024L, page.usedBytes)
        assertEquals(2048L, page.maxBytes)
        assertTrue(page.hasMore)
        assertEquals(listOf(11L, 12L), page.songs.map { it.id })

        val cloudSong = page.songs[0]
        assertEquals("Cloud Song", cloudSong.name)
        assertEquals("Singer", cloudSong.artist)
        assertEquals("NeteaseCloud Album", cloudSong.album)
        assertEquals(99L, cloudSong.albumId)
        assertEquals(1234L, cloudSong.durationMs)
        assertEquals("https://cover.test/album.jpg?param=300y300", cloudSong.coverUrl)
        assertEquals("https://cover.test/album.jpg?param=1024y1024", cloudSong.originalCoverUrl)
        assertEquals("netease", cloudSong.channelId)
        assertEquals("11", cloudSong.audioId)
        assertEquals(listOf(7L), cloudSong.neteaseArtists.orEmpty().map { it.id })

        val fallbackSong = page.songs[1]
        assertEquals("Fallback Song", fallbackSong.name)
        assertEquals("Fallback Artist", fallbackSong.artist)
        assertEquals("NeteaseFallback Album", fallbackSong.album)
        assertEquals(0L, fallbackSong.albumId)
        assertEquals(4321L, fallbackSong.durationMs)
        assertEquals("https://cover.test/fallback.jpg?param=300y300", fallbackSong.coverUrl)
        assertEquals(emptyList<NeteaseArtistSummary>(), fallbackSong.neteaseArtists)
    }

    @Test
    fun `cloud page ignores null and non-string metadata instead of literal null`() {
        val raw = """
            {
              "code": 200,
              "count": 4,
              "data": [
                {
                  "songId": 21,
                  "fileName": null,
                  "songName": null,
                  "artist": null,
                  "album": null,
                  "cover": 0,
                  "simpleSong": {"id": 21, "name": null}
                },
                {
                  "songId": 22,
                  "fileName": "plain.mp3",
                  "songName": null,
                  "artist": null,
                  "album": null,
                  "cover": "https://cover.test/plain.jpg?v=1"
                },
                {
                  "songId": 23,
                  "fileName": null,
                  "songName": null
                },
                {
                  "songId": 24,
                  "fileName": "no-cover.mp3",
                  "artist": null,
                  "album": null,
                  "cover": 0
                }
              ]
            }
        """.trimIndent()

        val page = parseNeteaseCloudPage(raw, offset = 0)

        assertEquals(4, page.consumed)
        assertEquals(listOf(22L, 24L), page.songs.map { it.id })

        val plain = page.songs[0]
        assertEquals("plain", plain.name)
        assertEquals("", plain.artist)
        assertEquals("Netease", plain.album)
        assertEquals("https://cover.test/plain.jpg?v=1&param=300y300", plain.coverUrl)
        assertEquals("https://cover.test/plain.jpg?v=1&param=1024y1024", plain.originalCoverUrl)

        val noCover = page.songs[1]
        assertEquals("no-cover", noCover.name)
        assertNull(noCover.coverUrl)
        assertNull(noCover.originalCoverUrl)
    }

    @Test
    fun `cloud page falls back to file name and skips unusable items`() {
        val raw = """
            {
              "code": 200,
              "count": 4,
              "hasMore": false,
              "data": [
                null,
                7,
                {"songId": 0, "fileName": "invalid.mp3"},
                {"songId": 13, "fileName": "  Untitled.mp3  "},
                {"songId": 14, "fileName": "no name.mp3", "simpleSong": null}
              ]
            }
        """.trimIndent()

        val page = parseNeteaseCloudPage(raw, offset = 0)

        assertEquals(5, page.consumed)
        assertEquals(listOf(13L, 14L), page.songs.map { it.id })
        assertEquals(listOf("Untitled", "no name"), page.songs.map { it.name })
        assertEquals(listOf("Netease", "Netease"), page.songs.map { it.album })
        assertNull(page.songs[0].coverUrl)
        assertNull(page.songs[0].artist.takeIf { it.isNotBlank() })
    }

    @Test
    fun `cloud page derives has more from count when server omits it`() {
        val page = parseNeteaseCloudPage(
            """{"code":200,"count":5,"data":[{"songId":1,"fileName":"a.mp3"},{"songId":2,"fileName":"b.mp3"}]}""",
            offset = 0
        )
        assertTrue(page.hasMore)

        val lastPage = parseNeteaseCloudPage(
            """{"code":200,"count":5,"data":[{"songId":5,"fileName":"e.mp3"}]}""",
            offset = 4
        )
        assertFalse(lastPage.hasMore)
    }

    @Test
    fun `cloud page trusts explicit has more rather than count`() {
        val page = parseNeteaseCloudPage(
            """{"code":200,"count":100,"hasMore":false,"data":[{"songId":1,"fileName":"a.mp3"}]}""",
            offset = 0
        )

        assertFalse(page.hasMore)
    }

    @Test
    fun `cloud page stops paging when server returns no items`() {
        val page = parseNeteaseCloudPage(
            """{"code":200,"count":100,"data":[]}""",
            offset = 200
        )

        assertFalse(page.hasMore)
        assertTrue(page.songs.isEmpty())
        assertEquals(0, page.consumed)
    }

    @Test
    fun `cloud page rejects unsuccessful responses`() {
        assertThrows(NeteaseCloudAuthRequiredException::class.java) {
            parseNeteaseCloudPage("""{"code":301,"message":"need login"}""", offset = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseNeteaseCloudPage("""{}""", offset = 0)
        }
    }

    @Test
    fun `cloud page keeps json failure classification`() {
        assertThrows(JSONException::class.java) {
            parseNeteaseCloudPage("{", offset = 0)
        }
    }
}
