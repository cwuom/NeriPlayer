package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupOnboardingLayerTransitionControllerTest {
    @Test
    fun `prepared incoming scene animates to the target and settles`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.request(1)
            val preparing = controller.visibleScenes
            val entering = preparing.last()

            assertEquals(
                listOf(
                    StartupOnboardingLayerScenePhase.Exiting,
                    StartupOnboardingLayerScenePhase.Entering
                ),
                preparing.map(StartupOnboardingLayerScene::phase)
            )
            assertTrue(entering.preparing)
            assertNotEquals(0L, entering.transitionToken)
            assertEquals(1, entering.direction)

            controller.onIncomingScenePrepared(entering.transitionToken)
            val animating = controller.visibleScenes.last()

            assertFalse(animating.preparing)
            assertEquals(0L, animating.transitionToken)

            advanceUntilIdle()

            assertFalse(controller.isRunning)
            assertEquals(
                listOf(StartupOnboardingLayerScene(1, StartupOnboardingLayerScenePhase.Settled)),
                controller.visibleScenes
            )
            assertEquals(
                StartupOnboardingLayerSceneMotion(offset = IntOffset.Zero, alpha = 1f),
                controller.motionFor(controller.visibleScenes.single(), widthPx = 1000)
            )
        }
    }

    @Test
    fun `stale or unexpected preparation callbacks do not start animation`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.onIncomingScenePrepared(1L)

            assertFalse(controller.isRunning)

            controller.request(1)
            val token = controller.visibleScenes.last().transitionToken
            controller.onIncomingScenePrepared(token + 1)
            advanceUntilIdle()

            assertTrue(controller.visibleScenes.last().preparing)

            controller.onIncomingScenePrepared(token)
            controller.onIncomingScenePrepared(token)
            advanceUntilIdle()

            assertEquals(1, controller.visibleScenes.single().stepIndex)
        }
    }

    @Test
    fun `reversal is only offered for the step being left`() = runTest {
        withController(initialStepIndex = 1) { controller ->
            controller.makeReady()

            assertFalse(controller.canReverseTo(1))

            controller.request(2)

            assertTrue(controller.canReverseTo(1))
            assertFalse(controller.canReverseTo(2))
            assertFalse(controller.canReverseTo(0))
        }
    }

    @Test
    fun `returning to the previous step while preparing cancels the transition`() = runTest {
        withController(initialStepIndex = 1) { controller ->
            controller.makeReady()
            controller.request(2)

            controller.request(1)

            assertFalse(controller.isRunning)
            assertEquals(
                listOf(StartupOnboardingLayerScene(1, StartupOnboardingLayerScenePhase.Settled)),
                controller.visibleScenes
            )
        }
    }

    @Test
    fun `replacing a preparing target backwards flips the direction`() = runTest {
        withController(initialStepIndex = 1) { controller ->
            controller.makeReady()
            controller.request(2)
            val firstToken = controller.visibleScenes.last().transitionToken

            controller.request(0)
            val scenes = controller.visibleScenes

            assertEquals(listOf(1, 0), scenes.map(StartupOnboardingLayerScene::stepIndex))
            assertEquals(-1, scenes.last().direction)
            assertTrue(scenes.last().transitionToken > firstToken)
            assertTrue(controller.canReverseTo(1))
        }
    }

    @Test
    fun `requesting the current target while preparing keeps the preparation`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.request(1)
            val token = controller.visibleScenes.last().transitionToken

            controller.request(1)

            assertEquals(token, controller.visibleScenes.last().transitionToken)
            assertTrue(controller.isRunning)
        }
    }

    @Test
    fun `going back during the animation reverses from the current progress`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.request(1)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            advanceTimeBy(FRAME_MS * 5)
            runCurrent()
            val enteringOffset = controller.motionFor(controller.visibleScenes.last(), 1000).offset.x

            controller.request(0)
            val reversed = controller.visibleScenes

            assertTrue(enteringOffset in 1 until 200)
            assertEquals(listOf(1, 0), reversed.map(StartupOnboardingLayerScene::stepIndex))
            assertEquals(-1, reversed.last().direction)
            assertFalse(reversed.last().preparing)
            assertTrue(controller.canReverseTo(1))

            advanceUntilIdle()

            assertFalse(controller.isRunning)
            assertEquals(0, controller.visibleScenes.single().stepIndex)
        }
    }

    @Test
    fun `a target requested during the animation starts after settling`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.request(1)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.request(2)
            assertEquals(listOf(0, 1), controller.visibleScenes.map(StartupOnboardingLayerScene::stepIndex))
            advanceUntilIdle()

            val queued = controller.visibleScenes
            assertEquals(listOf(1, 2), queued.map(StartupOnboardingLayerScene::stepIndex))
            assertTrue(queued.last().preparing)
        }
    }

    @Test
    fun `requesting the current target during the animation drops a queued step`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.request(1)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.request(2)
            controller.request(1)
            advanceUntilIdle()

            assertFalse(controller.isRunning)
            assertEquals(1, controller.visibleScenes.single().stepIndex)
        }
    }

    @Test
    fun `pending target starts only after width and first frame are both available`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.request(2)
            controller.onInitialSceneFrameRendered()
            controller.onContainerWidthChanged(0)

            assertFalse(controller.isRunning)

            controller.onContainerWidthChanged(720)

            assertEquals(listOf(0, 2), controller.visibleScenes.map(StartupOnboardingLayerScene::stepIndex))

            controller.onContainerWidthChanged(1080)
            controller.onInitialSceneFrameRendered()

            assertEquals(listOf(0, 2), controller.visibleScenes.map(StartupOnboardingLayerScene::stepIndex))
        }
    }

    @Test
    fun `cancelled pending target and current requests leave the first step settled`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.request(1)
            controller.request(0)
            controller.makeReady()

            assertFalse(controller.isRunning)

            controller.request(0)

            assertFalse(controller.isRunning)
            assertEquals(0, controller.visibleScenes.single().stepIndex)
        }
    }

    @Test
    fun `backward request after readiness slides in from the opposite side`() = runTest {
        withController(initialStepIndex = 2) { controller ->
            controller.makeReady()

            controller.request(0)

            assertEquals(-1, controller.visibleScenes.last().direction)
        }
    }

    @Test
    fun `dispose stops a running animation without settling a stale transition`() = runTest {
        withController(initialStepIndex = 0) { controller ->
            controller.makeReady()
            controller.request(1)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.dispose()
            advanceUntilIdle()

            assertFalse(controller.isRunning)
            assertEquals(1, controller.visibleScenes.single().stepIndex)
        }
    }

    private suspend fun TestScope.withController(
        initialStepIndex: Int,
        block: suspend TestScope.(StartupOnboardingLayerTransitionController) -> Unit
    ) {
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + VirtualFrameClock(testScheduler) + Job()
        )
        val controller = StartupOnboardingLayerTransitionController(scope, initialStepIndex)
        try {
            block(controller)
        } finally {
            controller.dispose()
            scope.cancel()
        }
    }

    private fun StartupOnboardingLayerTransitionController.makeReady() {
        onContainerWidthChanged(1080)
        onInitialSceneFrameRendered()
    }

    private class VirtualFrameClock(
        private val scheduler: TestCoroutineScheduler
    ) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MS)
            return onFrame(scheduler.currentTime * 1_000_000L)
        }
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
