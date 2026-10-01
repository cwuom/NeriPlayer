package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSongAlbumDisplayTest {

    @Test
    fun `local download source prefix is removed from album identity`() {
        assertEquals(
            "收敛",
            normalizeLocalAlbumIdentity("Netease收敛", false, stripManagedSourcePrefix = true)
        )
        assertEquals(
            "2:3",
            normalizeLocalAlbumIdentity("Netease2:3", false, stripManagedSourcePrefix = true)
        )
        assertEquals(
            "专辑",
            normalizeLocalAlbumIdentity("Netease:专辑", false, stripManagedSourcePrefix = true)
        )
        assertEquals(
            "专辑",
            normalizeLocalAlbumIdentity("Netease|专辑", false, stripManagedSourcePrefix = true)
        )
    }

    @Test
    fun `source-only album falls back to local identity`() {
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("Netease", false, stripManagedSourcePrefix = true)
        )
    }

    @Test
    fun `album names with a separating space are preserved`() {
        assertEquals("Netease Album", normalizeLocalAlbumIdentity("Netease Album", false))
    }

    @Test
    fun `ordinary local album beginning with source word is preserved`() {
        assertEquals("NeteaseRecords", normalizeLocalAlbumIdentity("NeteaseRecords", false))
    }

    @Test
    fun `only a netease remote stable key enables legacy prefix stripping`() {
        assertEquals(true, isNeteaseManagedSourceStableKey("123|netease|"))
        assertEquals(false, isNeteaseManagedSourceStableKey("123|__local_files__|/Music/a.flac"))
        assertEquals(false, isNeteaseManagedSourceStableKey("123|youtube_music|music://youtube/a"))
        assertEquals(false, isNeteaseManagedSourceStableKey("not-a-key"))
        assertEquals(
            "NeteaseRecords",
            normalizeLocalAlbumIdentity(
                album = "NeteaseRecords",
                usesFallbackAlbum = false,
                stripManagedSourcePrefix = isNeteaseManagedSourceStableKey(
                    "123|__local_files__|/Music/a.flac"
                )
            )
        )
    }

    @Test
    fun `fallback album remains the local identity`() {
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("Netease", true)
        )
    }
}
