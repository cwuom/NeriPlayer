package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedLyricsViewportGuardTest {

    @Test
    fun `auto scroll ignores line indexes outside the lyric list`() {
        assertFalse(autoScroll(currentIndex = -1))
        assertFalse(autoScroll(currentIndex = 5))
        assertTrue(autoScroll(currentIndex = 4))
    }

    @Test
    fun `overflow padding is zero for non finite or non positive widths`() {
        listOf(Float.NaN.dp, Dp.Infinity, 0.dp, (-12).dp).forEach { width ->
            assertEquals(
                width.toString(),
                0f,
                resolveEmbeddedLyricHorizontalOverflowPadding(width, maxLineScale = 1.2f).value,
                0f
            )
        }
    }

    @Test
    fun `overflow padding is zero unless the line actually grows`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 1f, 0.8f).forEach { scale ->
            assertEquals(
                scale.toString(),
                0f,
                resolveEmbeddedLyricHorizontalOverflowPadding(200.dp, maxLineScale = scale).value,
                0f
            )
        }
        assertEquals(
            10f,
            resolveEmbeddedLyricHorizontalOverflowPadding(200.dp, maxLineScale = 1.1f).value,
            0.001f
        )
    }

    @Test
    fun `blank matched translation falls back to the embedded translation`() {
        val line = LyricEntry(
            text = "今天天气很好",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            translation = "Nice weather today"
        )

        assertEquals(
            "Nice weather today",
            resolveLyricTranslationText(line, matchedTranslation = line.copy(text = "  "), showEmbeddedTranslations = true)
        )
        assertNull(
            resolveLyricTranslationText(
                line.copy(translation = null),
                matchedTranslation = null,
                showEmbeddedTranslations = true
            )
        )
    }

    private fun autoScroll(currentIndex: Int) = shouldAutoScrollLyricViewport(
        currentIndex = currentIndex,
        lyricsSize = 5,
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0,
        isUserInteracting = false
    )
}
