package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSongPlaceholderAlbumTest {
    @Test
    fun `fallback labels in every app language and the local identity are placeholders`() {
        listOf(null, "", "  ", "本地文件", " 本地文件 ", "Local Files", "local files", LocalSongSupport.LOCAL_ALBUM_IDENTITY)
            .forEach { album -> assertTrue("album=$album", LocalSongSupport.isPlaceholderAlbum(album)) }
    }

    @Test
    fun `real album names are not placeholders`() {
        listOf("Parklife", "本地", "Local", "local", "Files", "本地文件夹")
            .forEach { album -> assertFalse("album=$album", LocalSongSupport.isPlaceholderAlbum(album)) }
    }
}
