package moe.ouom.neriplayer.core.player.service.lyrics

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveLyricNotificationTextTest {
    private val line = LyricEntry(text = "  Hello \n  world  ", startTimeMs = 0L, endTimeMs = 2_000L)

    @Test
    fun `notification text normalizes whitespace and compacts the chip`() {
        assertEquals(
            LiveLyricNotificationText(lyric = "Hello world", fullLyric = "Hello world", compactLyric = "Hello …"),
            buildLiveLyricNotificationText(line)
        )
        assertNull(buildLiveLyricNotificationText(line, selectedText = null))
        assertNull(buildLiveLyricNotificationText(line, selectedText = " \t "))
    }

    @Test
    fun `secondary text is dropped when blank`() {
        assertNull(buildLiveLyricSecondaryText(null))
        assertNull(buildLiveLyricSecondaryText(" \n "))
        assertEquals("你好 世界", buildLiveLyricSecondaryText(" 你好\t世界 "))
    }

    @Test
    fun `compact text keeps short lines and optional long tokens`() {
        assertEquals("", compactLiveLyricText("   "))
        assertEquals("短歌词", compactLiveLyricText(" 短歌词 "))
        assertEquals("Supercalifragilistic", compactLiveLyricText("Supercalifragilistic", preserveLongToken = true))
        assertEquals("Superc…", compactLiveLyricText("Supercalifragilistic"))
        assertEquals("a b c …", compactLiveLyricText("a b c d e", preserveLongToken = true))
    }

    @Test
    fun `compact text never splits surrogate pairs`() {
        assertEquals("😀".repeat(6) + "…", compactLiveLyricText("😀".repeat(8)))
        assertEquals("😀".repeat(7), compactLiveLyricText("😀".repeat(7)))
    }
}
