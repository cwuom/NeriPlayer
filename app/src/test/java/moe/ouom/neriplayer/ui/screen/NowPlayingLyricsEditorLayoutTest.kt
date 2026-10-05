package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveLyricsEditorLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorSection
import org.junit.Assert.assertEquals
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

    @Test
    fun `compact and expanded metadata retain their original order and spacing`() {
        val compact = resolveLyricsEditorLayoutSpec(360.dp, 1.3f)
        assertEquals(listOf(LyricsEditorSection.HEADER, LyricsEditorSection.TABS,
            LyricsEditorSection.INPUT, LyricsEditorSection.ACTIONS), compact.sections)
        assertEquals(8.dp, compact.spacing)
        assertEquals(16.dp, compact.horizontalPadding)
        val expanded = resolveLyricsEditorLayoutSpec(800.dp, 1.5f)
        assertEquals(listOf(LyricsEditorSection.HEADER, LyricsEditorSection.SONG_INFO,
            LyricsEditorSection.TABS, LyricsEditorSection.INPUT, LyricsEditorSection.ACTIONS), expanded.sections)
        assertEquals(12.dp, expanded.spacing)
        assertEquals(24.dp, expanded.horizontalPadding)
    }

    @Test
    fun `layout thresholds reserve a full minimum input budget without rounding away the boundary`() {
        assertTrue(resolveLyricsEditorLayoutSpec(479.dp, 1f).compactHeader)
        assertFalse(resolveLyricsEditorLayoutSpec(480.dp, 1f).compactHeader)
        assertTrue(resolveLyricsEditorLayoutSpec(311.dp, 1f).scrollWholeContent)
        assertFalse(resolveLyricsEditorLayoutSpec(312.dp, 1f).scrollWholeContent)
        assertTrue(resolveLyricsEditorLayoutSpec(338.dp, 1.3f).scrollWholeContent)
        assertFalse(resolveLyricsEditorLayoutSpec(339.dp, 1.3f).scrollWholeContent)
        assertFalse(resolveLyricsEditorLayoutSpec(312.dp, 0.5f).scrollWholeContent)
        assertFalse(resolveLyricsEditorLayoutSpec(480.dp, 1.5f).scrollWholeContent)
    }
}
