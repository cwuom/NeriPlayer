package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSongAlbumDisplayTest {

    @Test
    fun `local download source prefix is removed from album identity`() {
        assertEquals("收敛", normalizeLocalAlbumIdentity("Netease收敛", false))
        assertEquals("2:3", normalizeLocalAlbumIdentity("Netease2:3", false))
        assertEquals("专辑", normalizeLocalAlbumIdentity("Netease:专辑", false))
        assertEquals("专辑", normalizeLocalAlbumIdentity("Netease|专辑", false))
    }

    @Test
    fun `source-only album falls back to local identity`() {
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("Netease", false)
        )
    }

    @Test
    fun `album names with a separating space are preserved`() {
        assertEquals("Netease Album", normalizeLocalAlbumIdentity("Netease Album", false))
    }

    @Test
    fun `fallback album remains the local identity`() {
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("Netease", true)
        )
    }
}
