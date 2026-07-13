package moe.ouom.neriplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Locale

class SystemPlaylistIdentityTest {

    private val inertContext = mockContext()

    @Test
    fun `positive id reserved names stay as custom playlists`() {
        val customFavorites = LocalPlaylist(
            id = 123L,
            name = "My Favorite Music"
        )
        val customLocalFiles = LocalPlaylist(
            id = 456L,
            name = "Local Files"
        )

        assertNull(FavoritesPlaylist.firstOrNull(listOf(customFavorites), null))
        assertNull(LocalFilesPlaylist.firstOrNull(listOf(customLocalFiles), null))
        assertNull(SystemLocalPlaylists.resolve(customFavorites.id, customFavorites.name, inertContext))
        assertNull(SystemLocalPlaylists.resolve(customLocalFiles.id, customLocalFiles.name, inertContext))
    }

    @Test
    fun `negative id legacy reserved names still map as system playlists`() {
        val legacyFavorites = LocalPlaylist(
            id = -9L,
            name = "My Favorite Music"
        )
        val legacyLocalFiles = LocalPlaylist(
            id = -10L,
            name = "Local Files"
        )

        assertNotNull(FavoritesPlaylist.firstOrNull(listOf(legacyFavorites), null))
        assertNotNull(LocalFilesPlaylist.firstOrNull(listOf(legacyLocalFiles), null))
    }

    @Test
    fun `negative id chinese reserved names map as system playlists`() {
        val legacyFavorites = LocalPlaylist(
            id = -11L,
            name = "我喜欢的音乐"
        )
        val legacyLocalFiles = LocalPlaylist(
            id = -12L,
            name = "本地文件"
        )

        assertNotNull(FavoritesPlaylist.firstOrNull(listOf(legacyFavorites), inertContext))
        assertNotNull(LocalFilesPlaylist.firstOrNull(listOf(legacyLocalFiles), inertContext))
    }

    @Test
    fun `legacy local song fallback requires album id zero`() {
        assertTrue(LocalSongSupport.isLocalSong("Local Files", null, 0L, null))
        assertFalse(LocalSongSupport.isLocalSong("Local Files", null, 12L, null))
        assertTrue(LocalSongSupport.isLocalSong("本地文件", null, 0L, inertContext))
        assertTrue(LocalSongSupport.isLocalSong(LocalSongSupport.LOCAL_ALBUM_IDENTITY, null, 0L, null))
    }

    @Test
    fun `favorites merge keeps first remote song when downloaded local copy appears later`() {
        val remoteSong = remoteNeteaseSong()
        val localCopy = downloadedLocalCopy(remoteSong)
        val legacyFavorites = LocalPlaylist(
            id = -11L,
            name = "我喜欢的音乐",
            songs = mutableListOf(remoteSong, localCopy)
        )

        val merged = FavoritesPlaylist.merge(listOf(legacyFavorites), inertContext)

        assertEquals(1, merged.songs.size)
        assertEquals(remoteSong, merged.songs.single())
    }

    @Test
    fun `local files merge keeps first downloaded copy with same source identity`() {
        val remoteSong = remoteNeteaseSong()
        val firstCopy = downloadedLocalCopy(remoteSong)
        val secondCopy = downloadedLocalCopy(remoteSong).copy(
            id = 100L,
            mediaUri = "/storage/emulated/0/Music/song-copy.mp3",
            localFilePath = "/storage/emulated/0/Music/song-copy.mp3"
        )
        val legacyLocalFiles = LocalPlaylist(
            id = -12L,
            name = "本地文件",
            songs = mutableListOf(firstCopy, secondCopy)
        )

        val merged = LocalFilesPlaylist.merge(listOf(legacyLocalFiles), inertContext)

        assertEquals(1, merged.songs.size)
        assertEquals(firstCopy, merged.songs.single())
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
            mediaUri = "/storage/emulated/0/Music/song.mp3",
            localFilePath = "/storage/emulated/0/Music/song.mp3",
            channelId = "local",
            audioId = "99",
            sourceStableKey = source.stableKey()
        )
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)

        `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(preferences.getString("selected_language", "")).thenReturn("")
        `when`(context.resources).thenReturn(resources)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales[0]).thenReturn(Locale.getDefault())
        `when`(context.createConfigurationContext(any(Configuration::class.java))).thenReturn(context)
        `when`(context.getString(R.string.favorite_my_music)).thenReturn("我喜欢的音乐")
        `when`(context.getString(R.string.local_files)).thenReturn("本地文件")
        return context
    }
}
