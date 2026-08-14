package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedLyricsViewTimingTest {

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
    fun `parseNeteaseLyricsAuto preserves enhanced lrc word timing`() {
        val lyrics = parseNeteaseLyricsAuto(
            """
            [00:00.000] <00:00.000>夜<00:00.356>曲<00:00.712> <00:01.068>-<00:01.424> <00:01.780>周<00:02.136>杰<00:02.492>伦<00:02.848> <00:03.204>(<00:03.560>Jay<00:03.916> <00:04.272>Chou<00:04.628>)<00:04.990>
            [00:04.990] <00:04.990>词<00:05.988>：<00:06.986>方<00:07.984>文<00:08.982>山<00:09.980>
            """.trimIndent()
        )

        assertEquals(2, lyrics.size)
        assertEquals("夜曲 - 周杰伦 (Jay Chou)", lyrics[0].text)
        assertEquals(0L, lyrics[0].startTimeMs)
        assertEquals(4_990L, lyrics[0].endTimeMs)
        assertEquals(14, lyrics[0].words?.size)
        assertEquals(0L, lyrics[0].words?.first()?.startTimeMs)
        assertEquals(356L, lyrics[0].words?.first()?.endTimeMs)
        assertEquals(4, lyrics[0].words?.get(12)?.charCount)
        assertEquals(4_990L, lyrics[0].words?.last()?.endTimeMs)
        assertEquals(9_980L, lyrics[1].endTimeMs)
    }

    @Test
    fun `enhanced lrc falls back to the next line for a final word end time`() {
        val lyrics = parseNeteaseLrc(
            """
            [00:10.000]<00:10.000>Hello <00:10.500>world
            [00:12.000]<00:12.000>Next line<00:13.000>
            """.trimIndent()
        )

        assertEquals("Hello world", lyrics[0].text)
        assertEquals(12_000L, lyrics[0].endTimeMs)
        assertEquals(12_000L, lyrics[0].words?.last()?.endTimeMs)
    }

    @Test
    fun `parseNeteaseLrc trims credits after a terminal empty timestamp`() {
        val lyrics = parseNeteaseLrc(
            """
            [03:58.12]もっと、ちゃんと言って
            [04:00.67]
            [04:01.30]vo/mix：Neri
            [04:01.64]tune：Tsubaki椿
            [04:01.83]inst：Trebor_TTTTT
            [04:02.03]吉他solo：热闹的蛋白酥
            """.trimIndent()
        )

        assertEquals(listOf("もっと、ちゃんと言って"), lyrics.map { it.text })
        assertEquals(238_120L, lyrics.single().startTimeMs)
        assertEquals(240_670L, lyrics.single().endTimeMs)
    }

    @Test
    fun `parseNeteaseLrc keeps lyric lines after a non-terminal empty timestamp`() {
        val lyrics = parseNeteaseLrc(
            """
            [00:01.00]第一句
            [00:02.00]
            [00:03.00]第二句
            """.trimIndent()
        )

        assertEquals(listOf("第一句", "第二句"), lyrics.map { it.text })
        assertEquals(2_000L, lyrics[0].endTimeMs)
    }

    @Test
    fun `resolveLyricSeekPosition rejects positions at or after known duration`() {
        assertEquals(239_999L, resolveLyricSeekPosition(239_999L, 240_000L))
        assertEquals(null, resolveLyricSeekPosition(240_000L, 240_000L))
        assertEquals(240_000L, resolveLyricSeekPosition(240_000L, 0L))
        assertEquals(0L, resolveLyricSeekPosition(-1L, 240_000L))
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
    fun `embedded lyrics use a gradual edge fade while retaining a shorter full-page fade`() {
        assertEquals(56.dp, resolveLyricEdgeFadeHeight(isEmbedded = true))
        assertEquals(72.dp, resolveLyricEdgeFadeHeight(isEmbedded = false))
    }

    @Test
    fun `synced lyrics keep placement under scroll control`() {
        assertFalse(shouldAnimateLyricItemPlacement())
    }

    @Test
    fun `song change owns the embedded lyric scroll session`() {
        val lyrics = listOf(
            LyricEntry(text = "A", startTimeMs = 0L, endTimeMs = 1_000L)
        )

        assertEquals("song-b", resolveLyricScrollSessionKey("song-b", lyrics))
        assertEquals(lyrics, resolveLyricScrollSessionKey(null, lyrics))
    }

    @Test
    fun `stable lyric viewport starts on the active line`() {
        assertEquals(
            3,
            resolveInitialLyricScrollIndex(
                currentIndex = 3,
                lyricsSize = 6,
                stabilizeViewport = true
            )
        )
        assertEquals(
            0,
            resolveInitialLyricScrollIndex(
                currentIndex = 3,
                lyricsSize = 6,
                stabilizeViewport = false
            )
        )
        assertEquals(
            0,
            resolveInitialLyricScrollIndex(
                currentIndex = -1,
                lyricsSize = 6,
                stabilizeViewport = true
            )
        )
    }

    @Test
    fun `auto scroll waits for the user gesture to settle`() {
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 2,
                lyricsSize = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = true
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 2,
                lyricsSize = 5,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false
            )
        )
        assertTrue(
            shouldAutoScrollLyricViewport(
                currentIndex = 2,
                lyricsSize = 5,
                firstVisibleItemIndex = 2,
                firstVisibleItemScrollOffset = 12,
                isUserInteracting = false
            )
        )
    }

    @Test
    fun `manual lyric scroll holds the viewport until playback advances`() {
        assertTrue(
            shouldHoldLyricViewportForManualScroll(
                manualScrollAnchorIndex = 2,
                currentIndex = 2
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 2,
                lyricsSize = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false,
                manualScrollAnchorIndex = 2
            )
        )
        assertFalse(
            shouldAutoScrollLyricViewport(
                currentIndex = 3,
                lyricsSize = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = true,
                manualScrollAnchorIndex = 2
            )
        )
        assertTrue(
            shouldAutoScrollLyricViewport(
                currentIndex = 3,
                lyricsSize = 5,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                isUserInteracting = false,
                manualScrollAnchorIndex = 2
            )
        )
    }

    @Test
    fun `manual translation reveal uses the dedicated transition mode`() {
        assertEquals(
            LyricTranslationTransitionMode.MANUAL_EXPANSION,
            resolveLyricTranslationTransitionMode(isManualReveal = true)
        )
        assertEquals(
            LyricTranslationTransitionMode.PLAYBACK_CHANGE,
            resolveLyricTranslationTransitionMode(isManualReveal = false)
        )
    }

    @Test
    fun `stale lyric auto scroll cannot clear the latest scroll state`() {
        val staleTarget = LyricAutoScrollTarget(lineIndex = 1, lyricsSize = 4)
        val latestTarget = LyricAutoScrollTarget(lineIndex = 2, lyricsSize = 4)

        assertFalse(shouldFinishLyricAutoScroll(staleTarget, latestTarget))
        assertTrue(shouldFinishLyricAutoScroll(latestTarget, latestTarget))
    }

    @Test
    fun `embedded lyric scale stays subtle across active state`() {
        assertEquals(1.025f, resolveEmbeddedLyricScale(isActive = true), 0f)
        assertEquals(0.985f, resolveEmbeddedLyricScale(isActive = false), 0f)
    }

    @Test
    fun `embedded active lyric reserves horizontal room for its scaled glyphs`() {
        assertEquals(
            5f,
            resolveEmbeddedLyricHorizontalOverflowPadding(
                maxTextWidth = 400.dp,
                maxLineScale = resolveEmbeddedLyricScale(isActive = true)
            ).value,
            0.001f
        )
        assertEquals(
            0f,
            resolveEmbeddedLyricHorizontalOverflowPadding(
                maxTextWidth = 400.dp,
                maxLineScale = 1f
            ).value,
            0f
        )
    }

    @Test
    fun `active lyric reveal clip keeps horizontal glyph bleed inside bounds`() {
        val bounds = resolveLyricRevealClipBounds(
            lineLeft = 8f,
            lineRight = 92f,
            horizontalBleedPx = 4f,
            containerWidth = 100f
        )

        assertEquals(4f, bounds.left, 0f)
        assertEquals(96f, bounds.right, 0f)
        assertEquals(4f, resolveActiveLyricRevealHorizontalPadding().value, 0f)
    }

    @Test
    fun `manual lyric presentation transitions between playback and clear states`() {
        assertEquals(1f, resolveLyricClearPresentationTarget(true), 0f)
        assertEquals(0f, resolveLyricClearPresentationTarget(false), 0f)
    }

    @Test
    fun `translation scale uses a centered upper anchor`() {
        val transformOrigin = resolveEmbeddedTranslationTransformOrigin()

        assertEquals(0.5f, transformOrigin.pivotFractionX, 0f)
        assertEquals(0.35f, transformOrigin.pivotFractionY, 0f)
    }

    @Test
    fun `translation gap follows both font sizes and stays bounded`() {
        assertEquals(4.dp, resolveLyricTranslationGap(18.sp, 14.sp))
        assertEquals(
            3.dp,
            resolveLyricTranslationGap(
                lyricFontSize = 18.sp,
                translationFontSize = 14.sp,
                lyricGlyphCoverage = 0.75f,
                translationGlyphCoverage = 0.75f
            )
        )
        assertEquals(2.dp, resolveLyricTranslationGap(9.sp, 7.sp))
        assertEquals(6.dp, resolveLyricTranslationGap(28.sp, 20.sp))
        assertEquals(8.dp, resolveLyricTranslationGap(48.sp, 32.sp))
    }

    @Test
    fun `translation gap ignores invalid font sizes`() {
        assertEquals(
            4.dp,
            resolveLyricTranslationGap(TextUnit.Unspecified, TextUnit.Unspecified)
        )
        assertEquals(3.5.dp, resolveLyricTranslationGap(TextUnit.Unspecified, 14.sp))
    }

    @Test
    fun `lyrics page Japanese lyric translation gets extra gap`() {
        assertEquals(3f, resolveLyricTranslationExtraGap("昨日の僕守る為に", true).value, 0f)
        assertEquals(0f, resolveLyricTranslationExtraGap("昨日の僕守る為に", false).value, 0f)
        assertEquals(0f, resolveLyricTranslationExtraGap("只是为了守护昨天的我", true).value, 0f)
        assertEquals(0f, resolveLyricTranslationExtraGap("Let it rain", true).value, 0f)
    }

    @Test
    fun `embedded translation is used when no separate translation line matches`() {
        val line = LyricEntry(
            text = "今日は晴れ",
            startTimeMs = 1_000L,
            endTimeMs = 2_000L,
            translation = "今天放晴"
        )

        assertEquals("今天放晴", resolveLyricTranslationText(line, null, true))
        assertEquals(null, resolveLyricTranslationText(line, null, false))
        assertEquals(
            "外部翻译",
            resolveLyricTranslationText(
                line = line,
                matchedTranslation = LyricEntry(
                    text = "外部翻译",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_000L
                ),
                showEmbeddedTranslations = true
            )
        )
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
    fun `cover lyric translation matcher accepts moderate timestamp drift`() {
        val lyrics = listOf(
            LyricEntry(text = "The road gets cold", startTimeMs = 2_000L, endTimeMs = 2_600L)
        )
        val translations = listOf(
            LyricEntry(text = "长路渐冷", startTimeMs = 800L, endTimeMs = 1_400L)
        )

        val matchedTranslations = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("长路渐冷", matchedTranslations[0]?.text)
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
