package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSongAlbumIdentityTest {

    @Test
    fun `local song identity uses stable local album key`() {
        val song = SongItem(
            id = 1L,
            name = "test",
            artist = "artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "/music/test.mp3"
        )

        assertEquals(LocalSongSupport.LOCAL_ALBUM_IDENTITY, song.identity().album)
    }

    @Test
    fun `remote song identity preserves explicit album names`() {
        val song = SongItem(
            id = 2L,
            name = "test",
            artist = "artist",
            album = "Local Files",
            albumId = 12L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/test.mp3"
        )

        assertEquals("Local Files", song.identity().album)
    }

    @Test
    fun `normalizeLocalAlbumIdentity keeps explicit album names and only canonicalizes fallback`() {
        assertEquals(
            "Local Files",
            normalizeLocalAlbumIdentity("Local Files", usesFallbackAlbum = false)
        )
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("Local Files", usesFallbackAlbum = true)
        )
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("", usesFallbackAlbum = false)
        )
    }
}
