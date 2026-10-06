package moe.ouom.neriplayer.ui.navigation

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainTabLayerTransitionControllerTest {
    @Test
    fun `first tab change waits for the incoming scene before animating`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(EXPLORE)
            val (exiting, entering) = controller.visibleScenes

            assertEquals(MainTabLayerScene(HOME, MainTabLayerScenePhase.Exiting, direction = 1), exiting)
            assertEquals(EXPLORE, entering.route)
            assertEquals(MainTabLayerScenePhase.Entering, entering.phase)
            assertNotEquals(0L, entering.transitionToken)
            assertEquals(1f, controller.offsetFractionFor(entering), 0f)
            assertEquals(0f, controller.offsetFractionFor(exiting), 0f)

            controller.onIncomingScenePrepared(entering.transitionToken)

            assertEquals(0L, controller.visibleScenes.last().transitionToken)

            advanceUntilIdle()

            assertEquals(
                listOf(
                    MainTabLayerScene(
                        route = EXPLORE,
                        phase = MainTabLayerScenePhase.Settled,
                        restorationToken = entering.restorationToken
                    )
                ),
                controller.visibleScenes
            )
        }
    }

    @Test
    fun `later tab changes animate immediately`() = runTest {
        withController { controller ->
            controller.makeReady()
            completeFirstTransitionTo(controller, EXPLORE)

            controller.request(LIBRARY)
            val entering = controller.visibleScenes.last()

            assertEquals(LIBRARY, entering.route)
            assertEquals(0L, entering.transitionToken)

            advanceUntilIdle()

            assertEquals(LIBRARY, controller.visibleScenes.single().route)
        }
    }

    @Test
    fun `unexpected preparation callbacks are ignored`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.onIncomingScenePrepared(1L)

            assertEquals(MainTabLayerScenePhase.Settled, controller.visibleScenes.single().phase)

            controller.request(EXPLORE)
            val token = controller.visibleScenes.last().transitionToken
            controller.onIncomingScenePrepared(token + 1)
            advanceUntilIdle()

            assertEquals(token, controller.visibleScenes.last().transitionToken)

            controller.onIncomingScenePrepared(token)
            controller.onIncomingScenePrepared(token)
            advanceUntilIdle()

            assertEquals(EXPLORE, controller.visibleScenes.single().route)
        }
    }

    @Test
    fun `returning to the origin before preparation finishes cancels the change`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(EXPLORE)

            controller.request(HOME)

            assertEquals(
                listOf(MainTabLayerScene(HOME, MainTabLayerScenePhase.Settled)),
                controller.visibleScenes
            )
        }
    }

    @Test
    fun `retargeting during preparation restarts from the original tab`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(EXPLORE)
            val firstToken = controller.visibleScenes.last().transitionToken

            controller.request(LIBRARY)
            val scenes = controller.visibleScenes

            assertEquals(listOf(HOME, LIBRARY), scenes.map(MainTabLayerScene::route))
            assertTrue(scenes.last().transitionToken > firstToken)

            controller.request(LIBRARY)

            assertEquals(listOf(HOME, LIBRARY), controller.visibleScenes.map(MainTabLayerScene::route))
        }
    }

    @Test
    fun `tab requested during an animation starts after settling`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(LIBRARY)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.request(SETTINGS)

            assertEquals(listOf(HOME, LIBRARY), controller.visibleScenes.map(MainTabLayerScene::route))

            advanceUntilIdle()

            assertEquals(SETTINGS, controller.visibleScenes.single().route)
        }
    }

    @Test
    fun `requesting the running target clears a queued tab`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(LIBRARY)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.request(SETTINGS)
            controller.request(LIBRARY)
            advanceUntilIdle()

            assertEquals(LIBRARY, controller.visibleScenes.single().route)
        }
    }

    @Test
    fun `queued non tab route is dropped after settling`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(EXPLORE)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.request(NON_TAB_ROUTE)
            advanceUntilIdle()

            assertEquals(
                MainTabLayerScene(EXPLORE, MainTabLayerScenePhase.Settled, restorationToken = 1L),
                controller.visibleScenes.single()
            )
        }
    }

    @Test
    fun `non tab and current routes never start a transition`() = runTest {
        withController { controller ->
            controller.makeReady()

            controller.request(NON_TAB_ROUTE)
            controller.request(HOME)

            assertEquals(
                listOf(MainTabLayerScene(HOME, MainTabLayerScenePhase.Settled)),
                controller.visibleScenes
            )
        }
    }

    @Test
    fun `restored target stays flagged until its own token is consumed`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(EXPLORE, restored = true)
            val entering = controller.visibleScenes.last()

            assertTrue(entering.restored)

            controller.consumeRestoredScene(entering.restorationToken + 1)

            assertTrue(controller.visibleScenes.last().restored)

            controller.consumeRestoredScene(entering.restorationToken)

            assertFalse(controller.visibleScenes.last().restored)

            controller.consumeRestoredScene(entering.restorationToken)

            assertFalse(controller.visibleScenes.last().restored)
        }
    }

    @Test
    fun `pending change starts once width and the first frame are available`() = runTest {
        withController { controller ->
            controller.request(EXPLORE)
            controller.request(EXPLORE)
            controller.onContainerWidthChanged(0)
            controller.onInitialSceneFrameRendered()

            assertEquals(MainTabLayerScenePhase.Settled, controller.visibleScenes.single().phase)

            controller.onContainerWidthChanged(720)

            assertEquals(listOf(HOME, EXPLORE), controller.visibleScenes.map(MainTabLayerScene::route))

            controller.onContainerWidthChanged(1080)
            controller.onInitialSceneFrameRendered()

            assertEquals(listOf(HOME, EXPLORE), controller.visibleScenes.map(MainTabLayerScene::route))
        }
    }

    @Test
    fun `pending change back to the current tab is discarded`() = runTest {
        withController { controller ->
            controller.request(EXPLORE)
            controller.request(HOME)
            controller.makeReady()

            assertEquals(
                listOf(MainTabLayerScene(HOME, MainTabLayerScenePhase.Settled)),
                controller.visibleScenes
            )
        }
    }

    @Test
    fun `dispose cancels the running animation`() = runTest {
        withController { controller ->
            controller.makeReady()
            controller.request(EXPLORE)
            controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
            runCurrent()

            controller.dispose()
            advanceUntilIdle()

            assertEquals(
                listOf(MainTabLayerScene(EXPLORE, MainTabLayerScenePhase.Settled)),
                controller.visibleScenes
            )
        }
    }

    @Test
    fun `restored host entry is suppressed only for deeper targets`() {
        assertTrue(shouldSuppressRestoredMainTabHostEntry(restoredEntry = true, initialDepth = 0, targetDepth = 1))
        assertFalse(shouldSuppressRestoredMainTabHostEntry(restoredEntry = true, initialDepth = 1, targetDepth = 1))
        assertFalse(shouldSuppressRestoredMainTabHostEntry(restoredEntry = false, initialDepth = 0, targetDepth = 1))
    }

    private suspend fun TestScope.withController(
        block: suspend TestScope.(MainTabLayerTransitionController) -> Unit
    ) {
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + VirtualFrameClock(testScheduler) + Job()
        )
        val controller = MainTabLayerTransitionController(scope, HOME)
        try {
            block(controller)
        } finally {
            controller.dispose()
            scope.cancel()
        }
    }

    private fun MainTabLayerTransitionController.makeReady() {
        onContainerWidthChanged(1080)
        onInitialSceneFrameRendered()
    }

    private fun TestScope.completeFirstTransitionTo(
        controller: MainTabLayerTransitionController,
        route: String
    ) {
        controller.request(route)
        controller.onIncomingScenePrepared(controller.visibleScenes.last().transitionToken)
        advanceUntilIdle()
    }

    private class VirtualFrameClock(
        private val scheduler: TestCoroutineScheduler
    ) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(16L)
            return onFrame(scheduler.currentTime * 1_000_000L)
        }
    }

    private companion object {
        val HOME = Destinations.Home.route
        val EXPLORE = Destinations.Explore.route
        val LIBRARY = Destinations.Library.route
        val SETTINGS = Destinations.Settings.route
        const val NON_TAB_ROUTE = "playlist_detail"
    }
}
