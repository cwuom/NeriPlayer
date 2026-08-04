package moe.ouom.neriplayer.core.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimelinePolicyTest {

    @Test
    fun collapsedJapaneseLyricsAreConvertedToPlainText() {
        val rawLyrics = """
            [00:00.00]凍えてる 心の中を
            [00:00.00]覗くたびに突き放して
            [00:00.00]遠い空の向こう側で
            [00:00.00]誰でも 同じ
        """.trimIndent()

        assertTrue(hasCollapsedTimedLyricTimeline(rawLyrics))
        assertFalse(isUsableTimedLyricTimeline(rawLyrics))
        assertEquals(
            """
                凍えてる 心の中を
                覗くたびに突き放して
                遠い空の向こう側で
                誰でも 同じ
            """.trimIndent(),
            extractPlainLyricsFromCollapsedTimedLyrics(rawLyrics)
        )
    }

    @Test
    fun timedLyricsWithProgressRemainUsable() {
        val rawLyrics = """
            [00:00.00]First line
            [00:12.50]Second line
            [00:25.00]Third line
        """.trimIndent()

        assertFalse(hasCollapsedTimedLyricTimeline(rawLyrics))
        assertTrue(isUsableTimedLyricTimeline(rawLyrics))
    }

    @Test
    fun shortZeroTimestampLyricRemainsCompatible() {
        assertTrue(isUsableTimedLyricTimeline("[00:00.00]Instrumental"))
    }
}
