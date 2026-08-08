package moe.ouom.neriplayer.core.player.playlist

import android.app.Application
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlayerFavoritesControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUpCoverMapper() {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
    }

    @After
    fun tearDownCoverMapper() {
        CoverUrlMapper.installForTest(null)
    }

    @Test
    fun `optimistic favorite readd projects downloaded copy to remote source`() {
        val application = mock(Application::class.java)
        `when`(application.applicationContext).thenReturn(application)
        `when`(application.filesDir).thenReturn(tempFolder.root)
        val remoteSong = remoteNeteaseSong()
        val downloadedCopy = downloadedLocalCopy(remoteSong)
        val initialPlaylists = listOf(
            LocalPlaylist(
                id = FavoritesPlaylist.SYSTEM_ID,
                name = "我喜欢的音乐"
            )
        )

        val added = PlayerFavoritesController.optimisticUpdateFavorites(
            playlists = initialPlaylists,
            add = true,
            song = downloadedCopy,
            application = application,
            favoritePlaylistName = "我喜欢的音乐"
        )
        val removed = PlayerFavoritesController.optimisticUpdateFavorites(
            playlists = added,
            add = false,
            song = downloadedCopy,
            application = application,
            favoritePlaylistName = "我喜欢的音乐"
        )
        val readded = PlayerFavoritesController.optimisticUpdateFavorites(
            playlists = removed,
            add = true,
            song = downloadedCopy,
            application = application,
            favoritePlaylistName = "我喜欢的音乐"
        )

        val favorite = readded.single().songs.single()
        assertEquals(remoteSong.identity(), favorite.identity())
        assertEquals("netease", favorite.channelId)
        assertNull(favorite.mediaUri)
        assertNull(favorite.localFilePath)
        assertFalse(LocalSongSupport.isLocalSong(favorite, null))
    }

    private fun remoteNeteaseSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
    }

    private fun downloadedLocalCopy(source: SongItem): SongItem {
        return SongItem(
            id = 99L,
            name = source.name,
            artist = source.artist,
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = source.durationMs,
            coverUrl = null,
            mediaUri = "/downloads/song.mp3",
            localFileName = "song.mp3",
            localFilePath = "/downloads/song.mp3",
            channelId = "local",
            audioId = "99",
            sourceStableKey = source.stableKey()
        )
    }
}
