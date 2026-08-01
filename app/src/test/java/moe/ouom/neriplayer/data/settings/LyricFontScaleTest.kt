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

    @Test
    fun resolveLyricFontScales_usesLegacyValueForUnsetPageSettings() {
        val scales = resolveLyricFontScales(
            legacyScale = 1.2f,
            coverLyric = null,
            coverTranslation = null,
            lyricsPageLyric = null,
            lyricsPageTranslation = null
        )

        assertEquals(1.2f, scales.coverLyric, 0.0001f)
        assertEquals(1.2f, scales.coverTranslation, 0.0001f)
        assertEquals(1.2f, scales.lyricsPageLyric, 0.0001f)
        assertEquals(1.2f, scales.lyricsPageTranslation, 0.0001f)
    }

    @Test
    fun resolveLyricFontScales_keepsEachPageAndTranslationScaleIndependent() {
        val scales = resolveLyricFontScales(
            legacyScale = 1.0f,
            coverLyric = 0.6f,
            coverTranslation = 0.8f,
            lyricsPageLyric = 1.3f,
            lyricsPageTranslation = 2f
        )

        assertEquals(0.6f, scales.scaleFor(LyricFontScaleTarget.COVER_LYRIC), 0.0001f)
        assertEquals(0.8f, scales.scaleFor(LyricFontScaleTarget.COVER_TRANSLATION), 0.0001f)
        assertEquals(1.3f, scales.scaleFor(LyricFontScaleTarget.LYRICS_PAGE_LYRIC), 0.0001f)
        assertEquals(MAX_LYRIC_FONT_SCALE, scales.scaleFor(LyricFontScaleTarget.LYRICS_PAGE_TRANSLATION), 0.0001f)
    }

    @Test
    fun lyricFontScales_resolvesSeparateTargetsForEachPage() {
        val scales = LyricFontScales(
            coverLyric = 0.9f,
            coverTranslation = 0.8f,
            lyricsPageLyric = 1.2f,
            lyricsPageTranslation = 1.1f
        )

        assertEquals(LyricFontScaleTarget.COVER_LYRIC, scales.lyricTargetFor(LyricFontScalePage.COVER))
        assertEquals(LyricFontScaleTarget.COVER_TRANSLATION, scales.translationTargetFor(LyricFontScalePage.COVER))
        assertEquals(LyricFontScaleTarget.LYRICS_PAGE_LYRIC, scales.lyricTargetFor(LyricFontScalePage.LYRICS))
        assertEquals(
            LyricFontScaleTarget.LYRICS_PAGE_TRANSLATION,
            scales.translationTargetFor(LyricFontScalePage.LYRICS)
        )
    }
}
