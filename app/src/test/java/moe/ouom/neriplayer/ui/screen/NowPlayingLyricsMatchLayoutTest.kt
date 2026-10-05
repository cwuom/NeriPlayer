package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.shouldScrollWholeLyricMatchContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsMatchLayoutTest {
    @Test
    fun `short landscape and keyboard budgets scroll controls with results`() {
        listOf(180.dp, 240.dp, 360.dp).forEach {
            assertTrue(shouldScrollWholeLyricMatchContent(it, 1.3f))
        }
    }

    @Test
    fun `large font switches to scroll before controls squeeze results`() {
        assertFalse(shouldScrollWholeLyricMatchContent(800.dp, 1.3f))
        assertTrue(shouldScrollWholeLyricMatchContent(800.dp, 1.5f))
    }

    @Test
    fun `tall portrait tablets keep pinned controls`() {
        assertFalse(shouldScrollWholeLyricMatchContent(1280.dp, 1.5f))
    }
}
