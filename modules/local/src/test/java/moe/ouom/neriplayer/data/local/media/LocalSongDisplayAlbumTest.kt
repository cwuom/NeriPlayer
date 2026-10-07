package moe.ouom.neriplayer.data.local.media

import android.content.Context
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalSongDisplayAlbumTest {
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(LOCALIZED_LOCAL_FILES).`when`(context).getString(CoreCommonR.string.local_files)
    }

    @Test
    fun `blank albums stay blank`() {
        assertEquals("", remote(album = "   ").displayAlbum(context))
    }

    @Test
    fun `remote albums are trimmed and local files aliases are localized`() {
        assertEquals("Night Album", remote(album = "  Night Album ").displayAlbum(context))
        assertEquals(LOCALIZED_LOCAL_FILES, remote(album = "LOCAL FILES").displayAlbum(context))
    }

    @Test
    fun `local songs show the local album identity with the localized local files name`() {
        assertEquals(LOCALIZED_LOCAL_FILES, local(album = LocalSongSupport.LOCAL_ALBUM_IDENTITY).displayAlbum(context))
        assertEquals("Night Album", local(album = " Night Album").displayAlbum(context))
    }

    @Test
    fun `only managed netease downloads drop the legacy source prefix`() {
        assertEquals("Night Album", local(album = "Netease-Night Album", sourceStableKey = "123|netease|").displayAlbum(context))
        assertEquals("Netease-Night Album", local(album = "Netease-Night Album").displayAlbum(context))
    }

    private fun remote(album: String) = SongItem(
        id = 1,
        name = "Song",
        artist = "Artist",
        album = album,
        albumId = 5,
        durationMs = 0,
        coverUrl = null,
        mediaUri = "https://cdn.example/song.mp3"
    )

    private fun local(album: String, sourceStableKey: String? = null) = SongItem(
        id = 2,
        name = "Song",
        artist = "Artist",
        album = album,
        albumId = 0,
        durationMs = 0,
        coverUrl = null,
        localFilePath = "/music/song.flac",
        sourceStableKey = sourceStableKey
    )

    private companion object {
        const val LOCALIZED_LOCAL_FILES = "本地文件"
    }
}
