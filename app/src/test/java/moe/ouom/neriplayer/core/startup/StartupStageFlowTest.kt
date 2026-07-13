package moe.ouom.neriplayer.core.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupStageFlowTest {
    @Test
    fun `emits stage updates from startup gates`() = runTest {
        val disclaimerAccepted = MutableStateFlow<Boolean?>(null)
        val onboardingCompleted = MutableStateFlow<Boolean?>(null)
        val stages = mutableListOf<StartupStage>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StartupStageFlow.from(
                disclaimerAccepted = disclaimerAccepted,
                startupOnboardingCompleted = onboardingCompleted
            ).toList(stages)
        }

        disclaimerAccepted.value = false
        onboardingCompleted.value = true
        disclaimerAccepted.value = true
        runCurrent()
        job.cancel()

        assertEquals(
            listOf(
                StartupStage.Loading,
                StartupStage.Disclaimer,
                StartupStage.Main
            ),
            stages
        )
    }

    @Test
    fun `keeps onboarding loading state until onboarding gate is ready`() = runTest {
        val stages = mutableListOf<StartupStage>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            StartupStageFlow.from(
                disclaimerAccepted = MutableStateFlow(true),
                startupOnboardingCompleted = MutableStateFlow(null)
            ).toList(stages)
        }

        runCurrent()
        job.cancel()

        assertEquals(listOf(StartupStage.Loading), stages)
    }
}
