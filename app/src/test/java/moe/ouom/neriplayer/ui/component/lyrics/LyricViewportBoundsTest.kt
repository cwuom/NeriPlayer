package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricViewportBoundsTest {

    @Test
    fun `auto scroll ignores line indexes outside the lyric list`() {
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = -1,
                lyricsSize = 5,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 5,
                lyricsSize = 5,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 0,
                lyricsSize = 0,
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false
            )
        )
    }

    @Test
    fun `auto scroll moves a viewport anchored elsewhere or partially scrolled`() {
        assertTrue(
            shouldAutoScrollLyricViewport(
                currentIndex = 4,
                lyricsSize = 5,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false
            )
        )
        assertTrue(
            shouldAutoScrollLyricViewport(
                currentIndex = 0,
                lyricsSize = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 24,
                isUserInteracting = false
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 0,
                lyricsSize = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 4,
                lyricsSize = 5,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = true
            )
        )
    }

    @Test
    fun `overflow padding is zero for unusable widths`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -20f).forEach { width ->
            assertEquals(
                0f,
                resolveEmbeddedLyricHorizontalOverflowPadding(
                    maxTextWidth = width.dp,
                    maxLineScale = 1.2f
                ).value,
                0f
            )
        }
    }

    @Test
    fun `overflow padding is zero when lines never grow beyond their width`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, 0.9f).forEach { scale ->
            assertEquals(
                0f,
                resolveEmbeddedLyricHorizontalOverflowPadding(
                    maxTextWidth = 300.dp,
                    maxLineScale = scale
                ).value,
                0f
            )
        }
    }

    @Test
    fun `overflow padding splits the scaled growth across both sides`() {
        assertEquals(
            30f,
            resolveEmbeddedLyricHorizontalOverflowPadding(
                maxTextWidth = 300.dp,
                maxLineScale = 1.2f
            ).value,
            0.001f
        )
    }

    @Test
    fun `blank matched translation falls back to the embedded translation`() {
        val line = LyricEntry(
            text = "Lyric",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            translation = "Embedded"
        )
        val blankMatch = LyricEntry(text = "  ", startTimeMs = 0L, endTimeMs = 1_000L)

        assertEquals("Embedded", resolveLyricTranslationText(line, blankMatch, true))
        assertNull(resolveLyricTranslationText(line, blankMatch, false))
    }

    @Test
    fun `matched translation is shown even when embedded translations are hidden`() {
        val line = LyricEntry(text = "Lyric", startTimeMs = 0L, endTimeMs = 1_000L)
        val match = LyricEntry(text = "Matched", startTimeMs = 0L, endTimeMs = 1_000L)

        assertEquals("Matched", resolveLyricTranslationText(line, match, false))
        assertNull(resolveLyricTranslationText(line, null, true))
    }
}
