package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistSelectionTopBarColor
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistTranslucentTopBarColor
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistHeroTopBarColorTest {

    @Test
    fun `expanded hero chrome keeps the solid cover color`() {
        assertEquals(1f, resolvePlaylistTranslucentTopBarColor(Color.Red, 0f).alpha, 0.01f)
        assertEquals(1f, resolvePlaylistSelectionTopBarColor(Color.Red, 0.5f).alpha, 0.01f)
    }

    @Test
    fun `collapsed chrome fades out with scroll`() {
        assertEquals(0.5f, resolvePlaylistTranslucentTopBarColor(Color.Red, 0.5f).alpha, 0.01f)
        assertEquals(0f, resolvePlaylistSelectionTopBarColor(Color.Red, 1f).alpha, 0.01f)
    }
}
