package moe.ouom.neriplayer.core.startup

internal object StartupStageResolver {
    fun resolve(
        disclaimerAccepted: Boolean?,
        startupOnboardingCompleted: Boolean?
    ): StartupStage {
        return when (disclaimerAccepted) {
            null -> StartupStage.Loading
            false -> StartupStage.Disclaimer
            true -> when (startupOnboardingCompleted) {
                null -> StartupStage.Loading
                false -> StartupStage.Onboarding
                true -> StartupStage.Main
            }
        }
    }

    fun resolve(
        disclaimerAccepted: Boolean?,
        startupOnboardingCompleted: Boolean?,
        pendingDisclaimerAccepted: Boolean
    ): StartupStage {
        if (!pendingDisclaimerAccepted) {
            return resolve(disclaimerAccepted, startupOnboardingCompleted)
        }
        return resolve(
            disclaimerAccepted = true,
            startupOnboardingCompleted = startupOnboardingCompleted ?: false
        )
    }
}
