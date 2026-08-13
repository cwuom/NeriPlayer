package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.platform.netease.CachedNeteasePlaylistTrack
import moe.ouom.neriplayer.data.platform.netease.neteaseRadarCacheContext
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
    fun `radar cache refresh keeps MGC header when fresh metadata is unavailable`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 5_320_167_908L,
            header = CachedNeteasePlaylistHeader(
                id = 5_320_167_908L,
                name = "为你定制的时光雷达",
                coverUrl = "https://example.com/account.jpg",
                playCount = 1_530_000_000L,
                trackCount = 30
            ),
            recentTrackSignature = "50#0:1|",
            tracks = emptyList()
        )

        assertEquals(cached, refreshNeteasePlaylistCachedHeader(cached, fresh = null))
    }

    @Test
    fun `cached radar header is not overwritten by generic fallback`() {
        val cached = CachedNeteasePlaylistHeader(
            id = 5_320_167_908L,
            name = "为你定制的时光雷达",
            coverUrl = "https://example.com/account.jpg",
            playCount = 1_530_000_000L,
            trackCount = 30
        )

        val header = cached.toNeteaseCollectionHeader(
            PlaylistSummary(
                id = 5_320_167_908L,
                name = "时光雷达",
                picUrl = "https://example.com/visitor.jpg",
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
    fun `empty radar metadata keeps matching cached display header during refresh`() {
        val cachedHeader = CachedNeteasePlaylistHeader(
            id = 5_320_167_908L,
            name = "账号雷达",
            coverUrl = "https://example.com/account.jpg",
            playCount = 99L,
            trackCount = 30
        )
        val playlist = PlaylistSummary(
            id = cachedHeader.id,
            name = "时光雷达",
            picUrl = "",
            playCount = 0L,
            trackCount = 0
        )
        val detailHeader = NeteaseCollectionHeader(
            id = cachedHeader.id,
            isAlbum = false,
            name = "通用雷达",
            coverUrl = "https://example.com/generic.jpg",
            playCount = 1L,
            trackCount = 50
        )

        val resolved = resolveNeteasePlaylistDisplayHeader(
            playlist = playlist,
            detailHeader = detailHeader,
            freshRadarHeader = null,
            cachedRadarHeader = cachedHeader
        )

        assertEquals("账号雷达", resolved.name)
        assertEquals("https://example.com/account.jpg", resolved.coverUrl)
        assertEquals(99L, resolved.playCount)
        assertEquals(30, resolved.trackCount)
    }

    @Test
    fun `partial radar metadata preserves matching cached fields`() {
        val cachedHeader = CachedNeteasePlaylistHeader(
            id = 5_320_167_908L,
            name = "账号雷达",
            coverUrl = "https://example.com/account.jpg",
            playCount = 99L,
            trackCount = 30
        )
        val resolved = resolveNeteasePlaylistDisplayHeader(
            playlist = PlaylistSummary(
                id = cachedHeader.id,
                name = "时光雷达",
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            ),
            detailHeader = NeteaseCollectionHeader(
                id = cachedHeader.id,
                isAlbum = false,
                name = "通用雷达",
                coverUrl = "https://example.com/generic.jpg",
                playCount = 1L,
                trackCount = 50
            ),
            freshRadarHeader = PlaylistSummary(
                id = cachedHeader.id,
                name = "新雷达标题",
                picUrl = "",
                playCount = 0L,
                trackCount = 0
            ),
            cachedRadarHeader = cachedHeader
        )

        assertEquals("新雷达标题", resolved.name)
        assertEquals("https://example.com/account.jpg", resolved.coverUrl)
        assertEquals(99L, resolved.playCount)
        assertEquals(30, resolved.trackCount)
    }

    @Test
    fun `radar display fallback ignores a cache from another account context`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 5_320_167_908L,
            header = CachedNeteasePlaylistHeader(
                id = 5_320_167_908L,
                name = "账号 A 雷达",
                coverUrl = "https://example.com/account-a.jpg",
                playCount = 99L,
                trackCount = 30
            ),
            recentTrackSignature = "30#0:1|",
            tracks = emptyList(),
            radarCacheContext = "account-a"
        )

        assertNull(
            matchingNeteaseRadarCacheHeader(
                cached = cached,
                playlistId = cached.playlistId,
                expectedRadarCacheContext = "account-b"
            )
        )
        assertEquals(
            cached.header,
            matchingNeteaseRadarCacheHeader(
                cached = cached,
                playlistId = cached.playlistId,
                expectedRadarCacheContext = "account-a"
            )
        )
    }

    @Test
    fun `radar cache validation uses raw detail metadata instead of display count`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 5_320_167_908L,
            header = CachedNeteasePlaylistHeader(
                id = 5_320_167_908L,
                name = "为你定制的时光雷达",
                coverUrl = "https://example.com/account.jpg",
                playCount = 1_530_000_000L,
                trackCount = 30
            ),
            recentTrackSignature = "50#0:1|",
            tracks = List(50) { index ->
                CachedNeteasePlaylistTrack(
                    id = index + 1L,
                    name = "Track $index",
                    artist = "Artist",
                    album = "Album",
                    albumId = 1L,
                    durationMs = 1L,
                    coverUrl = null,
                    audioId = null
                )
            }
        )

        assertTrue(
            shouldReuseNeteasePlaylistCache(
                cached = cached,
                expectedTrackCount = 50,
                recentTrackSignature = "50#0:1|",
                requireHeaderTrackCountMatch = false
            )
        )
        assertFalse(
            shouldReuseNeteasePlaylistCache(
                cached = cached,
                expectedTrackCount = 50,
                recentTrackSignature = "50#0:1|",
                requireHeaderTrackCountMatch = true
            )
        )
        assertFalse(
            shouldReuseNeteasePlaylistCache(
                cached = cached,
                expectedTrackCount = 50,
                recentTrackSignature = "50#0:2|",
                requireHeaderTrackCountMatch = false
            )
        )
        assertFalse(
            shouldReuseNeteasePlaylistCache(
                cached = cached.copy(tracks = cached.tracks.take(49)),
                expectedTrackCount = 50,
                recentTrackSignature = "50#0:1|",
                requireHeaderTrackCountMatch = false
            )
        )
    }

    @Test
    fun `radar cache only serves its matching account context`() {
        val accountAContext = neteaseRadarCacheContext(
            mapOf("MUSIC_U" to "synthetic-account-a-cookie")
        )
        val accountBContext = neteaseRadarCacheContext(
            mapOf("MUSIC_U" to "synthetic-account-b-cookie")
        )
        val publicContext = neteaseRadarCacheContext(emptyMap())
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 5_320_167_908L,
            header = CachedNeteasePlaylistHeader(
                id = 5_320_167_908L,
                name = "为你定制的时光雷达",
                coverUrl = "https://example.com/account-a.jpg",
                playCount = 1_530_000_000L,
                trackCount = 30
            ),
            recentTrackSignature = "50#0:1|",
            tracks = emptyList(),
            radarCacheContext = accountAContext
        )

        assertTrue(
            isNeteasePlaylistCacheCompatible(
                cached = cached,
                requestedPlaylistId = cached.playlistId,
                expectedRadarCacheContext = accountAContext
            )
        )
        assertFalse(
            isNeteasePlaylistCacheCompatible(
                cached = cached,
                requestedPlaylistId = cached.playlistId,
                expectedRadarCacheContext = accountBContext
            )
        )
        assertFalse(
            isNeteasePlaylistCacheCompatible(
                cached = cached,
                requestedPlaylistId = cached.playlistId,
                expectedRadarCacheContext = publicContext
            )
        )
        assertFalse(
            isNeteasePlaylistCacheCompatible(
                cached = cached.copy(radarCacheContext = null),
                requestedPlaylistId = cached.playlistId,
                expectedRadarCacheContext = accountAContext
            )
        )
        assertFalse(
            isNeteasePlaylistCacheCompatible(
                cached = cached.copy(radarCacheContext = publicContext),
                requestedPlaylistId = cached.playlistId,
                expectedRadarCacheContext = accountAContext
            )
        )
        assertFalse(accountAContext.contains("synthetic-account-a-cookie"))
        assertFalse(accountBContext.contains("synthetic-account-b-cookie"))
    }

    @Test
    fun `normal playlist cache ignores radar account context`() {
        val cached = CachedNeteasePlaylistDetail(
            playlistId = 42L,
            header = CachedNeteasePlaylistHeader(
                id = 42L,
                name = "普通歌单",
                coverUrl = "https://example.com/playlist.jpg",
                playCount = 1L,
                trackCount = 1
            ),
            recentTrackSignature = "1#0:1|",
            tracks = emptyList(),
            radarCacheContext = null
        )

        assertTrue(
            isNeteasePlaylistCacheCompatible(
                cached = cached,
                requestedPlaylistId = 42L,
                expectedRadarCacheContext = neteaseRadarCacheContext(emptyMap())
            )
        )
    }

    @Test
    fun `stale radar request is rejected after account context changes`() {
        val accountAContext = neteaseRadarCacheContext(
            mapOf("MUSIC_U" to "synthetic-account-a-cookie")
        )
        val accountBContext = neteaseRadarCacheContext(
            mapOf("MUSIC_U" to "synthetic-account-b-cookie")
        )
        val radarId = 5_320_167_908L

        assertTrue(
            shouldAcceptNeteasePlaylistLoadResult(
                requestedPlaylistId = radarId,
                activePlaylistId = radarId,
                requestRadarCacheContext = accountAContext,
                currentRadarCacheContext = accountAContext
            )
        )
        assertFalse(
            shouldAcceptNeteasePlaylistLoadResult(
                requestedPlaylistId = radarId,
                activePlaylistId = radarId,
                requestRadarCacheContext = accountAContext,
                currentRadarCacheContext = accountBContext
            )
        )
        assertFalse(
            shouldAcceptNeteasePlaylistLoadResult(
                requestedPlaylistId = radarId,
                activePlaylistId = 42L,
                requestRadarCacheContext = accountAContext,
                currentRadarCacheContext = accountAContext
            )
        )
    }

    @Test
    fun `stale collection request is rejected after a newer load starts`() {
        assertTrue(
            shouldAcceptNeteaseCollectionLoadResult(
                requestedCollectionId = 42L,
                activeCollectionId = 42L,
                requestGeneration = 2L,
                activeGeneration = 2L
            )
        )
        assertFalse(
            shouldAcceptNeteaseCollectionLoadResult(
                requestedCollectionId = 42L,
                activeCollectionId = 42L,
                requestGeneration = 1L,
                activeGeneration = 2L
            )
        )
        assertFalse(
            shouldAcceptNeteaseCollectionLoadResult(
                requestedCollectionId = 42L,
                activeCollectionId = 43L,
                requestGeneration = 2L,
                activeGeneration = 2L
            )
        )
    }

    @Test
    fun `initial radar context emission only skips the unchanged construction snapshot`() {
        assertFalse(
            shouldHandleInitialNeteaseRadarContextEmission(
                isFirstEmission = true,
                initialRadarCacheContext = "account-a",
                emittedRadarCacheContext = "account-a"
            )
        )
        assertTrue(
            shouldHandleInitialNeteaseRadarContextEmission(
                isFirstEmission = true,
                initialRadarCacheContext = "account-a",
                emittedRadarCacheContext = "account-b"
            )
        )
        assertTrue(
            shouldHandleInitialNeteaseRadarContextEmission(
                isFirstEmission = false,
                initialRadarCacheContext = "account-a",
                emittedRadarCacheContext = "account-a"
            )
        )
    }

    @Test
    fun `radar context change resets personalized entry metadata`() {
        val personalized = PlaylistSummary(
            id = 5_320_167_908L,
            name = "账号 A 的时光雷达",
            picUrl = "https://example.com/account-a.jpg",
            playCount = 42L,
            trackCount = 30
        )

        val reset = resetNeteaseRadarPlaylistSummaryForContextChange(personalized)

        assertEquals(personalized.id, reset.id)
        assertEquals("时光雷达", reset.name)
        assertEquals("", reset.picUrl)
        assertEquals(0L, reset.playCount)
        assertEquals(0, reset.trackCount)
    }

    @Test
    fun `normal playlist keeps entry metadata across radar context change`() {
        val playlist = PlaylistSummary(
            id = 42L,
            name = "普通歌单",
            picUrl = "https://example.com/playlist.jpg",
            playCount = 1L,
            trackCount = 1
        )

        assertEquals(playlist, resetNeteaseRadarPlaylistSummaryForContextChange(playlist))
    }

    @Test
    fun `unknown radar entry context falls back to public metadata`() {
        val personalized = PlaylistSummary(
            id = 5_320_167_908L,
            name = "账号 A 的时光雷达",
            picUrl = "https://example.com/account-a.jpg",
            playCount = 42L,
            trackCount = 30
        )

        val safeEntry = prepareNeteasePlaylistEntryForContext(
            playlist = personalized,
            knownCacheContext = null,
            currentCacheContext = neteaseRadarCacheContext(emptyMap())
        )

        assertEquals("时光雷达", safeEntry.name)
        assertEquals("", safeEntry.picUrl)
        assertEquals(0L, safeEntry.playCount)
        assertEquals(0, safeEntry.trackCount)
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
