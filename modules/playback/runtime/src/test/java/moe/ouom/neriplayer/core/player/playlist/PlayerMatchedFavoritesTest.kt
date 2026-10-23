package moe.ouom.neriplayer.core.player.playlist

import android.app.Application
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class PlayerMatchedFavoritesTest {
    private val application = mock(Application::class.java)
    private val remote = SongItem(
        id = 42L, name = "Song", artist = "Artist", album = "NeteaseAlbum", albumId = 7L,
        durationMs = 180_000L, coverUrl = null, channelId = "netease", audioId = "42"
    )
    private val local = remote.copy(
        id = 99L, album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, albumId = 0L,
        channelId = "local", audioId = "99", mediaUri = "/music/song.mp3",
        localFilePath = "/music/song.mp3", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "42"
    )

    @Test
    fun `local netease match inherits the remote favorite without changing file identity`() {
        val identityBefore = local.identity()

        assertTrue(PlayerFavoritesController.isFavorite(favorites(remote), local, application))
        assertEquals(identityBefore, local.identity())
        assertFalse(local.identity() == remote.identity())
    }

    @Test
    fun `favorite lookup rejects other providers invalid matches and conflicting download sources`() {
        listOf(
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC),
            local.copy(matchedLyricSource = null),
            local.copy(matchedSongId = null),
            local.copy(matchedSongId = "0"),
            local.copy(matchedSongId = "-42"),
            local.copy(matchedSongId = "invalid"),
            local.copy(matchedSongId = "43"),
            local.copy(id = 42L, matchedSongId = "43"),
            local.copy(sourceStableKey = "77|netease|"),
            local.copy(sourceStableKey = "77|bilibili|")
        ).forEach { candidate ->
            assertFalse(candidate.toString(), PlayerFavoritesController.isFavorite(favorites(remote), candidate, application))
        }
    }

    @Test
    fun `ordinary playlists do not mark a matched local song as favorite`() {
        val ordinary = LocalPlaylist(id = 5L, name = "Mix", songs = mutableListOf(remote))

        assertFalse(PlayerFavoritesController.isFavorite(listOf(ordinary), local, application))
    }

    @Test
    fun `lyric matches on other remote providers do not change their favorite identity`() {
        val bilibili = remote.copy(channelId = "bilibili", audioId = "BV42")
        val matchedBilibili = bilibili.copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42"
        )

        assertFalse(PlayerFavoritesController.isFavorite(favorites(remote), matchedBilibili, application))
        assertFalse(PlayerFavoritesController.isFavorite(favorites(bilibili), local, application))
    }

    @Test
    fun `optimistic favorite removal removes the matched remote entry`() {
        val updated = update(favorites(remote), add = false)

        assertTrue(updated.single().songs.isEmpty())
        assertFalse(PlayerFavoritesController.isFavorite(updated, local, application))
    }

    @Test
    fun `optimistic favorite add does not duplicate an existing netease match`() {
        val updated = update(favorites(remote), add = true)

        assertEquals(listOf(remote), updated.single().songs)
    }

    @Test
    fun `optimistic local favorite remains visible from the matched netease track`() {
        val added = update(favorites(), add = true)

        assertTrue(PlayerFavoritesController.isFavorite(added, remote, application))
    }

    private fun favorites(vararg songs: SongItem) = listOf(
        LocalPlaylist(id = FavoritesPlaylist.SYSTEM_ID, name = "Favorites", songs = songs.toMutableList())
    )

    private fun update(playlists: List<LocalPlaylist>, add: Boolean) =
        PlayerFavoritesController.optimisticUpdateFavorites(
            playlists = playlists,
            add = add,
            song = local,
            application = application,
            favoritePlaylistName = "Favorites",
            remoteProjection = { null }
        )
}
