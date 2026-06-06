package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricFontScaleTest {

    @Test
    fun normalizeLyricFontScale_clampsOutOfRangeValues() {
        assertEquals(MIN_LYRIC_FONT_SCALE, normalizeLyricFontScale(0.1f), 0.0001f)
        assertEquals(1.0f, normalizeLyricFontScale(1.0f), 0.0001f)
        assertEquals(MAX_LYRIC_FONT_SCALE, normalizeLyricFontScale(2.0f), 0.0001f)
    }

    @Test
    fun scaledLyricFontSize_usesFullSliderRange() {
        assertEquals(9f, scaledLyricFontSize(18f, MIN_LYRIC_FONT_SCALE), 0.0001f)
        assertEquals(28.8f, scaledLyricFontSize(18f, MAX_LYRIC_FONT_SCALE), 0.0001f)
        assertEquals(10f, scaledLyricFontSize(20f, MIN_LYRIC_FONT_SCALE), 0.0001f)
    }
}
