package moe.ouom.neriplayer.core.startup.theme

import android.content.Context
import moe.ouom.neriplayer.data.settings.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.settings.readThemePreferenceSnapshotSync

internal object StartupThemeSnapshotProvider {
    fun read(
        context: Context,
        safeModeActive: Boolean
    ): ThemePreferenceSnapshot {
        return if (safeModeActive) {
            ThemePreferenceSnapshot()
        } else {
            readThemePreferenceSnapshotSync(context)
        }
    }
}
