package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.data.model.settings.appearance.ThemeMode
import moe.ouom.neriplayer.ui.theme.reveal.resolveThemeRevealSnapshotByteBudget
import moe.ouom.neriplayer.ui.theme.reveal.resolveThemeRevealSnapshotDimensions
import moe.ouom.neriplayer.ui.theme.reveal.resolveThemeToggleTarget
import moe.ouom.neriplayer.ui.theme.reveal.shouldBlockThemeModeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppThemeRevealPolicyTest {

    @Test
    fun `phone panels keep full resolution for the theme reveal`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 1440,
            height = 3200
        )

        assertEquals(1440, dimensions.width)
        assertEquals(3200, dimensions.height)
    }

    @Test
    fun `oversized windows respect both edge and allocation limits`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 8192,
            height = 4096
        )

        assertEquals(3072, dimensions.width)
        assertEquals(1536, dimensions.height)
        assertTrue(maxOf(dimensions.width, dimensions.height) <= 4096)
    }

    @Test
    fun `ordinary tablet panels keep full resolution within the byte budget`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(2560, 1600)

        assertEquals(2560, dimensions.width)
        assertEquals(1600, dimensions.height)
    }

    @Test
    fun `four k windows stay within eighteen mebibytes without distorting the snapshot`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(3840, 2160)

        assertTrue(dimensions.width.toLong() * dimensions.height * 4 <= 18L * 1024 * 1024)
        assertTrue(kotlin.math.abs(dimensions.width.toDouble() / dimensions.height - 16.0 / 9) < 0.001)
    }

    @Test
    fun `square windows obey the allocation limit even when no edge exceeds the cap`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(4096, 4096)

        assertEquals(dimensions.width, dimensions.height)
        assertTrue(dimensions.width.toLong() * dimensions.height * 4 <= 18L * 1024 * 1024)
    }

    @Test
    fun `snapshot budget follows the heap and keeps a fixed upper bound`() {
        assertEquals(8L * 1024 * 1024, resolveThemeRevealSnapshotByteBudget(128L * 1024 * 1024))
        assertEquals(16L * 1024 * 1024, resolveThemeRevealSnapshotByteBudget(256L * 1024 * 1024))
        assertEquals(18L * 1024 * 1024, resolveThemeRevealSnapshotByteBudget(512L * 1024 * 1024))
        assertEquals(18L * 1024 * 1024, resolveThemeRevealSnapshotByteBudget(Long.MAX_VALUE))
        assertEquals(4L, resolveThemeRevealSnapshotByteBudget(0))
        assertEquals(4L, resolveThemeRevealSnapshotByteBudget(Long.MIN_VALUE))
    }

    @Test
    fun `lower heap budgets reduce large snapshots while preserving their aspect ratio`() {
        val budget = resolveThemeRevealSnapshotByteBudget(128L * 1024 * 1024)
        val dimensions = resolveThemeRevealSnapshotDimensions(3840, 2160, maxBitmapBytes = budget)

        assertTrue(dimensions.width.toLong() * dimensions.height * 4 <= budget)
        assertTrue(kotlin.math.abs(dimensions.width.toDouble() / dimensions.height - 16.0 / 9) < 0.001)
    }

    @Test
    fun `minimum budgets always produce a single valid pixel`() {
        for (budget in listOf(Long.MIN_VALUE, -1L, 0L, 3L, 4L, 5L)) {
            val dimensions = resolveThemeRevealSnapshotDimensions(3840, 2160, maxBitmapBytes = budget)

            assertEquals(1, dimensions.width)
            assertEquals(1, dimensions.height)
        }
    }

    @Test
    fun `extreme square dimensions do not overflow the allocation budget`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(Int.MAX_VALUE, Int.MAX_VALUE)

        assertEquals(dimensions.width, dimensions.height)
        assertTrue(dimensions.width.toLong() * dimensions.height * 4 <= 18L * 1024 * 1024)
    }

    @Test
    fun `extreme narrow windows cannot exceed tiny pixel budgets`() {
        for (budget in listOf(4L, 8L, 16L, 8L * 1024 * 1024)) {
            val wide = resolveThemeRevealSnapshotDimensions(Int.MAX_VALUE, 1, maxBitmapBytes = budget)
            val tall = resolveThemeRevealSnapshotDimensions(1, Int.MAX_VALUE, maxBitmapBytes = budget)

            assertTrue(wide.width.toLong() * wide.height * 4 <= budget)
            assertTrue(tall.width.toLong() * tall.height * 4 <= budget)
            assertEquals(wide.width, tall.height)
            assertEquals(wide.height, tall.width)
        }
    }

    @Test
    fun `rotating a large window transposes the same bounded snapshot`() {
        val landscape = resolveThemeRevealSnapshotDimensions(3840, 2160)
        val portrait = resolveThemeRevealSnapshotDimensions(2160, 3840)

        assertEquals(landscape.width, portrait.height)
        assertEquals(landscape.height, portrait.width)
    }

    @Test
    fun `invalid dimensions and edge limits are normalized to a valid pixel`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(-1, 0, maxDimensionPx = 0)

        assertEquals(1, dimensions.width)
        assertEquals(1, dimensions.height)
    }

    @Test
    fun `small theme reveal snapshot keeps original size`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 900,
            height = 600
        )

        assertEquals(900, dimensions.width)
        assertEquals(600, dimensions.height)
    }

    @Test
    fun `active reveal blocks another theme request`() {
        assertTrue(
            shouldBlockThemeModeChange(
                captureInFlight = false,
                writeInFlight = false,
                revealActive = true,
                hasPendingThemePreference = true
            )
        )
    }

    @Test
    fun `capture and persistence each block another theme request`() {
        assertTrue(
            shouldBlockThemeModeChange(
                captureInFlight = true,
                writeInFlight = false,
                revealActive = false,
                hasPendingThemePreference = false
            )
        )
        assertTrue(
            shouldBlockThemeModeChange(
                captureInFlight = false,
                writeInFlight = true,
                revealActive = false,
                hasPendingThemePreference = false
            )
        )
    }

    @Test
    fun `idle state accepts the next theme request`() {
        assertTrue(
            !shouldBlockThemeModeChange(
                captureInFlight = false,
                writeInFlight = false,
                revealActive = false,
                hasPendingThemePreference = false
            )
        )
    }

    @Test
    fun `second toggle uses the updated theme state`() {
        assertEquals(ThemeMode.DARK, resolveThemeToggleTarget(isDark = false))
        assertEquals(ThemeMode.LIGHT, resolveThemeToggleTarget(isDark = true))
    }
}
