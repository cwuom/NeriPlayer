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
    fun `parseNeteaseLrc accepts legacy colon millisecond timestamps`() {
        val lyrics = parseNeteaseLrc(
            """
            [00:00:15]The sky blue archive!
            [00:12:76]新しい景色が見たくて自転車を漕いだ
            """.trimIndent()
        )

        assertEquals(2, lyrics.size)
        assertEquals("The sky blue archive!", lyrics[0].text)
        assertEquals(150L, lyrics[0].startTimeMs)
        assertEquals("新しい景色が見たくて自転車を漕いだ", lyrics[1].text)
        assertEquals(12_760L, lyrics[1].startTimeMs)
    }

    @Test
    fun `lyricListItemKey stays unique for duplicate metadata lines`() {
        val duplicateLine = LyricEntry(text = "BPM：180", startTimeMs = 0L, endTimeMs = 0L)
        val keys = listOf(
            lyricListItemKey(index = 0, line = duplicateLine),
            lyricListItemKey(index = 1, line = duplicateLine)
        )

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `shouldSnapLyricTimeSmoothing only animates small forward deltas`() {
        assertFalse(
            shouldSnapLyricTimeSmoothing(
                displayedTimeMs = 1_000L,
                targetTimeMs = 1_180L
            )
        )
        assertTrue(
            shouldSnapLyricTimeSmoothing(
                displayedTimeMs = 1_000L,
                targetTimeMs = 1_181L
            )
        )
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
    fun `findBestMatchingTranslation still prefers actual overlap when start delta is large`() {
        val translations = listOf(
            LyricEntry(text = "重叠翻译", startTimeMs = 0L, endTimeMs = 1_500L)
        )

        val matched = findBestMatchingTranslation(
            translations = translations,
            lineStartMs = 1_000L,
            lineEndMs = 2_000L
        )

        assertEquals("重叠翻译", matched?.text)
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

    @Test
    fun `cover lyric translation matcher does not reuse long translation for later lines`() {
        val lyrics = listOf(
            LyricEntry(text = "My Baby", startTimeMs = 29_440L, endTimeMs = 30_180L),
            LyricEntry(text = "Let It Go", startTimeMs = 30_180L, endTimeMs = 30_860L),
            LyricEntry(text = "我们去过的每个角落像寄托", startTimeMs = 30_860L, endTimeMs = 32_780L)
        )
        val translations = listOf(
            LyricEntry(text = "我的宝贝", startTimeMs = 29_440L, endTimeMs = 30_180L),
            LyricEntry(text = "放手吧", startTimeMs = 30_180L, endTimeMs = 56_040L)
        )

        val matchedTranslations = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("我的宝贝", matchedTranslations[0]?.text)
        assertEquals("放手吧", matchedTranslations[1]?.text)
        assertEquals(null, matchedTranslations[2]?.text)
    }

    @Test
    fun `matchTranslationsToLineIndices keeps early overlapping translation on current line`() {
        val lyrics = listOf(
            LyricEntry(text = "The road gets cold", startTimeMs = 68_020L, endTimeMs = 70_480L),
            LyricEntry(text = "There's no spring in the middle this year", startTimeMs = 70_510L, endTimeMs = 75_250L)
        )
        val translations = listOf(
            LyricEntry(text = "踽踽独行 长路孤冷", startTimeMs = 66_440L, endTimeMs = 70_600L),
            LyricEntry(text = "已然初夏 春光还迟迟未来", startTimeMs = 70_600L, endTimeMs = 75_390L)
        )

        val matchedTranslations = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("踽踽独行 长路孤冷", matchedTranslations[0]?.text)
        assertEquals("已然初夏 春光还迟迟未来", matchedTranslations[1]?.text)
    }

    @Test
    fun `resolveHeadGlowTarget keeps glow on current line when next char wraps`() {
        val target = resolveHeadGlowTarget(
            currentLine = 0,
            nextLine = 1,
            currentLineRight = 180f,
            currentLineCenterY = 24f,
            nextCharLeft = 36f,
            nextLineCenterY = 52f
        )

        assertEquals(180f, target.x)
        assertEquals(24f, target.y)
    }

    @Test
    fun `resolveHeadGlowTarget follows next char when wrap does not happen`() {
        val target = resolveHeadGlowTarget(
            currentLine = 0,
            nextLine = 0,
            currentLineRight = 180f,
            currentLineCenterY = 24f,
            nextCharLeft = 96f,
            nextLineCenterY = 24f
        )

        assertEquals(96f, target.x)
        assertEquals(24f, target.y)
    }
}
