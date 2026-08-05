package moe.ouom.neriplayer.ui.onboarding

import moe.ouom.neriplayer.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupThemeTogglePolicyTest {

    @Test
    fun onlyCaptureAndRevealStatesBlockAnotherToggle() {
        assertEquals(
            true,
            shouldBlockStartupOnboardingThemeToggle(
                captureInFlight = true,
                revealActive = false
            )
        )
        assertEquals(
            true,
            shouldBlockStartupOnboardingThemeToggle(
                captureInFlight = false,
                revealActive = true
            )
        )
        assertEquals(
            false,
            shouldBlockStartupOnboardingThemeToggle(
                captureInFlight = false,
                revealActive = false
            )
        )
    }

    @Test
    fun pendingDarkPreferenceWinsBeforeStoredValuesCatchUp() {
        assertEquals(
            ThemeMode.DARK,
            resolveStartupOnboardingThemeMode(
                storedFollowSystemDark = true,
                storedForceDark = false,
                pendingFollowSystemDark = false,
                pendingForceDark = true
            )
        )
    }

    @Test
    fun pendingLightPreferenceCanReplaceStoredDarkMode() {
        assertEquals(
            ThemeMode.LIGHT,
            resolveStartupOnboardingThemeMode(
                storedFollowSystemDark = false,
                storedForceDark = true,
                pendingFollowSystemDark = false,
                pendingForceDark = false
            )
        )
    }

    @Test
    fun storedAutoModeRemainsTheDefaultWithoutPendingValues() {
        assertEquals(
            ThemeMode.AUTO,
            resolveStartupOnboardingThemeMode(
                storedFollowSystemDark = true,
                storedForceDark = false,
                pendingFollowSystemDark = null,
                pendingForceDark = null
            )
        )
    }
}
