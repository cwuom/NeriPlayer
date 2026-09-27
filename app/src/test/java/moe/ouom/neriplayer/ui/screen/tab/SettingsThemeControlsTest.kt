package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.ui.geometry.Offset
import moe.ouom.neriplayer.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsThemeControlsTest {
    @Test
    fun `manual mode uses the selected tab center and ignores repeat selection`() {
        assertNull(resolveManualThemeReveal(0, ThemeMode.LIGHT, Offset.Zero, 200f, 40f))
        assertNull(resolveManualThemeReveal(1, ThemeMode.DARK, Offset.Zero, 200f, 40f))

        assertEquals(
            ThemeRevealRequest(ThemeMode.DARK, Offset(160f, 40f), 1f),
            resolveManualThemeReveal(1, ThemeMode.AUTO, Offset(10f, 20f), 200f, 40f)
        )
        assertEquals(
            ThemeRevealRequest(ThemeMode.LIGHT, Offset(60f, 40f), 1f),
            resolveManualThemeReveal(0, ThemeMode.DARK, Offset(10f, 20f), 200f, 40f)
        )
    }

    @Test
    fun `manual mode falls back to zero before the tabs are measured`() {
        assertEquals(
            ThemeRevealRequest(ThemeMode.LIGHT, Offset.Zero, 1f),
            resolveManualThemeReveal(0, ThemeMode.AUTO, Offset(10f, 20f), 0f, 40f)
        )
        assertEquals(
            ThemeRevealRequest(ThemeMode.DARK, Offset.Zero, 1f),
            resolveManualThemeReveal(1, ThemeMode.LIGHT, Offset(10f, 20f), 200f, 0f)
        )
    }

    @Test
    fun `auto toggle enters auto or returns to the visible light or dark mode`() {
        assertEquals(
            ThemeRevealRequest(ThemeMode.AUTO, Offset(4f, 8f), 22f),
            resolveAutoThemeToggle(ThemeMode.DARK, true, Offset(4f, 8f), 22f)
        )
        assertEquals(
            ThemeRevealRequest(ThemeMode.AUTO, Offset.Zero, 18f),
            resolveAutoThemeToggle(ThemeMode.LIGHT, false, null, 18f)
        )
        assertEquals(
            ThemeRevealRequest(ThemeMode.DARK, Offset.Zero, 18f),
            resolveAutoThemeToggle(ThemeMode.AUTO, true, null, 18f)
        )
        assertEquals(
            ThemeRevealRequest(ThemeMode.LIGHT, Offset.Zero, 18f),
            resolveAutoThemeToggle(ThemeMode.AUTO, false, null, 18f)
        )
    }

    @Test
    fun `auto mode owner refreshes its callback and current mode after recomposition`() {
        val owner = AutoThemeModeController()
        var firstRequest: ThemeRevealRequest? = null
        var secondRequest: ThemeRevealRequest? = null

        owner.update(ThemeMode.AUTO, true) { mode, origin, radius ->
            firstRequest = ThemeRevealRequest(mode, origin, radius)
        }
        assertTrue(owner.autoEnabled)
        owner.toggle()
        assertEquals(ThemeRevealRequest(ThemeMode.DARK, Offset.Zero, 18f), firstRequest)

        owner.update(ThemeMode.LIGHT, false) { mode, origin, radius ->
            secondRequest = ThemeRevealRequest(mode, origin, radius)
        }
        assertFalse(owner.autoEnabled)
        owner.toggle()
        assertEquals(ThemeRevealRequest(ThemeMode.AUTO, Offset.Zero, 18f), secondRequest)
        assertEquals(ThemeRevealRequest(ThemeMode.DARK, Offset.Zero, 18f), firstRequest)
    }
}
