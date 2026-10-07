package moe.ouom.neriplayer.core.player.playlist

import android.app.Application
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PlayerFavoritesMembershipTest {

    private val application = mock(Application::class.java)
    private val liked = song(id = 1)
    private val other = song(id = 2)
    private val mix = LocalPlaylist(id = 5, name = "Mix", songs = mutableListOf(liked, other))
    private val favorites = LocalPlaylist(
        id = FavoritesPlaylist.SYSTEM_ID,
        name = "Favorites",
        songs = mutableListOf(liked)
    )

    @Test
    fun `songs in ordinary playlists are not favorites without a favorites playlist`() {
        assertFalse(PlayerFavoritesController.isFavorite(listOf(mix), liked, application))
    }

    @Test
    fun `favorites membership follows song identity rather than display metadata`() {
        val playlists = listOf(mix, favorites)

        assertTrue(PlayerFavoritesController.isFavorite(playlists, liked.copy(customName = "Renamed"), application))
        assertFalse(PlayerFavoritesController.isFavorite(playlists, other, application))
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
