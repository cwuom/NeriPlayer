package moe.ouom.neriplayer.ui.component.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LyricShareCardLineBudgetTest {

    @Test
    fun `line budget counts the spacing only between lines`() {
        // 3 lines of 40px with 10px between them need exactly 140px
        assertEquals(3, lyricShareCardMaxLinesForHeight(140, 40f, 10f))
        assertEquals(2, lyricShareCardMaxLinesForHeight(139, 40f, 10f))
    }

    @Test
    fun `line budget without spacing divides the height evenly`() {
        assertEquals(5, lyricShareCardMaxLinesForHeight(200, 40f, 0f))
    }

    @Test
    fun `line budget always allows at least one line`() {
        assertEquals(1, lyricShareCardMaxLinesForHeight(10, 40f, 16f))
    }

    @Test
    fun `line budget rejects non positive heights`() {
        assertThrows(IllegalArgumentException::class.java) {
            lyricShareCardMaxLinesForHeight(0, 40f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            lyricShareCardMaxLinesForHeight(100, 0f, 0f)
        }
    }

    @Test
    fun `line budget rejects negative spacing`() {
        assertThrows(IllegalArgumentException::class.java) {
            lyricShareCardMaxLinesForHeight(100, 40f, -1f)
        }
    }
}
