package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPlaylistCacheFreshnessTest {

    @Test
    fun `cached tracks stay fresh until six hours after saving`() {
        val cache = cache(savedAtMs = SAVED_AT_MS)

        assertTrue(isYouTubeMusicPlaylistCacheFresh(cache, nowMs = SAVED_AT_MS))
        assertTrue(
            isYouTubeMusicPlaylistCacheFresh(
                cache,
                nowMs = SAVED_AT_MS + YOUTUBE_MUSIC_PLAYLIST_CACHE_FRESHNESS_MS - 1L
            )
        )
        assertFalse(
            isYouTubeMusicPlaylistCacheFresh(
                cache,
                nowMs = SAVED_AT_MS + YOUTUBE_MUSIC_PLAYLIST_CACHE_FRESHNESS_MS
            )
        )
    }

    @Test
    fun `cache saved after the current clock is not trusted`() {
        assertFalse(
            isYouTubeMusicPlaylistCacheFresh(
                cache(savedAtMs = SAVED_AT_MS),
                nowMs = SAVED_AT_MS - 1L
            )
        )
    }

    @Test
    fun `cache without tracks or save time is never fresh`() {
        assertFalse(
            isYouTubeMusicPlaylistCacheFresh(
                cache(savedAtMs = SAVED_AT_MS, tracks = emptyList()),
                nowMs = SAVED_AT_MS
            )
        )
        assertFalse(isYouTubeMusicPlaylistCacheFresh(cache(savedAtMs = 0L), nowMs = 1L))
    }

    @Test
    fun `creator context is trimmed and survives a missing previous playlist`() {
        assertEquals(
            "Demo Creator",
            retainYouTubeMusicPlaylistCreatorContext(
                playlist = playlist(creatorName = "  Demo Creator  "),
                previousPlaylist = null
            ).creatorName
        )
        assertEquals(
            "",
            retainYouTubeMusicPlaylistCreatorContext(
                playlist = playlist(creatorName = "   "),
                previousPlaylist = null
            ).creatorName
        )
        assertEquals(
            "Previous Creator",
            retainYouTubeMusicPlaylistCreatorContext(
                playlist = playlist(creatorName = ""),
                previousPlaylist = playlist(creatorName = "  Previous Creator ")
            ).creatorName
        )
    }

    private fun cache(
        savedAtMs: Long,
        tracks: List<CachedYouTubeMusicPlaylistTrack> = listOf(
            CachedYouTubeMusicPlaylistTrack(
                videoId = "video-1",
                name = "Track",
                artist = "Artist",
                albumName = "Album",
                durationMs = 180_000L,
                coverUrl = ""
            )
        )
    ) = CachedYouTubeMusicPlaylistDetail(
        browseId = "VLPLdemo",
        playlistId = "PLdemo",
        title = "Demo",
        subtitle = "Playlist",
        coverUrl = "",
        trackCount = tracks.size,
        firstPageSignature = "signature",
        tracks = tracks,
        savedAtMs = savedAtMs
    )

    private fun playlist(creatorName: String) = YouTubeMusicPlaylist(
        browseId = "MPREalbum",
        playlistId = "MPREalbum",
        title = "Demo Album",
        subtitle = "Album",
        coverUrl = "",
        creatorName = creatorName
    )

    private companion object {
        const val SAVED_AT_MS = 1_700_000_000_000L
    }
}
