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
    fun `long press selects every line from the latest selection start through its target`() {
        val lineKeys = listOf("line-0", "line-1", "line-2", "line-3", "line-4")

        val selected = selectShareLineRange(
            currentKeys = setOf("line-1"),
            lineKeys = lineKeys,
            selectionStartKey = "line-1",
            targetKey = "line-4"
        )

        assertEquals(
            setOf("line-1", "line-2", "line-3", "line-4"),
            selected
        )
    }

    @Test
    fun `long press selects a reverse range without discarding existing selections`() {
        val lineKeys = listOf("line-0", "line-1", "line-2", "line-3", "line-4")

        val selected = selectShareLineRange(
            currentKeys = setOf("line-0", "line-3"),
            lineKeys = lineKeys,
            selectionStartKey = "line-3",
            targetKey = "line-1"
        )

        assertEquals(
            setOf("line-0", "line-1", "line-2", "line-3"),
            selected
        )
    }

    @Test
    fun `long press falls back to selecting its target when the selection start is unavailable`() {
        val selected = selectShareLineRange(
            currentKeys = setOf("line-0"),
            lineKeys = listOf("line-0", "line-1"),
            selectionStartKey = "removed-line",
            targetKey = "line-1"
        )

        assertEquals(setOf("line-0", "line-1"), selected)
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
