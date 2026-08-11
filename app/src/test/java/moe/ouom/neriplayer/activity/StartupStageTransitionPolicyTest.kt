package moe.ouom.neriplayer.activity

import moe.ouom.neriplayer.core.startup.StartupStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStageTransitionPolicyTest {
    @Test
    fun onboardingDefersOnlyDuringStartupBootstrap() {
        assertTrue(shouldDeferStartupStageContent(StartupStage.Onboarding))
        assertTrue(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Loading
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Loading,
                disclaimerWasShown = true
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Disclaimer
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Main
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Main,
                previousStage = StartupStage.Onboarding
            )
        )
        assertFalse(shouldDeferStartupStageContent(StartupStage.Loading))
        assertFalse(shouldDeferStartupStageContent(StartupStage.Disclaimer))
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Main,
                previousStage = StartupStage.Loading
            )
        )
    }
}
