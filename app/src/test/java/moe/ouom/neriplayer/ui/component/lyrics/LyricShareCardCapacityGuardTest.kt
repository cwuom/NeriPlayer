package moe.ouom.neriplayer.ui.component.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LyricShareCardCapacityGuardTest {

    @Test
    fun `line capacity without spacing divides the available height`() {
        assertEquals(
            4,
            lyricShareCardMaxLinesForHeight(maxHeight = 100, lineHeight = 25f, lineSpacingExtra = 0f)
        )
    }

    @Test
    fun `line capacity rejects invalid card measurements`() {
        val heightError = assertThrows(IllegalArgumentException::class.java) {
            lyricShareCardMaxLinesForHeight(maxHeight = 0, lineHeight = 48f, lineSpacingExtra = 0f)
        }
        val lineHeightError = assertThrows(IllegalArgumentException::class.java) {
            lyricShareCardMaxLinesForHeight(maxHeight = 480, lineHeight = 0f, lineSpacingExtra = 0f)
        }
        val spacingError = assertThrows(IllegalArgumentException::class.java) {
            lyricShareCardMaxLinesForHeight(maxHeight = 480, lineHeight = 48f, lineSpacingExtra = -1f)
        }

        assertEquals("Maximum lyric card height must be positive", heightError.message)
        assertEquals("Lyric card line height must be positive", lineHeightError.message)
        assertEquals("Lyric card line spacing must not be negative", spacingError.message)
    }
}
