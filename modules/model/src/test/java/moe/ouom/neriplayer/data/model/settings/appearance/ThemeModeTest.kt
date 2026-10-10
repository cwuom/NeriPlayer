package moe.ouom.neriplayer.data.model.settings.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `only auto mode follows the system dark setting`() {
        assertFalse(ThemeMode.LIGHT.resolveUseDark(systemDark = true))
        assertTrue(ThemeMode.DARK.resolveUseDark(systemDark = false))
        assertTrue(ThemeMode.AUTO.resolveUseDark(systemDark = true))
        assertFalse(ThemeMode.AUTO.resolveUseDark(systemDark = false))
    }

    @Test
    fun `stored flags map back to their mode with force dark winning`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromPreferenceFlags(forceDark = true, followSystemDark = true))
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromPreferenceFlags(mode.forceDark, mode.followSystemDark))
        }
    }

    @Test
    fun `preference snapshots resolve dark mode with force dark first`() {
        assertTrue(ThemePreferenceSnapshot(forceDark = true, followSystemDark = false).resolveUseDark(systemDark = false))
        assertTrue(ThemePreferenceSnapshot(followSystemDark = true).resolveUseDark(systemDark = true))
        assertFalse(ThemePreferenceSnapshot(followSystemDark = true).resolveUseDark(systemDark = false))
        assertFalse(ThemePreferenceSnapshot(followSystemDark = false).resolveUseDark(systemDark = true))
    }
}
