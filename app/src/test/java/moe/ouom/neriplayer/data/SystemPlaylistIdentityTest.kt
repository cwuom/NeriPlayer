package moe.ouom.neriplayer.data

import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPlaylistIdentityTest {

    private val inertContext = ContextWrapper(null)

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
}
