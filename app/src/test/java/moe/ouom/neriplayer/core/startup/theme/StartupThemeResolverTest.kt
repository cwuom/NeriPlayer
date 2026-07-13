package moe.ouom.neriplayer.core.startup.theme

import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupThemeResolverTest {
    @Test
    fun `snapshot force dark wins over system light`() {
        val useDark = StartupThemeResolver.resolveSnapshotUseDark(
            snapshot = ThemePreferenceSnapshot(
                forceDark = true,
                followSystemDark = false
            ),
            systemDark = false
        )

        assertTrue(useDark)
    }

    @Test
    fun `snapshot auto follows system dark`() {
        val useDark = StartupThemeResolver.resolveSnapshotUseDark(
            snapshot = ThemePreferenceSnapshot(
                forceDark = false,
                followSystemDark = true
            ),
            systemDark = true
        )

        assertTrue(useDark)
    }

    @Test
    fun `snapshot light ignores system dark`() {
        val useDark = StartupThemeResolver.resolveSnapshotUseDark(
            snapshot = ThemePreferenceSnapshot(
                forceDark = false,
                followSystemDark = false
            ),
            systemDark = true
        )

        assertFalse(useDark)
    }

    @Test
    fun `theme mode resolves explicit light`() {
        assertFalse(
            StartupThemeResolver.resolveModeUseDark(
                mode = ThemeMode.LIGHT,
                systemDark = true
            )
        )
    }

    @Test
    fun `theme mode resolves explicit dark`() {
        assertTrue(
            StartupThemeResolver.resolveModeUseDark(
                mode = ThemeMode.DARK,
                systemDark = false
            )
        )
    }

    @Test
    fun `theme mode auto follows system`() {
        assertTrue(
            StartupThemeResolver.resolveModeUseDark(
                mode = ThemeMode.AUTO,
                systemDark = true
            )
        )
    }
}
