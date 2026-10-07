package moe.ouom.neriplayer.ui.navigation

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

class MainTabLayerScenePreparationTest {

    private val scope = CoroutineScope(StandardTestDispatcher() + BroadcastFrameClock() + Job())
    private val controller = MainTabLayerTransitionController(scope = scope, initialRoute = "home")

    @After
    fun tearDown() {
        controller.dispose()
        scope.cancel()
    }

    @Test
    fun `restored host entry is suppressed only when it opens a deeper level`() {
        assertTrue(shouldSuppressRestoredMainTabHostEntry(restoredEntry = true, initialDepth = 0, targetDepth = 1))
        assertFalse(shouldSuppressRestoredMainTabHostEntry(restoredEntry = true, initialDepth = 1, targetDepth = 1))
        assertFalse(shouldSuppressRestoredMainTabHostEntry(restoredEntry = false, initialDepth = 0, targetDepth = 2))
    }

    @Test
    fun `preparation signal without a running transition is ignored`() {
        controller.onIncomingScenePrepared(0L)

        assertEquals(
            listOf(MainTabLayerScene(route = "home", phase = MainTabLayerScenePhase.Settled)),
            controller.visibleScenes
        )
    }

    @Test
    fun `first tab transition waits for the incoming scene with the matching token`() {
        startFirstTransition(targetRoute = "explore")
        val token = enteringScene().transitionToken

        controller.onIncomingScenePrepared(token + 1)
        assertEquals(token, enteringScene().transitionToken)

        controller.onIncomingScenePrepared(token)
        assertEquals(0L, enteringScene().transitionToken)
        assertEquals(listOf("home", "explore"), controller.visibleScenes.map(MainTabLayerScene::route))

        controller.onIncomingScenePrepared(token)
        assertEquals(0L, enteringScene().transitionToken)
    }

    @Test
    fun `restored scene flag is consumed only for its own restoration token`() {
        startFirstTransition(targetRoute = "library", restored = true)
        val restorationToken = enteringScene().restorationToken
        assertTrue(enteringScene().restored)

        controller.consumeRestoredScene(restorationToken + 1)
        assertTrue(enteringScene().restored)

        controller.consumeRestoredScene(restorationToken)
        assertFalse(enteringScene().restored)

        controller.consumeRestoredScene(restorationToken)
        assertFalse(enteringScene().restored)
    }

    private fun startFirstTransition(targetRoute: String, restored: Boolean = false) {
        controller.onContainerWidthChanged(1080)
        controller.onInitialSceneFrameRendered()
        controller.request(targetRoute, restored)
    }

    private fun enteringScene(): MainTabLayerScene =
        controller.visibleScenes.single { it.phase == MainTabLayerScenePhase.Entering }
}
