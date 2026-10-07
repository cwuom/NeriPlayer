package moe.ouom.neriplayer.data.model.settings.lyrics

import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSettingsModelsTest {

    @Test
    fun `font scales are looked up per page and role`() {
        val scales = LyricFontScales(
            coverLyric = 1.1f,
            coverTranslation = 0.9f,
            lyricsPageLyric = 1.3f,
            lyricsPageTranslation = 0.8f
        )

        assertEquals(1.1f, scales.scaleFor(scales.lyricTargetFor(LyricFontScalePage.COVER)), 0f)
        assertEquals(0.9f, scales.scaleFor(scales.translationTargetFor(LyricFontScalePage.COVER)), 0f)
        assertEquals(1.3f, scales.scaleFor(scales.lyricTargetFor(LyricFontScalePage.LYRICS)), 0f)
        assertEquals(0.8f, scales.scaleFor(scales.translationTargetFor(LyricFontScalePage.LYRICS)), 0f)
    }

    @Test
    fun `every fixed lyric source maps to its editable match source`() {
        assertEquals(
            listOf(
                null,
                EditableLyricMatchSource.CLOUD_MUSIC,
                EditableLyricMatchSource.KUGOU,
                EditableLyricMatchSource.QQ_MUSIC,
                EditableLyricMatchSource.LRCLIB,
                EditableLyricMatchSource.AMLL_TTML
            ),
            LyricSourcePreference.entries.map { it.matchSource }
        )
    }
}
