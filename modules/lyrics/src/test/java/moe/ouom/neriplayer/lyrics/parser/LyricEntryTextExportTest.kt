package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LyricEntryTextExportTest {
    private val plain = LyricEntry(text = "hello", startTimeMs = 61_230L, endTimeMs = 62_000L)
    private val emptyWords = LyricEntry(text = "ab", startTimeMs = 1_000L, endTimeMs = 1_600L, words = emptyList())
    private val timed = LyricEntry(
        text = "abcd",
        startTimeMs = 1_000L,
        endTimeMs = 2_000L,
        words = listOf(WordTiming(1_000L, 1_500L, charCount = 2), WordTiming(1_500L, 2_000L, charCount = 2))
    )

    @Test
    fun `lists without word timing are returned unchanged`() {
        val lines = listOf(plain, emptyWords)

        assertSame(lines, lines.flattenWordTimedEntries())
        assertEquals(emptyList<LyricEntry>(), emptyList<LyricEntry>().flattenWordTimedEntries())
    }

    @Test
    fun `flattening drops word timing but keeps plain lines`() {
        val flattened = listOf(plain, timed, emptyWords).flattenWordTimedEntries()

        assertSame(plain, flattened[0])
        assertEquals(timed.copy(words = null), flattened[1])
        assertNull(flattened[1].words)
        assertSame(emptyWords, flattened[2])
    }

    @Test
    fun `editable text writes lrc for plain lines and yrc for word timed lines`() {
        assertEquals("", emptyList<LyricEntry>().toEditableLyricsText())
        assertEquals(
            "[01:01.23]hello\n[00:01.00]ab\n[1000,1000](1000,500,0)ab(1500,500,0)cd",
            listOf(plain, emptyWords, timed).toEditableLyricsText()
        )
    }

    @Test
    fun `editor initial text falls back from preferred to displayed lyrics`() {
        fun initialText(
            matchedLyric: String? = null,
            preferred: String = "",
            wordTimed: Boolean = false,
            fallback: String? = null
        ) = resolveLyricsEditorInitialText(
            matchedLyric = matchedLyric,
            preferredNeteaseLyric = preferred,
            displayedLyricsText = "displayed",
            displayedHasWordTimedEntries = wordTimed,
            fallbackLyricsText = fallback
        )

        assertEquals("[00:01.00]matched", initialText(matchedLyric = "[00:01.00]matched", preferred = "preferred"))
        assertEquals("displayed", initialText(preferred = "preferred", wordTimed = true))
        assertEquals("preferred", initialText(preferred = "preferred", fallback = "fallback"))
        assertEquals("fallback", initialText(preferred = " ", fallback = "fallback"))
        assertEquals("displayed", initialText())
    }

    @Test
    fun `stored lyrics win over the remote preferred lyric even when cleared`() {
        assertEquals("[01:02.50]mine", resolvePreferredLyricContent("[1:02:50]mine", preferredNeteaseLyric = "remote"))
        assertEquals("", resolvePreferredLyricContent("", preferredNeteaseLyric = "remote"))
        assertEquals("remote", resolvePreferredLyricContent(null, preferredNeteaseLyric = "remote"))
        assertNull(resolvePreferredLyricContent(null, preferredNeteaseLyric = "  "))
    }
}
