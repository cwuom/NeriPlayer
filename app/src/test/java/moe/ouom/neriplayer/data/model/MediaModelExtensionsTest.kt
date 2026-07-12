package moe.ouom.neriplayer.data.model

import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaModelExtensionsTest {

    @Test
    fun `resolveDisplayCoverUrl prefers local cover over remote fallback on main thread`() {
        assertEquals(
            "content://covers/song.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = "content://covers/song.jpg",
                onMainThread = true
            )
        )
    }

    @Test
    fun `resolveDisplayCoverUrl keeps custom override above local and remote covers`() {
        assertEquals(
            "content://covers/custom.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = "content://covers/custom.jpg",
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = "content://covers/song.jpg",
                onMainThread = true
            )
        )
    }

    @Test
    fun `resolveDisplayCoverUrl falls back to remote cover when local cover is unavailable`() {
        assertEquals(
            "https://example.com/song.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = null,
                onMainThread = true
            )
        )
    }

    @Test
    fun `local playlist cover follows display order and skips songs without cover`() {
        val playlist = LocalPlaylist(
            id = 1L,
            name = "cover",
            songs = mutableListOf(
                song(name = "newest", coverUrl = null),
                song(name = "middle", coverUrl = "content://covers/middle.jpg"),
                song(name = "oldest", coverUrl = "content://covers/oldest.jpg")
            )
        )

        assertEquals("content://covers/middle.jpg", playlist.displayCoverUrl())
    }

    @Test
    fun `local artist cover follows display order and skips songs without cover`() {
        val artist = LocalArtistSummary(
            name = "artist",
            songs = listOf(
                song(name = "newest", coverUrl = null),
                song(name = "middle", coverUrl = "content://covers/middle.jpg"),
                song(name = "oldest", coverUrl = "content://covers/oldest.jpg")
            )
        )

        assertEquals("content://covers/middle.jpg", artist.displayCoverUrl())
    }

    private fun song(
        name: String,
        coverUrl: String?
    ): SongItem {
        return SongItem(
            id = name.hashCode().toLong(),
            name = name,
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = coverUrl
        )
    }
}
