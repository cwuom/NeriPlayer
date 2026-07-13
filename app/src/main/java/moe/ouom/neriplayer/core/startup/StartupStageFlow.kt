package moe.ouom.neriplayer.core.startup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal object StartupStageFlow {
    fun from(
        disclaimerAccepted: Flow<Boolean?>,
        startupOnboardingCompleted: Flow<Boolean?>
    ): Flow<StartupStage> {
        return combine(
            disclaimerAccepted,
            startupOnboardingCompleted
        ) { disclaimerAcceptedValue, startupOnboardingCompletedValue ->
            StartupStageResolver.resolve(
                disclaimerAccepted = disclaimerAcceptedValue,
                startupOnboardingCompleted = startupOnboardingCompletedValue
            )
        }.distinctUntilChanged()
    }
}
