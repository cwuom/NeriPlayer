package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricsEditorLayoutSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsEditorLayoutTest {
    @Test
    fun `short viewport compacts metadata without sacrificing editor height`() {
        val layout = resolveLyricsEditorLayoutSpec(360.dp, 1.3f)
        assertTrue(layout.compactHeader)
        assertFalse(layout.scrollWholeContent)
    }

    @Test
    fun `keyboard budget and large fonts scroll the entire editor`() {
        listOf(180.dp to 1f, 240.dp to 1.5f, 320.dp to 1.5f).forEach { (height, scale) ->
            val layout = resolveLyricsEditorLayoutSpec(height, scale)
            assertTrue(layout.compactHeader)
            assertTrue(layout.scrollWholeContent)
        }
    }

    @Test
    fun `portrait phone and tablet keep fixed actions and full metadata`() {
        listOf(800.dp, 1280.dp).forEach { height ->
            val layout = resolveLyricsEditorLayoutSpec(height, 1.5f)
            assertFalse(layout.compactHeader)
            assertFalse(layout.scrollWholeContent)
        }
    }
}
