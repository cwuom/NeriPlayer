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

class MainTabTransitionRequestTest {

    private val dispatcher = StandardTestDispatcher()
    private val frameClock = BroadcastFrameClock()
    private val scope = CoroutineScope(dispatcher + frameClock + Job())
    private val navigations = mutableListOf<String>()
    private val controller = MainTabTransitionController(
        scope = scope,
        initialRoute = "home",
        initialEntryId = "entry-home",
        onNavigate = { route ->
            navigations += route
            "entry-$route"
        }
    )
    private var frameTimeNanos = 0L

    @After
    fun tearDown() {
        controller.dispose()
        scope.cancel()
    }

    @Test
    fun `requesting the displayed tab while idle does nothing`() {
        controller.request("home")
        dispatcher.scheduler.runCurrent()

        assertFalse(controller.isRunning)
        assertEquals("home", controller.renderedRoute)
        assertTrue(navigations.isEmpty())
    }

    @Test
    fun `routes outside the tab order are committed without a slide`() {
        controller.request("debug/youtube")
        assertTrue(controller.isRunning)

        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("debug/youtube"), navigations)
        assertEquals("debug/youtube", controller.renderedRoute)
        assertEquals("entry-debug/youtube", controller.renderedEntryId)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `tab switch navigates after the exit slide and settles centered`() {
        controller.request("explore")
        controller.request("explore")
        dispatcher.scheduler.runCurrent()

        assertTrue(controller.isRunning)
        assertEquals("home", controller.renderedRoute)
        assertTrue(navigations.isEmpty())

        settleAnimations()

        assertEquals(listOf("explore"), navigations)
        assertEquals("explore", controller.renderedRoute)
        assertEquals("entry-explore", controller.renderedEntryId)
        assertEquals(0f, controller.offsetFraction, 0f)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `requesting the displayed tab mid slide cancels the pending navigation`() {
        controller.request("explore")
        dispatcher.scheduler.runCurrent()

        controller.request("home")
        settleAnimations()

        assertTrue(navigations.isEmpty())
        assertEquals("home", controller.renderedRoute)
        assertEquals(0f, controller.offsetFraction, 0f)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `route sync without a route keeps the current tab`() {
        controller.syncRoute(null, "entry-other")

        assertEquals("home", controller.renderedRoute)
        assertEquals("entry-home", controller.renderedEntryId)
    }

    @Test
    fun `idle route sync adopts the observed route and entry`() {
        controller.syncRoute("library", "entry-library")

        assertEquals("library", controller.renderedRoute)
        assertEquals("entry-library", controller.renderedEntryId)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `route sync during a slide only refreshes the rendered entry`() {
        controller.request("explore")

        controller.syncRoute("home", "entry-home-2")
        assertEquals("entry-home-2", controller.renderedEntryId)

        controller.syncRoute("home", null)
        controller.syncRoute("explore", "entry-explore")

        assertEquals("home", controller.renderedRoute)
        assertEquals("entry-home-2", controller.renderedEntryId)
        assertTrue(controller.isRunning)
    }

    @Test
    fun `route sync to an unrelated route aborts the slide`() {
        controller.request("explore")

        controller.syncRoute("settings", "entry-settings")
        settleAnimations()

        assertFalse(controller.isRunning)
        assertEquals("settings", controller.renderedRoute)
        assertEquals("entry-settings", controller.renderedEntryId)
        assertTrue(navigations.isEmpty())
    }

    private fun settleAnimations() {
        repeat(MAX_FRAMES) {
            dispatcher.scheduler.runCurrent()
            if (!controller.isRunning) return
            frameClock.sendFrame(frameTimeNanos)
            frameTimeNanos += FRAME_STEP_NANOS
        }
        dispatcher.scheduler.runCurrent()
    }

    private companion object {
        const val MAX_FRAMES = 20
        const val FRAME_STEP_NANOS = 1_000_000_000L
    }
}
