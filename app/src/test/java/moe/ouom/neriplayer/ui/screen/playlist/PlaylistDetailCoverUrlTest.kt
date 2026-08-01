package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCollectionHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDetailCoverUrlTest {
    @Test
    fun `header cover wins when present`() {
        assertEquals(
            "https://example.com/header.jpg",
            resolvePlaylistDetailCoverUrl(
                headerCoverUrl = "https://example.com/header.jpg",
                fallbackCoverUrl = "https://example.com/fallback.jpg"
            )
        )
    }

    @Test
    fun `fallback cover is used when header cover is blank`() {
        assertEquals(
            "https://example.com/fallback.jpg",
            resolvePlaylistDetailCoverUrl(
                headerCoverUrl = "   ",
                fallbackCoverUrl = "https://example.com/fallback.jpg"
            )
        )
    }

    @Test
    fun `fallback cover is used when header cover is null`() {
        assertEquals(
            "https://example.com/fallback.jpg",
            resolvePlaylistDetailCoverUrl(
                headerCoverUrl = null,
                fallbackCoverUrl = "https://example.com/fallback.jpg"
            )
        )
    }

    @Test
    fun `blank values resolve to null`() {
        assertEquals(
            null,
            resolvePlaylistDetailCoverUrl(
                headerCoverUrl = " ",
                fallbackCoverUrl = "\t"
            )
        )
    }

    @Test
    fun `netease http cover is canonicalized before display`() {
        assertEquals(
            "https://p1.music.126.net/cover.jpg?param=140y140",
            resolvePlaylistDetailCoverUrl(
                headerCoverUrl = "http://p1.music.126.net/cover.jpg?param=140y140",
                fallbackCoverUrl = null
            )
        )
    }

    @Test
    fun `stale playlist header does not match another route`() {
        val header = NeteaseCollectionHeader(
            id = 12L,
            isAlbum = false,
            name = "Playlist",
            coverUrl = "https://example.com/cover.jpg",
            playCount = 0L,
            trackCount = 0
        )

        assertTrue(isNeteaseCollectionHeaderForRoute(header, 12L, "netease"))
        assertFalse(isNeteaseCollectionHeaderForRoute(header, 12L, "neteaseAlbum"))
        assertFalse(isNeteaseCollectionHeaderForRoute(header, 13L, "netease"))
    }
}
