package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricCurrentLineIndexTest {
    private val lines = listOf(0L, 1_000L, 2_000L, 2_000L, 5_000L).map { startTimeMs ->
        LyricEntry(text = "line $startTimeMs", startTimeMs = startTimeMs, endTimeMs = startTimeMs + 500L)
    }

    @Test
    fun `no lines means no current line`() {
        assertEquals(-1, findCurrentLineIndex(emptyList(), 1_000L))
    }

    @Test
    fun `current line is the last line that has started`() {
        assertEquals(0, findCurrentLineIndex(lines, 999L))
        assertEquals(1, findCurrentLineIndex(lines, 1_000L))
        assertEquals(3, findCurrentLineIndex(lines, 2_500L))
        assertEquals(4, findCurrentLineIndex(lines, 60_000L))
    }

    @Test
    fun `times before the first line still point at the first line`() {
        assertEquals(0, findCurrentLineIndex(lines.drop(1), 0L))
    }
}
