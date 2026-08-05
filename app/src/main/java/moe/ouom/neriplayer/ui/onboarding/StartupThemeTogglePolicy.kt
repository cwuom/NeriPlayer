package moe.ouom.neriplayer.ui.onboarding

import moe.ouom.neriplayer.data.settings.ThemeMode

internal fun shouldBlockStartupOnboardingThemeToggle(
    captureInFlight: Boolean,
    revealActive: Boolean
): Boolean = captureInFlight || revealActive

internal fun resolveStartupOnboardingThemeMode(
    storedFollowSystemDark: Boolean,
    storedForceDark: Boolean,
    pendingFollowSystemDark: Boolean?,
    pendingForceDark: Boolean?
): ThemeMode {
    return ThemeMode.fromPreferenceFlags(
        forceDark = pendingForceDark ?: storedForceDark,
        followSystemDark = pendingFollowSystemDark ?: storedFollowSystemDark
    )
}
