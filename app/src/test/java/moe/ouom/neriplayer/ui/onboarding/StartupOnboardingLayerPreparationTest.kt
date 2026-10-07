package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupOnboardingLayerPreparationTest {

    private val scope = CoroutineScope(StandardTestDispatcher() + BroadcastFrameClock() + Job())
    private val controller = StartupOnboardingLayerTransitionController(scope = scope, initialStepIndex = 0)

    @After
    fun tearDown() {
        controller.dispose()
        scope.cancel()
    }

    @Test
    fun `idle layer cannot reverse and ignores preparation signals`() {
        controller.onIncomingScenePrepared(0L)

        assertFalse(controller.canReverseTo(0))
        assertEquals(
            listOf(StartupOnboardingLayerScene(stepIndex = 0, phase = StartupOnboardingLayerScenePhase.Settled)),
            controller.visibleScenes
        )
    }

    @Test
    fun `running transition can only reverse to the step it is leaving`() {
        startTransitionTo(2)

        assertTrue(controller.canReverseTo(0))
        assertFalse(controller.canReverseTo(1))
        assertFalse(controller.canReverseTo(2))
    }

    @Test
    fun `incoming step starts animating only after its own preparation token`() {
        startTransitionTo(1)
        val token = enteringScene().transitionToken
        assertTrue(enteringScene().preparing)

        controller.onIncomingScenePrepared(token + 1)
        assertTrue(enteringScene().preparing)

        controller.onIncomingScenePrepared(token)
        val animating = enteringScene()
        assertFalse(animating.preparing)
        assertEquals(0L, animating.transitionToken)
        assertEquals(listOf(0, 1), controller.visibleScenes.map(StartupOnboardingLayerScene::stepIndex))

        controller.onIncomingScenePrepared(token)
        assertEquals(animating, enteringScene())
    }

    private fun startTransitionTo(stepIndex: Int) {
        controller.onContainerWidthChanged(1080)
        controller.onInitialSceneFrameRendered()
        controller.request(stepIndex)
    }

    private fun enteringScene(): StartupOnboardingLayerScene =
        controller.visibleScenes.single { it.phase == StartupOnboardingLayerScenePhase.Entering }
}
