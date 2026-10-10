package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistDetail
import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistHeader
import moe.ouom.neriplayer.data.model.netease.cache.CachedNeteasePlaylistTrack
import moe.ouom.neriplayer.data.model.netease.collection.NeteaseCollectionHeader
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCollectionEntryContextPolicyTest {
    @Test
    fun `header count check falls back to cached track count when the header has none`() {
        val cached = cache(headerTrackCount = 0, trackCount = 3)

        assertTrue(reuse(cached, expectedTrackCount = 3, requireHeaderMatch = true))
        assertFalse(reuse(cached, expectedTrackCount = 4, requireHeaderMatch = true))
    }

    @Test
    fun `header count check accepts matching or unknown expected counts`() {
        val cached = cache(headerTrackCount = 3, trackCount = 3)

        assertTrue(reuse(cached, expectedTrackCount = 3, requireHeaderMatch = true))
        assertTrue(reuse(cached, expectedTrackCount = 0, requireHeaderMatch = true))
        assertFalse(reuse(cached, expectedTrackCount = 0, requireHeaderMatch = true, signature = "other"))
    }

    @Test
    fun `cache without header check still requires all expected tracks and the same signature`() {
        val cached = cache(headerTrackCount = 99, trackCount = 2)

        assertFalse(reuse(cached, expectedTrackCount = 3, requireHeaderMatch = false))
        assertTrue(reuse(cached, expectedTrackCount = 2, requireHeaderMatch = false))
        assertFalse(reuse(cached, expectedTrackCount = 2, requireHeaderMatch = false, signature = "other"))
    }

    @Test
    fun `context reset restores the matching public radar definition`() {
        val personalized = PlaylistSummary(TREASURE_RADAR_ID, "Account radar", "https://img/a.jpg", 9L, 30)

        assertEquals(
            PlaylistSummary(TREASURE_RADAR_ID, "宝藏雷达", "", 0L, 0),
            resetNeteaseRadarPlaylistSummaryForContextChange(personalized)
        )
    }

    @Test
    fun `entry metadata is kept for ordinary playlists and the known radar context`() {
        val ordinary = PlaylistSummary(42L, "Ordinary", "https://img/o.jpg", 1L, 1)
        val radar = PlaylistSummary(TREASURE_RADAR_ID, "Account radar", "https://img/a.jpg", 9L, 30)

        assertSame(ordinary, prepareNeteasePlaylistEntryForContext(ordinary, null, "ctx-a"))
        assertSame(radar, prepareNeteasePlaylistEntryForContext(radar, "ctx-a", "ctx-a"))
        assertEquals(
            "宝藏雷达",
            prepareNeteasePlaylistEntryForContext(radar, "ctx-a", "ctx-b").name
        )
    }

    @Test
    fun `blank radar entry metadata keeps the detail header values`() {
        val header = applyNeteaseRadarPlaylistHeader(
            playlist = PlaylistSummary(TREASURE_RADAR_ID, " ", "", 0L, 0),
            detailHeader = detailHeader(TREASURE_RADAR_ID)
        )

        assertEquals(detailHeader(TREASURE_RADAR_ID), header)
    }

    @Test
    fun `display header ignores radar sources for ordinary playlists`() {
        val detail = detailHeader(42L)

        assertSame(
            detail,
            resolveNeteasePlaylistDisplayHeader(
                playlist = PlaylistSummary(42L, "Entry", "https://img/e.jpg", 3L, 4),
                detailHeader = detail,
                freshRadarHeader = PlaylistSummary(42L, "Fresh", "", 0L, 0),
                cachedRadarHeader = null
            )
        )
    }

    @Test
    fun `radar display header without cache layers fresh metadata over the entry`() {
        val entry = PlaylistSummary(TREASURE_RADAR_ID, "Entry radar", "http://img/entry.jpg", 0L, 0)
        val detail = detailHeader(TREASURE_RADAR_ID)

        val withoutFresh = resolveNeteasePlaylistDisplayHeader(entry, detail, null, null)
        val withFresh = resolveNeteasePlaylistDisplayHeader(
            playlist = entry,
            detailHeader = detail,
            freshRadarHeader = PlaylistSummary(TREASURE_RADAR_ID, "Fresh radar", "", 77L, 0),
            cachedRadarHeader = null
        )

        assertEquals(
            detail.copy(name = "Entry radar", coverUrl = "https://img/entry.jpg"),
            withoutFresh
        )
        assertEquals(
            detail.copy(name = "Fresh radar", coverUrl = "https://img/entry.jpg", playCount = 77L),
            withFresh
        )
    }

    private fun reuse(
        cached: CachedNeteasePlaylistDetail,
        expectedTrackCount: Int,
        requireHeaderMatch: Boolean,
        signature: String = SIGNATURE
    ) = shouldReuseNeteasePlaylistCache(
        cached = cached,
        expectedTrackCount = expectedTrackCount,
        recentTrackSignature = signature,
        requireHeaderTrackCountMatch = requireHeaderMatch
    )

    private fun cache(headerTrackCount: Int, trackCount: Int) = CachedNeteasePlaylistDetail(
        playlistId = 42L,
        header = CachedNeteasePlaylistHeader(
            id = 42L,
            name = "Cached",
            coverUrl = "",
            playCount = 0L,
            trackCount = headerTrackCount
        ),
        recentTrackSignature = SIGNATURE,
        tracks = List(trackCount) { index ->
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

    private fun detailHeader(id: Long) = NeteaseCollectionHeader(
        id = id,
        isAlbum = false,
        name = "Detail",
        coverUrl = "https://img/detail.jpg",
        playCount = 5L,
        trackCount = 50
    )

    private companion object {
        const val TREASURE_RADAR_ID = 5_362_359_247L
        const val SIGNATURE = "3#sig"
    }
}
