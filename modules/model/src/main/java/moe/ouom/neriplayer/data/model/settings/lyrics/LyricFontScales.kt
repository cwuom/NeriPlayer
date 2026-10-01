package moe.ouom.neriplayer.data.model.settings.lyrics

enum class LyricFontScaleTarget {
    COVER_LYRIC,
    COVER_TRANSLATION,
    LYRICS_PAGE_LYRIC,
    LYRICS_PAGE_TRANSLATION
}

enum class LyricFontScalePage {
    COVER,
    LYRICS
}

data class LyricFontScales(
    val coverLyric: Float,
    val coverTranslation: Float,
    val lyricsPageLyric: Float,
    val lyricsPageTranslation: Float
) {
    fun scaleFor(target: LyricFontScaleTarget): Float = when (target) {
        LyricFontScaleTarget.COVER_LYRIC -> coverLyric
        LyricFontScaleTarget.COVER_TRANSLATION -> coverTranslation
        LyricFontScaleTarget.LYRICS_PAGE_LYRIC -> lyricsPageLyric
        LyricFontScaleTarget.LYRICS_PAGE_TRANSLATION -> lyricsPageTranslation
    }

    fun lyricTargetFor(page: LyricFontScalePage): LyricFontScaleTarget = when (page) {
        LyricFontScalePage.COVER -> LyricFontScaleTarget.COVER_LYRIC
        LyricFontScalePage.LYRICS -> LyricFontScaleTarget.LYRICS_PAGE_LYRIC
    }

    fun translationTargetFor(page: LyricFontScalePage): LyricFontScaleTarget = when (page) {
        LyricFontScalePage.COVER -> LyricFontScaleTarget.COVER_TRANSLATION
        LyricFontScalePage.LYRICS -> LyricFontScaleTarget.LYRICS_PAGE_TRANSLATION
    }
}
