package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.data.model.settings.appearance.ThemeMode
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
    fun `oversized windows are still capped proportionally`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 8192,
            height = 4096
        )

        assertEquals(4096, dimensions.width)
        assertEquals(2048, dimensions.height)
        assertTrue(maxOf(dimensions.width, dimensions.height) <= 4096)
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
