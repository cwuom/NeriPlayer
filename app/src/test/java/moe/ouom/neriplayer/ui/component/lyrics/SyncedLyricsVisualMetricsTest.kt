package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedLyricsVisualMetricsTest {

    private val spec = LyricVisualSpec()

    @Test
    fun `line height scales valid font sizes and falls back to the reference size`() {
        assertEquals(20.sp, resolveLyricLineHeight(16.sp, 1.25f))
        assertEquals(1.5.em, resolveLyricLineHeight(1.em, 1.5f))
        assertEquals(20.sp, resolveLyricLineHeight(TextUnit.Unspecified, 1.25f))
        assertEquals(20.sp, resolveLyricLineHeight(0.sp, 1.25f))
        assertEquals(20.sp, resolveLyricLineHeight((-4).sp, 1.25f))
    }

    @Test
    fun `scale shrinks with distance down to the far minimum`() {
        assertEquals(spec.activeScale, scaleForDistance(0, spec), 0f)
        assertEquals(spec.activeScale, scaleForDistance(-1, spec), 0f)
        assertEquals(spec.nearScale, scaleForDistance(1, spec), 0f)
        assertEquals(spec.farScale, scaleForDistance(2, spec), 0f)
        assertEquals(0.86f, scaleForDistance(3, spec), 0.0001f)
        assertEquals(spec.farScaleMin, scaleForDistance(40, spec), 0f)
    }

    @Test
    fun `alpha fades with distance but never below the readable floor`() {
        assertEquals(0.7f, alphaForDistance(1, near = 0.7f, far = 0.4f), 0f)
        assertEquals(0.4f, alphaForDistance(2, near = 0.7f, far = 0.4f), 0f)
        assertEquals(0.32f, alphaForDistance(3, near = 0.7f, far = 0.4f), 0.0001f)
        assertEquals(0.16f, alphaForDistance(20, near = 0.7f, far = 0.4f), 0f)
    }

    @Test
    fun `blur grows in steps for the first four neighbours then saturates`() {
        assertEquals(10f, blurForDistance(1, 10f), 0f)
        assertEquals(15f, blurForDistance(2, 10f), 0f)
        assertEquals(20f, blurForDistance(3, 10f), 0f)
        assertEquals(25f, blurForDistance(4, 10f), 0f)
        assertEquals(40f, blurForDistance(5, 10f), 0f)
    }

    @Test
    fun `kana detection covers hiragana katakana extensions and halfwidth forms only`() {
        assertTrue(containsJapaneseKana("あ"))
        assertTrue(containsJapaneseKana("カ"))
        assertTrue(containsJapaneseKana("\u31F0"))
        assertTrue(containsJapaneseKana("ｱ"))
        assertTrue(containsJapaneseKana("漢字と"))

        assertFalse(containsJapaneseKana(""))
        assertFalse(containsJapaneseKana("Hello"))
        assertFalse(containsJapaneseKana("\u303F"))
        assertFalse(containsJapaneseKana("\u3100"))
        assertFalse(containsJapaneseKana("\u31EF"))
        assertFalse(containsJapaneseKana("\u3200"))
        assertFalse(containsJapaneseKana("\uFF65"))
        assertFalse(containsJapaneseKana("\uFFA0"))
    }

    @Test
    fun `reveal clip falls back to the line extent when the container is unknown`() {
        val bounds = resolveLyricRevealClipBounds(
            lineLeft = 10f,
            lineRight = 50f,
            horizontalBleedPx = 4f,
            containerWidth = 0f
        )

        assertEquals(6f, bounds.left, 0f)
        assertEquals(50f, bounds.right, 0f)
    }

    @Test
    fun `reveal clip collapses inverted lines and ignores invalid bleed`() {
        val bounds = resolveLyricRevealClipBounds(
            lineLeft = 30f,
            lineRight = 20f,
            horizontalBleedPx = Float.NaN,
            containerWidth = Float.NaN
        )

        assertEquals(30f, bounds.left, 0f)
        assertEquals(30f, bounds.right, 0f)
    }

    @Test
    fun `reveal clip keeps overflowing lines inside the container`() {
        val bounds = resolveLyricRevealClipBounds(
            lineLeft = -5f,
            lineRight = 120f,
            horizontalBleedPx = -1f,
            containerWidth = 100f
        )

        assertEquals(0f, bounds.left, 0f)
        assertEquals(100f, bounds.right, 0f)
    }

    @Test
    fun `draw time reveal prefers the composed offset over the interpolated position`() {
        val line = LyricEntry(text = "abcdefghij", startTimeMs = 0L, endTimeMs = 10_000L)
        val state = InterpolatedPlaybackPositionState(initialPositionMs = 4_000L)

        assertEquals(3f, resolveDrawTimeRevealOffsetChars(3f, line, state, lyricOffsetMs = 0L))
        assertEquals(5f, resolveDrawTimeRevealOffsetChars(null, line, state, lyricOffsetMs = 1_000L))
        assertNull(resolveDrawTimeRevealOffsetChars(null, null, state, lyricOffsetMs = 0L))
        assertNull(resolveDrawTimeRevealOffsetChars(null, line, null, lyricOffsetMs = 0L))
    }

    @Test
    fun `draw time reveal clamps the interpolated position to the line`() {
        val line = LyricEntry(text = "abcdefghij", startTimeMs = 0L, endTimeMs = 10_000L)
        val state = InterpolatedPlaybackPositionState(initialPositionMs = 200L)

        assertEquals(0f, resolveDrawTimeRevealOffsetChars(null, line, state, lyricOffsetMs = -500L))
        state.renderedPositionMs = 20_000L
        assertEquals(10f, resolveDrawTimeRevealOffsetChars(null, line, state, lyricOffsetMs = 0L))
    }

    @Test
    fun `translation gap uses whichever font size is valid`() {
        assertEquals(4.5.dp, resolveLyricTranslationGap(18.sp, TextUnit.Unspecified))
        assertEquals(3.5.dp, resolveLyricTranslationGap(0.sp, 14.sp))
    }

    @Test
    fun `translation gap clamps glyph coverage and font scale`() {
        assertEquals(
            3.dp,
            resolveLyricTranslationGap(
                lyricFontSize = 16.sp,
                translationFontSize = 16.sp,
                lyricGlyphCoverage = -1f,
                translationGlyphCoverage = -1f
            )
        )
        assertEquals(
            2.dp,
            resolveLyricTranslationGap(16.sp, 16.sp, fontScale = 0.05f)
        )
        assertEquals(
            8.dp,
            resolveLyricTranslationGap(16.sp, 16.sp, fontScale = 3f)
        )
    }
}
