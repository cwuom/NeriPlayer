package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLyricAlignmentTest {
    @Test
    fun `a translation spanning two original cues stays on both lines`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(
                LyricEntry("and there will almost certainly", 54_000L, 55_600L),
                LyricEntry("be many more to come.", 55_600L, 56_800L),
                LyricEntry("Okay, tangent over.", 56_800L, 58_000L)
            ),
            translations = listOf(
                LyricEntry("而且将来肯定会有更多的模型出现。", 54_000L, 56_700L),
                LyricEntry("好了，题外话结束。", 56_700L, 58_000L)
            )
        )

        val matched = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("而且将来肯定会有更多的模型出现。", matched[0]?.text)
        assertEquals("而且将来肯定会有更多的模型出现。", matched[1]?.text)
        assertEquals("好了，题外话结束。", matched[2]?.text)
        assertEquals(listOf(54_000L, 55_600L, 56_800L), translations.map { it.startTimeMs })
    }

    @Test
    fun `multiple translated cues inside one original cue keep every sentence`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(LyricEntry("而且将来肯定会有更多的模型出现。", 1_000L, 4_000L)),
            translations = listOf(
                LyricEntry("and there will almost certainly", 1_000L, 2_500L),
                LyricEntry("be many more to come.", 2_500L, 4_000L)
            )
        )

        val matched = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("and there will almost certainly\nbe many more to come.", matched[0]?.text)
        assertEquals(1, translations.size)
    }

    @Test
    fun `a nearby caption outside the original interval is not a translation`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(LyricEntry("当前句", 1_000L, 2_000L)),
            translations = listOf(LyricEntry("another sentence", 2_100L, 2_200L))
        )

        assertTrue(translations.isEmpty())
        assertTrue(matchTranslationsToLineIndices(lyrics, translations).isEmpty())
    }

    @Test
    fun `an overlapping original cue cannot take the next cues translation`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(
                LyricEntry("没有翻译的前句", 0L, 2_000L),
                LyricEntry("后句", 1_500L, 2_500L)
            ),
            translations = listOf(LyricEntry("next sentence", 1_500L, 2_500L))
        )

        val matched = matchTranslationsToLineIndices(lyrics, translations)

        assertNull(matched[0])
        assertEquals("next sentence", matched[1]?.text)
        assertEquals(listOf(1_500L), translations.map { it.startTimeMs })
    }

    @Test
    fun `repeated translated sentences at different times remain repeated`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(LyricEntry("不。不。", 0L, 4_000L)),
            translations = listOf(
                LyricEntry("No.", 0L, 2_000L),
                LyricEntry("No.", 2_000L, 4_000L)
            )
        )

        val matched = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("No.\nNo.", matched[0]?.text)
    }

    @Test
    fun `a brief boundary overlap does not attach the next translated sentence`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(LyricEntry("当前句", 0L, 1_000L)),
            translations = listOf(LyricEntry("next sentence", 900L, 1_500L))
        )

        assertTrue(translations.isEmpty())
        assertTrue(matchTranslationsToLineIndices(lyrics, translations).isEmpty())
    }

    @Test
    fun `an original cue cannot consume a translation beyond the following cue`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(
                LyricEntry("第一句", 0L, 10_000L),
                LyricEntry("第二句", 1_000L, 2_000L),
                LyricEntry("第三句", 2_000L, 3_000L)
            ),
            translations = listOf(LyricEntry("third sentence", 2_000L, 3_000L))
        )

        val matched = matchTranslationsToLineIndices(lyrics, translations)

        assertNull(matched[0])
        assertNull(matched[1])
        assertEquals("third sentence", matched[2]?.text)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), lyrics.map { it.endTimeMs })
    }

    @Test
    fun `a translated cue stops when the next translated cue replaces it`() {
        val (lyrics, translations) = alignSubtitleLyrics(
            lyrics = listOf(
                LyricEntry("第一句", 0L, 1_500L),
                LyricEntry("第二句", 1_500L, 2_500L)
            ),
            translations = listOf(
                LyricEntry("first sentence", 0L, 2_500L),
                LyricEntry("second sentence", 1_500L, 2_500L)
            )
        )

        val matched = matchTranslationsToLineIndices(lyrics, translations)

        assertEquals("first sentence", matched[0]?.text)
        assertEquals("second sentence", matched[1]?.text)
    }

    @Test
    fun `metadata and placeholders cannot hide or contaminate a combined translation`() {
        for (marker in listOf("作曲: 甲", "//")) {
            for (markerFirst in listOf(true, false)) {
                val first = if (markerFirst) marker else "正文翻译"
                val second = if (markerFirst) "正文翻译" else marker
                val (lyrics, translations) = alignSubtitleLyrics(
                    lyrics = listOf(LyricEntry("旁白", 0L, 4_000L)),
                    translations = listOf(
                        LyricEntry(first, 0L, 2_000L),
                        LyricEntry(second, 2_000L, 4_000L)
                    )
                )

                val matched = matchTranslationsToLineIndices(lyrics, translations)

                assertEquals("marker=$marker first=$markerFirst", "正文翻译", matched[0]?.text)
                assertEquals(listOf("正文翻译"), translations.map { it.text })
            }
        }
    }
}
