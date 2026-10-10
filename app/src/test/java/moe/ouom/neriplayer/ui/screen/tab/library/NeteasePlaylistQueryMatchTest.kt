package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaylistQueryMatchTest {

    private val playlist = PlaylistSummary(
        id = 101L,
        name = "Morning Mix",
        picUrl = "https://p1.example.invalid/Cover-ABC.jpg",
        playCount = 98_765L,
        trackCount = 42
    )

    @Test
    fun `play count digits match a playlist`() {
        assertTrue(playlist.matchesNeteasePlaylistQuery("8765"))
    }

    @Test
    fun `track count digits match a playlist`() {
        assertTrue(playlist.matchesNeteasePlaylistQuery("42"))
    }

    @Test
    fun `cover url matches ignoring case`() {
        assertTrue(playlist.matchesNeteasePlaylistQuery("cover-abc"))
    }

    @Test
    fun `queries matching no field are rejected`() {
        assertFalse(playlist.matchesNeteasePlaylistQuery("Evening"))
    }

    @Test
    fun `blank queries keep the original playlist list`() {
        val playlists = listOf(playlist)

        assertSame(playlists, filterNeteasePlaylists(playlists, "   "))
        assertEquals(emptyList<PlaylistSummary>(), filterNeteasePlaylists(playlists, " Evening "))
    }
}
