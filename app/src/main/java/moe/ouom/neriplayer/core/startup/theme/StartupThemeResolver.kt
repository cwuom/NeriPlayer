package moe.ouom.neriplayer.core.startup.theme

import moe.ouom.neriplayer.data.settings.appearance.ThemeMode
import moe.ouom.neriplayer.data.settings.appearance.ThemePreferenceSnapshot

internal object StartupThemeResolver {
    fun resolveSnapshotUseDark(
        snapshot: ThemePreferenceSnapshot,
        systemDark: Boolean
    ): Boolean {
        return snapshot.resolveUseDark(systemDark)
    }

    fun resolveModeUseDark(
        mode: ThemeMode,
        systemDark: Boolean
    ): Boolean {
        return mode.resolveUseDark(systemDark)
    }
}
