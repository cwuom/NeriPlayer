package moe.ouom.neriplayer.core.startup.theme

import moe.ouom.neriplayer.data.settings.ThemeMode

internal data class StartupNightModeSyncPlan(
    val useDark: Boolean,
    val shouldApplyNightMode: Boolean
)

internal object StartupNightModeSyncPlanner {
    fun plan(
        forceDark: Boolean,
        followSystemDark: Boolean,
        systemDark: Boolean,
        currentResourceDark: Boolean
    ): StartupNightModeSyncPlan {
        val themeMode = ThemeMode.fromPreferenceFlags(
            forceDark = forceDark,
            followSystemDark = followSystemDark
        )
        val useDark = StartupThemeResolver.resolveModeUseDark(
            mode = themeMode,
            systemDark = systemDark
        )
        return StartupNightModeSyncPlan(
            useDark = useDark,
            shouldApplyNightMode = currentResourceDark != useDark
        )
    }
}
