package moe.ouom.neriplayer.activity

import moe.ouom.neriplayer.core.startup.StartupStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStageTransitionPolicyTest {
    @Test
    fun onlyOnboardingReceivesADeferredContentFrame() {
        assertTrue(shouldDeferStartupStageContent(StartupStage.Onboarding))
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
