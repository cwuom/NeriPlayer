package moe.ouom.neriplayer.core.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStageResolverTest {
    @Test
    fun `uses loading stage while disclaimer state is unknown`() {
        assertEquals(
            StartupStage.Loading,
            StartupStageResolver.resolve(
                disclaimerAccepted = null,
                startupOnboardingCompleted = null
            )
        )
    }

    @Test
    fun `requires disclaimer before onboarding`() {
        assertEquals(
            StartupStage.Disclaimer,
            StartupStageResolver.resolve(
                disclaimerAccepted = false,
                startupOnboardingCompleted = true
            )
        )
    }

    @Test
    fun `uses loading stage while onboarding state is unknown`() {
        assertEquals(
            StartupStage.Loading,
            StartupStageResolver.resolve(
                disclaimerAccepted = true,
                startupOnboardingCompleted = null
            )
        )
    }

    @Test
    fun `routes accepted users to onboarding until it is complete`() {
        assertEquals(
            StartupStage.Onboarding,
            StartupStageResolver.resolve(
                disclaimerAccepted = true,
                startupOnboardingCompleted = false
            )
        )
    }

    @Test
    fun `routes fully prepared users to main stage`() {
        assertEquals(
            StartupStage.Main,
            StartupStageResolver.resolve(
                disclaimerAccepted = true,
                startupOnboardingCompleted = true
            )
        )
    }
}
