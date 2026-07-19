package moe.ouom.neriplayer.ui.viewmodel.album

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseAlbumNavigationTest {

    @Test
    fun `known album summary rejects zero album id`() {
        assertNull(buildKnownNeteaseAlbumSummary(neteaseSong(albumId = 0L)))
    }

    @Test
    fun `known album summary keeps valid album metadata`() {
        val album = buildKnownNeteaseAlbumSummary(neteaseSong(albumId = 42L))

        assertEquals(42L, album?.id)
        assertEquals("Demo Album", album?.name)
        assertEquals("https://example.test/cover.jpg", album?.picUrl)
    }

    @Test
    fun `known album summary accepts unprefixed netease metadata`() {
        val album = buildKnownNeteaseAlbumSummary(
            neteaseSong(albumId = 42L).copy(album = "Demo Album")
        )

        assertEquals(42L, album?.id)
        assertEquals("Demo Album", album?.name)
    }

    @Test
    fun `song detail parser recovers missing album id`() {
        val raw = """
            {
              "code": 200,
              "songs": [
                {
                  "id": 7,
                  "al": {
                    "id": 99,
                    "name": "Recovered Album",
                    "picUrl": "http://example.test/recovered.jpg",
                    "size": 12
                  }
                }
              ]
            }
        """.trimIndent()

        val album = parseNeteaseAlbumSummaryFromSongDetail(
            raw = raw,
            fallbackName = "NeteaseFallback",
            fallbackCoverUrl = null
        )

        assertEquals(99L, album?.id)
        assertEquals("Recovered Album", album?.name)
        assertEquals("https://example.test/recovered.jpg", album?.picUrl)
        assertEquals(12, album?.size)
    }

    @Test
    fun `song detail parser rejects missing album id`() {
        val raw = """
            {
              "code": 200,
              "songs": [{ "id": 7, "al": { "id": 0, "name": "Broken" } }]
            }
        """.trimIndent()

        assertNull(
            parseNeteaseAlbumSummaryFromSongDetail(
                raw = raw,
                fallbackName = "NeteaseFallback",
                fallbackCoverUrl = null
            )
        )
    }

    @Test
    fun `song detail lookup prefers direct netease audio id`() {
        val song = neteaseSong(albumId = 0L).copy(
            id = 1L,
            audioId = "2",
            matchedSongId = "3"
        )

        assertEquals(2L, resolveNeteaseSongDetailId(song))
    }

    @Test
    fun `song detail lookup falls back to song id when audio id is missing`() {
        val song = neteaseSong(albumId = 0L).copy(audioId = null)

        assertEquals(7L, resolveNeteaseSongDetailId(song))
    }

    @Test
    fun `non netease source does not use matched song id for album lookup`() {
        val song = neteaseSong(albumId = 0L).copy(
            channelId = "bilibili",
            album = "Bilibili",
            matchedSongId = "99"
        )

        assertNull(resolveNeteaseSongDetailId(song))
    }

    private fun neteaseSong(albumId: Long): SongItem {
        return SongItem(
            id = 7L,
            name = "Demo Song",
            artist = "Demo Artist",
            album = "NeteaseDemo Album",
            albumId = albumId,
            durationMs = 1_000L,
            coverUrl = "https://example.test/cover.jpg",
            channelId = "netease",
            audioId = "7"
        )
    }
}
