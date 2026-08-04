package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppThemeRevealPolicyTest {

    @Test
    fun `large theme reveal snapshot is downsampled under max dimension`() {
        val dimensions = resolveThemeRevealSnapshotDimensions(
            width = 3200,
            height = 1440
        )

        assertEquals(1080, dimensions.width)
        assertEquals(486, dimensions.height)
        assertTrue(maxOf(dimensions.width, dimensions.height) <= 1080)
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
