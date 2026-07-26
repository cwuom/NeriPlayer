package moe.ouom.neriplayer.ui.component.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖歌词翻译配对的两个回归：
 * - Bug A：多行共享同一时间戳时翻译整体错位一行（元数据行窃取翻译）
 * - Bug B：没有真正翻译的歌把制作信息当成翻译显示
 */
class LyricTranslationMatcherTest {

    @Test
    fun `shared timestamp keeps translation on the real lyric line not the metadata line`() {
        // 出品/营销/OP 与正文行共享 15638ms，翻译应落在正文行而非最靠前的元数据行
        val lines = listOf(
            LyricEntry(text = "出品：网易飓风", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "营销：网易飓风", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "OP：唯迹文化", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "爱上了一个人眼睛不说谎", startTimeMs = 15_638L, endTimeMs = 18_891L),
            LyricEntry(text = "眼泪总偷偷的躲在眼眶", startTimeMs = 18_891L, endTimeMs = 23_891L)
        )
        val translations = listOf(
            LyricEntry(text = "爱上了译", startTimeMs = 15_638L, endTimeMs = 18_891L),
            LyricEntry(text = "眼泪译", startTimeMs = 18_891L, endTimeMs = 23_891L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        assertNull(matched[0])
        assertNull(matched[1])
        assertNull(matched[2])
        assertEquals("爱上了译", matched[3]?.text)
        assertEquals("眼泪译", matched[4]?.text)
    }

    @Test
    fun `mixed yrc and lrc lyrics align translations without shifting up one line`() {
        // 作词/作曲(逐字) + 共享时间戳的元数据 + 正文，翻译只覆盖正文
        val lines = listOf(
            LyricEntry(
                text = "作词: 罗言",
                startTimeMs = 0L,
                endTimeMs = 1_000L,
                words = listOf(WordTiming(startTimeMs = 0L, endTimeMs = 1_000L, charCount = 5))
            ),
            LyricEntry(
                text = "作曲: 罗言",
                startTimeMs = 1_000L,
                endTimeMs = 15_638L,
                words = listOf(WordTiming(startTimeMs = 1_000L, endTimeMs = 2_000L, charCount = 5))
            ),
            LyricEntry(text = "出品：网易飓风", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "营销：网易飓风", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "OP：唯迹文化", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "爱上了一个人眼睛不说谎", startTimeMs = 15_638L, endTimeMs = 18_891L),
            LyricEntry(text = "眼泪总偷偷的躲在眼眶", startTimeMs = 18_891L, endTimeMs = 22_000L)
        )
        val translations = listOf(
            LyricEntry(text = "爱上了译", startTimeMs = 15_638L, endTimeMs = 18_891L),
            LyricEntry(text = "眼泪译", startTimeMs = 18_891L, endTimeMs = 22_000L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        // 作词/作曲/出品/营销/OP 均无翻译
        for (index in 0..4) {
            assertNull("index=$index should have no translation", matched[index])
        }
        assertEquals("爱上了译", matched[5]?.text)
        assertEquals("眼泪译", matched[6]?.text)
    }

    @Test
    fun `multiple translations on shared timestamp map to the trailing lines in order`() {
        val lines = listOf(
            LyricEntry(text = "OP：唯迹文化", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "第一句正文", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "第二句正文", startTimeMs = 15_638L, endTimeMs = 18_891L),
            LyricEntry(text = "下一句", startTimeMs = 18_891L, endTimeMs = 22_000L)
        )
        val translations = listOf(
            LyricEntry(text = "第一句译", startTimeMs = 15_638L, endTimeMs = 15_638L),
            LyricEntry(text = "第二句译", startTimeMs = 15_638L, endTimeMs = 18_891L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        assertNull(matched[0])
        assertEquals("第一句译", matched[1]?.text)
        assertEquals("第二句译", matched[2]?.text)
        assertNull(matched[3])
    }

    @Test
    fun `translations fewer than original lines never leak onto unrelated lines`() {
        val lines = listOf(
            LyricEntry(text = "line1", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "line2", startTimeMs = 2_000L, endTimeMs = 3_000L),
            LyricEntry(text = "line3", startTimeMs = 3_000L, endTimeMs = 4_000L),
            LyricEntry(text = "line4", startTimeMs = 4_000L, endTimeMs = 5_000L)
        )
        val translations = listOf(
            LyricEntry(text = "trans1", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "trans3", startTimeMs = 3_000L, endTimeMs = 4_000L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        assertEquals("trans1", matched[0]?.text)
        assertNull(matched[1])
        assertEquals("trans3", matched[2]?.text)
        assertNull(matched[3])
    }

    @Test
    fun `metadata only translation produces no matches`() {
        // Bug B：整段翻译只有制作信息时不显示任何翻译
        val lines = listOf(
            LyricEntry(text = "第一句", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "第二句", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )
        val translations = listOf(
            LyricEntry(text = "作词 : 罗言", startTimeMs = 0L, endTimeMs = 1_000L),
            LyricEntry(text = "作曲 : 罗言", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "制作人 : 罗言", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        assertTrue(matched.isEmpty())
    }

    @Test
    fun `credit metadata is stripped while real translations stay aligned`() {
        val lines = listOf(
            LyricEntry(text = "lyric1", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "lyric2", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )
        val translations = listOf(
            LyricEntry(text = "作词 : 罗言", startTimeMs = 0L, endTimeMs = 1_000L),
            LyricEntry(text = "lyric1译", startTimeMs = 1_000L, endTimeMs = 2_000L),
            LyricEntry(text = "lyric2译", startTimeMs = 2_000L, endTimeMs = 3_000L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        assertEquals("lyric1译", matched[0]?.text)
        assertEquals("lyric2译", matched[1]?.text)
    }

    @Test
    fun `real yrc english lyric with sparse chinese translation aligns downward`() {
        // 用户真实样本 Keep Your Head Up：YRC 英文逐字原文 + 独立稀疏中文 LRC 翻译
        // 翻译只覆盖部分行，必须向下 carry-forward 精确对齐，不得错位/漏配/泄漏到无翻译行
        val lines = listOf(
            LyricEntry("When she was younger, she would pretend", 120L, 4980L),
            LyricEntry("That her bedroom was a castle", 5940L, 11190L),
            LyricEntry("And she got older, and it all changed", 11490L, 16230L),
            LyricEntry("There was no time for make believe", 16920L, 22260L),
            LyricEntry("Until the light in her eyes it was all but gone", 22620L, 27210L),
            LyricEntry("'Cause all the dreams that she had turned out to be wrong", 28200L, 32820L),
            LyricEntry("So keep your head up princess 'fore your crown falls", 34320L, 37230L),
            LyricEntry("Know these voices in your head will be your downfall", 37410L, 39930L),
            LyricEntry("I know it gets so hard but you don't got far to go", 40140L, 44010L)
        )
        val translations = listOf(
            LyricEntry("当她还是个小女孩 她终日幻想", 120L, 28200L),
            LyricEntry("童年的梦境变成愚蠢的错误", 28200L, 34320L),
            LyricEntry("亲爱的公主 抬起你的头 别让皇冠落下", 34320L, 37410L),
            LyricEntry("脑海中的那些声音只会阻碍你闪闪发光", 37410L, 40140L),
            LyricEntry("我知道此刻举步维艰 但你已经快要成功", 40140L, 45660L)
        )

        val matched = matchTranslationsToLineIndices(lines, translations)

        assertEquals("当她还是个小女孩 她终日幻想", matched[0]?.text)
        assertNull("index=1 无翻译", matched[1])
        assertNull("index=2 无翻译", matched[2])
        assertNull("index=3 无翻译", matched[3])
        assertNull("index=4 无翻译", matched[4])
        assertEquals("童年的梦境变成愚蠢的错误", matched[5]?.text)
        assertEquals("亲爱的公主 抬起你的头 别让皇冠落下", matched[6]?.text)
        assertEquals("脑海中的那些声音只会阻碍你闪闪发光", matched[7]?.text)
        assertEquals("我知道此刻举步维艰 但你已经快要成功", matched[8]?.text)
    }

    @Test
    fun `empty translation list yields empty result`() {
        val lines = listOf(
            LyricEntry(text = "line", startTimeMs = 1_000L, endTimeMs = 2_000L)
        )

        assertTrue(matchTranslationsToLineIndices(lines, emptyList()).isEmpty())
    }

    @Test
    fun `isLyricCreditMetadataLine detects known credit roles`() {
        assertTrue(isLyricCreditMetadataLine("作词 : 罗言"))
        assertTrue(isLyricCreditMetadataLine("作曲：罗言"))
        assertTrue(isLyricCreditMetadataLine("OP：唯迹文化"))
        assertTrue(isLyricCreditMetadataLine("出品：网易飓风"))
        assertTrue(isLyricCreditMetadataLine("op : Some Label"))
        assertTrue(isLyricCreditMetadataLine("Producer: John"))
    }

    @Test
    fun `isLyricCreditMetadataLine keeps normal lyrics with colon`() {
        assertFalse(isLyricCreditMetadataLine("爱上了一个人眼睛不说谎"))
        assertFalse(isLyricCreditMetadataLine("Starlight"))
        assertFalse(isLyricCreditMetadataLine("我说：走吧"))
        assertFalse(isLyricCreditMetadataLine("3:15 剩下的时间"))
        assertFalse(isLyricCreditMetadataLine(""))
    }
}
