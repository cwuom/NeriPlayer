package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistHeader

class NeteaseCollectionDetailViewModelTest {

    @Test
    fun `album cover fallback fills blank track cover`() {
        val resolved = resolveNeteaseCollectionCoverUrl(
            primary = "",
            fallback = "http://example.com/album.jpg"
        )

        assertEquals("https://example.com/album.jpg", resolved)
    }

    @Test
    fun `track cover wins over album fallback`() {
        val resolved = resolveNeteaseCollectionCoverUrl(
            primary = "http://example.com/track.jpg",
            fallback = "https://example.com/album.jpg"
        )

        assertEquals("https://example.com/track.jpg", resolved)
    }

    @Test
    fun `missing covers stay blank`() {
        val resolved = resolveNeteaseCollectionCoverUrl(
            primary = "   ",
            fallback = null
        )

        assertEquals("", resolved)
    }

    @Test
    fun `radar cache refresh keeps tracks but adopts account header`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 5_327_906_368L,
            header = CachedNeteasePlaylistHeader(
                id = 5_327_906_368L,
                name = "乐迷雷达",
                coverUrl = "https://example.com/visitor.jpg",
                playCount = 1L,
                trackCount = 30
            ),
            recentTrackSignature = "30#0:1|",
            tracks = emptyList()
        )
        val refreshed = refreshNeteasePlaylistCachedHeader(
            cached = cached,
            fresh = NeteaseCollectionHeader(
                id = 5_327_906_368L,
                isAlbum = false,
                name = "为你定制的乐迷雷达",
                coverUrl = "https://example.com/account.jpg",
                playCount = 42L,
                trackCount = 30
            )
        )

        assertEquals("为你定制的乐迷雷达", refreshed.header.name)
        assertEquals("https://example.com/account.jpg", refreshed.header.coverUrl)
        assertEquals(cached.tracks, refreshed.tracks)
        assertEquals(cached.recentTrackSignature, refreshed.recentTrackSignature)
    }
}
