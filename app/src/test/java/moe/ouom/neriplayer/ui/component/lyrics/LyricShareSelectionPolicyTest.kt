package moe.ouom.neriplayer.ui.component.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricShareSelectionPolicyTest {
    @Test
    fun `selection accepts a line beyond the former character limit`() {
        val longLineKey = "line-${"x".repeat(151)}"

        val selected = toggleShareLine(
            currentKeys = setOf("initial-line"),
            toggledKey = longLineKey
        )

        assertEquals(setOf("initial-line", longLineKey), selected)
    }

    @Test
    fun `lyric card line capacity follows its available height`() {
        assertEquals(
            6,
            lyricShareCardMaxLinesForHeight(
                maxHeight = 572,
                lineHeight = 80f,
                lineSpacingExtra = 16f
            )
        )
        assertEquals(
            1,
            lyricShareCardMaxLinesForHeight(
                maxHeight = 24,
                lineHeight = 48f,
                lineSpacingExtra = 12f
            )
        )
    }
}
