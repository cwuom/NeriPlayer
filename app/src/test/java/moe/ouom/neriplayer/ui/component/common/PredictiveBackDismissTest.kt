package moe.ouom.neriplayer.ui.component.common

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveBackDismissTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state = PredictiveDismissState()
    private var enabled by mutableStateOf(true)
    private var dismissals = 0

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun setHandler() {
        composeRule.setContent {
            PredictiveDismissHandler(state = state, enabled = enabled) { dismissals++ }
        }
        composeRule.waitForIdle()
    }

    private fun backEvent(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = edge)

    private fun startGesture(progress: Float, edge: Int = BackEventCompat.EDGE_LEFT) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f, edge)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(progress, edge)) }
        composeRule.waitForIdle()
    }

    @Test
    fun `gesture progress and edge follow the back events without dismissing`() {
        setHandler()

        startGesture(0.4f, BackEventCompat.EDGE_RIGHT)

        composeRule.runOnIdle {
            assertEquals(0.4f, state.progress, 0.001f)
            assertEquals(BackEventCompat.EDGE_RIGHT, state.swipeEdge)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun `releasing the gesture dismisses and keeps the released shape for the exit animation`() {
        setHandler()
        startGesture(0.7f)

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        assertEquals(0.7f, state.progress, 0.001f)
        state.reset()
        assertEquals(0f, state.progress, 0f)
    }

    @Test
    fun `cancelling the gesture springs the surface back without dismissing`() {
        setHandler()
        startGesture(0.6f)

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        assertEquals(0, dismissals)
        assertEquals(0f, state.progress, 0.001f)
    }

    @Test
    fun `disabled handler leaves back to the system`() {
        enabled = false
        setHandler()

        composeRule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
        enabled = true
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun `transform scales shifts and rounds in proportion to the gesture`() {
        val idle = predictiveDismissTransform(0f, BackEventCompat.EDGE_LEFT, 1000f, 56f)
        assertEquals(PredictiveDismissTransform(1f, 0f, 0f), idle)

        val full = predictiveDismissTransform(1f, BackEventCompat.EDGE_LEFT, 1000f, 56f)
        assertEquals(PREDICTIVE_DISMISS_MIN_SCALE, full.scale, 0.0001f)
        assertEquals(50f, full.translationX, 0.0001f)
        assertEquals(56f, full.cornerRadius, 0.0001f)

        val rightEdge = predictiveDismissTransform(0.5f, BackEventCompat.EDGE_RIGHT, 1000f, 56f)
        assertEquals(0.95f, rightEdge.scale, 0.0001f)
        assertEquals(-25f, rightEdge.translationX, 0.0001f)
        assertEquals(28f, rightEdge.cornerRadius, 0.0001f)

        assertEquals(full, predictiveDismissTransform(3f, BackEventCompat.EDGE_LEFT, 1000f, 56f))
        assertEquals(idle, predictiveDismissTransform(-1f, BackEventCompat.EDGE_LEFT, 1000f, 56f))
    }
}
