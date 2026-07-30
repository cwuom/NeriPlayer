package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScreenLocalArtistSortModeTest {

    @Test
    fun `local artist sort defaults to song count`() {
        assertEquals(LocalArtistSortMode.SONG_COUNT, resolveLocalArtistSortMode(null))
        assertEquals(LocalArtistSortMode.SONG_COUNT, resolveLocalArtistSortMode("bad-value"))
    }

    @Test
    fun `local artist sort storage round trips selected value`() {
        val storageValue = localArtistSortModeStorageValue(LocalArtistSortMode.RECENT_ADDED)

        assertEquals(LocalArtistSortMode.RECENT_ADDED, resolveLocalArtistSortMode(storageValue))
    }

    @Test
    fun `local artists sort by song count by default policy`() {
        val artists = listOf(
            artist("Beta", songCount = 1),
            artist("Alpha", songCount = 3),
            artist("Gamma", songCount = 3)
        )

        val sorted = sortLocalArtists(artists, LocalArtistSortMode.SONG_COUNT)

        assertEquals(listOf("Alpha", "Gamma", "Beta"), sorted.map { it.name })
    }

    private fun artist(name: String, songCount: Int): LocalArtistSummary {
        return LocalArtistSummary(
            name = name,
            songs = List(songCount) { index ->
                SongItem(
                    id = (name.hashCode().toLong() * 31L) + index,
                    name = "$name $index",
                    artist = name,
                    album = "",
                    albumId = 0L,
                    durationMs = 0L,
                    coverUrl = null
                )
            }
        )
    }
}
