package moe.ouom.neriplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeriAppLocalPlaylistRouteTest {

    @Test
    fun `local playlist detail route resolves its playback statistics source`() {
        assertEquals(42L, localPlaylistIdFromSourceRoute("local_playlist_detail/42"))
    }

    @Test
    fun `other and malformed routes do not claim a local playlist source`() {
        assertNull(localPlaylistIdFromSourceRoute("playlist_detail/42"))
        assertNull(localPlaylistIdFromSourceRoute("local_playlist_detail/not-a-number"))
        assertNull(localPlaylistIdFromSourceRoute(null))
    }
}
