package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseRadarPlaylistDefinitions
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.isNeteaseRadarPlaylist

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

    @Test
    fun `radar cache refresh uses applied MGC header`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 5_320_167_908L,
            header = CachedNeteasePlaylistHeader(
                id = 5_320_167_908L,
                name = "时光雷达",
                coverUrl = "https://example.com/visitor.jpg",
                playCount = 1L,
                trackCount = 50
            ),
            recentTrackSignature = "30#0:1|",
            tracks = emptyList()
        )
        val appliedHeader = applyNeteaseRadarPlaylistHeader(
            playlist = PlaylistSummary(
                id = 5_320_167_908L,
                name = "为你定制的时光雷达",
                picUrl = "http://example.com/account.jpg",
                playCount = 1_530_000_000L,
                trackCount = 30
            ),
            detailHeader = NeteaseCollectionHeader(
                id = 5_320_167_908L,
                isAlbum = false,
                name = "时光雷达",
                coverUrl = "https://example.com/visitor.jpg",
                playCount = 1L,
                trackCount = 50
            )
        )

        val refreshed = refreshNeteasePlaylistCachedHeader(cached, appliedHeader)

        assertEquals("为你定制的时光雷达", refreshed.header.name)
        assertEquals("https://example.com/account.jpg", refreshed.header.coverUrl)
        assertEquals(1_530_000_000L, refreshed.header.playCount)
        assertEquals(30, refreshed.header.trackCount)
    }

    @Test
    fun `all radar definitions are treated as radar playlists`() {
        assertEquals(
            NeteaseRadarPlaylistDefinitions.map { it.id },
            NeteaseRadarPlaylistDefinitions
                .map { it.id }
                .filter(::isNeteaseRadarPlaylist)
        )
    }

    @Test
    fun `radar detail keeps MGC header`() {
        val header = applyNeteaseRadarPlaylistHeader(
            playlist = PlaylistSummary(
                id = 5_320_167_908L,
                name = "为你定制的时光雷达",
                picUrl = "http://example.com/account.jpg",
                playCount = 1_530_000_000L,
                trackCount = 30
            ),
            detailHeader = NeteaseCollectionHeader(
                id = 5_320_167_908L,
                isAlbum = false,
                name = "时光雷达",
                coverUrl = "https://example.com/visitor.jpg",
                playCount = 1L,
                trackCount = 50
            )
        )

        assertEquals("为你定制的时光雷达", header.name)
        assertEquals("https://example.com/account.jpg", header.coverUrl)
        assertEquals(1_530_000_000L, header.playCount)
        assertEquals(30, header.trackCount)
    }

    @Test
    fun `radar detail keeps title when MGC cover is unavailable`() {
        val header = applyNeteaseRadarPlaylistHeader(
            playlist = PlaylistSummary(
                id = 5_320_167_908L,
                name = "为你定制的时光雷达",
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            ),
            detailHeader = NeteaseCollectionHeader(
                id = 5_320_167_908L,
                isAlbum = false,
                name = "时光雷达",
                coverUrl = "https://example.com/visitor.jpg",
                playCount = 1L,
                trackCount = 50
            )
        )

        assertEquals("为你定制的时光雷达", header.name)
        assertEquals("https://example.com/visitor.jpg", header.coverUrl)
    }

    @Test
    fun `ordinary playlists keep their detail header`() {
        val detailHeader = NeteaseCollectionHeader(
            id = 123L,
            isAlbum = false,
            name = "详情标题",
            coverUrl = "https://example.com/detail.jpg",
            playCount = 2L,
            trackCount = 10
        )

        val header = applyNeteaseRadarPlaylistHeader(
            playlist = PlaylistSummary(
                id = 123L,
                name = "入口标题",
                picUrl = "https://example.com/entry.jpg",
                playCount = 3L,
                trackCount = 20
            ),
            detailHeader = detailHeader
        )

        assertEquals(detailHeader, header)
    }
}
