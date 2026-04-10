package moe.ouom.neriplayer.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicLyricTimingTest {

    @Test
    fun `findCurrentLineIndex uses nearest previous line`() {
        val lyrics = listOf(
            LyricEntry(text = "A", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "B", startTimeMs = 3_000L, endTimeMs = 4_000L),
            LyricEntry(text = "C", startTimeMs = 5_000L, endTimeMs = 6_000L)
        )

        assertEquals(-1, findCurrentLineIndex(emptyList(), 0L))
        assertEquals(0, findCurrentLineIndex(lyrics, 0L))
        assertEquals(0, findCurrentLineIndex(lyrics, 1_000L))
        assertEquals(0, findCurrentLineIndex(lyrics, 2_999L))
        assertEquals(1, findCurrentLineIndex(lyrics, 3_000L))
        assertEquals(2, findCurrentLineIndex(lyrics, 8_000L))
    }

    @Test
    fun `shouldSnapLyricTimeSmoothing only animates small forward deltas`() {
        assertFalse(shouldSnapLyricTimeSmoothing(displayedTimeMs = 1_000L, targetTimeMs = 1_080L))
        assertTrue(shouldSnapLyricTimeSmoothing(displayedTimeMs = 1_000L, targetTimeMs = 1_400L))
        assertTrue(shouldSnapLyricTimeSmoothing(displayedTimeMs = 1_000L, targetTimeMs = 900L))
    }

    @Test
    fun `findBestMatchingTranslation keeps shared boundary aligned to current line`() {
        val translations = listOf(
            LyricEntry(text = "我们有一整个周末", startTimeMs = 18_090L, endTimeMs = 22_620L),
            LyricEntry(text = "撕碎它", startTimeMs = 22_620L, endTimeMs = 24_630L)
        )

        val matched = findBestMatchingTranslation(
            translations = translations,
            lineStartMs = 18_090L,
            lineEndMs = 22_620L
        )

        assertEquals("我们有一整个周末", matched?.text)
    }

    @Test
    fun `matchTranslationsToLineIndices keeps sparse translation on its nearest line only`() {
        val lyrics = listOf(
            LyricEntry(text = "My Baby", startTimeMs = 29_440L, endTimeMs = 30_180L),
            LyricEntry(text = "Let It Go", startTimeMs = 30_180L, endTimeMs = 30_860L),
            LyricEntry(text = "我们去过的每个角落像寄托", startTimeMs = 30_860L, endTimeMs = 32_780L),
            LyricEntry(text = "那我们也笑过", startTimeMs = 32_780L, endTimeMs = 33_950L),
            LyricEntry(text = "那逝去的生活的每个片段叫我如何删减", startTimeMs = 33_950L, endTimeMs = 37_080L)
        )
        val translations = listOf(
            LyricEntry(text = "我的宝贝", startTimeMs = 29_440L, endTimeMs = 30_180L),
            LyricEntry(text = "放手吧", startTimeMs = 30_180L, endTimeMs = 56_040L)
        )

        val matchedTranslations = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("我的宝贝", matchedTranslations[0]?.text)
        assertEquals("放手吧", matchedTranslations[1]?.text)
        assertEquals(null, matchedTranslations[2]?.text)
        assertEquals(null, matchedTranslations[3]?.text)
        assertEquals(null, matchedTranslations[4]?.text)
    }
}
