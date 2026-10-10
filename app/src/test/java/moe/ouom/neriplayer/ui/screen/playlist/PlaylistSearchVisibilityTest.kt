package moe.ouom.neriplayer.ui.screen.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSearchVisibilityTest {

    @Test
    fun `requested search is shown outside selection mode`() {
        assertTrue(shouldShowPlaylistSearch(showSearch = true, selectionMode = false))
    }

    @Test
    fun `selection mode hides a requested search`() {
        assertFalse(shouldShowPlaylistSearch(showSearch = true, selectionMode = true))
    }

    @Test
    fun `search stays hidden until it is requested`() {
        assertFalse(shouldShowPlaylistSearch(showSearch = false, selectionMode = false))
        assertFalse(shouldShowPlaylistSearch(showSearch = false, selectionMode = true))
    }
}
