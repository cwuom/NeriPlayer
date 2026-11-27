package moe.ouom.neriplayer.ui.screen.tab.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSongPagerColumnsTest {

    @Test
    fun `last page with a single column of songs leaves the other columns empty`() {
        // 两列每列 4 首, 28 首的最后一页只剩 25..28
        assertTrue(homeSongPagerColumnHasSongs(pageStart = 24, pageEnd = 28, columnIndex = 0, rowsPerColumn = 4))
        assertFalse(homeSongPagerColumnHasSongs(pageStart = 24, pageEnd = 28, columnIndex = 1, rowsPerColumn = 4))
    }

    @Test
    fun `a partly filled column still counts as having songs`() {
        assertTrue(homeSongPagerColumnHasSongs(pageStart = 0, pageEnd = 5, columnIndex = 1, rowsPerColumn = 4))
        assertFalse(homeSongPagerColumnHasSongs(pageStart = 0, pageEnd = 5, columnIndex = 2, rowsPerColumn = 4))
    }

    @Test
    fun `full pages keep every column`() {
        (0 until 3).forEach { column ->
            assertTrue(homeSongPagerColumnHasSongs(pageStart = 0, pageEnd = 12, columnIndex = column, rowsPerColumn = 4))
        }
    }
}
