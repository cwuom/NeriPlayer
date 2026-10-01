package moe.ouom.neriplayer.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainLyricsTest {
    @Test
    fun `blank lines do not consume timing slots`() {
        val result = convertPlainLyricsToEntries("  first \n\n second \n \nthird", 10_000L)
        assertEquals(listOf("first", "second", "third"), result.map { it.text })
        assertEquals(listOf(0L, 3_333L, 6_666L), result.map { it.startTimeMs })
        assertEquals(listOf(3_333L, 6_666L, 10_000L), result.map { it.endTimeMs })
    }

    @Test
    fun `empty input has no synthesized entries`() {
        assertTrue(convertPlainLyricsToEntries(" \n\t\n", 10_000L).isEmpty())
    }

    @Test
    fun `missing duration retains text and bounds final timestamp`() {
        for (duration in listOf(0L, -1L)) {
            val result = convertPlainLyricsToEntries("first\nsecond", duration)
            assertEquals(listOf(0L, 0L), result.map { it.startTimeMs })
            assertEquals(listOf(0L, 1L), result.map { it.endTimeMs })
        }
    }
}
