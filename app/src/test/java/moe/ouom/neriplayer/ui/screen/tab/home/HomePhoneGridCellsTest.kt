package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePhoneGridCellsTest {
    @Test
    fun `narrow phones retain two cells after both sets of horizontal padding`() {
        for (density in listOf(Density(1f), Density(2.75f), Density(2.75f, 1.5f))) {
            with(density) {
                for (width in listOf(266.dp, 300.dp, 320.dp, 360.dp, 362.dp)) {
                    val available = width.roundToPx() - 2 * 16.dp.roundToPx() - 2 * 8.dp.roundToPx()
                    val spacing = 14.dp.roundToPx()
                    val cells = with(HomePhoneGridCells(150.dp)) {
                        calculateCrossAxisCellSizes(available, spacing)
                    }

                    assertEquals("width=$width density=$density cells=$cells", 2, cells.size)
                    assertEquals(available, cells.sum() + spacing)
                    assertTrue(cells.all { it > 0 })
                    assertTrue(kotlin.math.abs(cells[0] - cells[1]) <= 1)
                }
            }
        }
    }

    @Test
    fun `wide phone grids keep the original adaptive sizing and additional columns`() {
        with(Density(2.75f)) {
            for (width in listOf(480.dp, 600.dp, 700.dp)) {
                val available = width.roundToPx() - 2 * 16.dp.roundToPx() - 2 * 8.dp.roundToPx()
                val spacing = 14.dp.roundToPx()
                val expected = with(GridCells.Adaptive(150.dp)) {
                    calculateCrossAxisCellSizes(available, spacing)
                }
                val actual = with(HomePhoneGridCells(150.dp)) {
                    calculateCrossAxisCellSizes(available, spacing)
                }

                assertTrue(expected.size >= 2)
                assertEquals(expected, actual)
            }
        }
    }

    @Test
    fun `two columns require enough space for two positive cell widths`() {
        with(Density(1f)) {
            val spacing = 14
            val cells = HomePhoneGridCells(150.dp)
            with(cells) {
                assertEquals(listOf(1, 1), calculateCrossAxisCellSizes(spacing + 2, spacing))
                for (available in listOf(0, 1, spacing, spacing + 1)) {
                    val sizes = calculateCrossAxisCellSizes(available, spacing)
                    assertEquals(1, sizes.size)
                    assertTrue(sizes.single() >= 0)
                }
            }
        }
    }
}
