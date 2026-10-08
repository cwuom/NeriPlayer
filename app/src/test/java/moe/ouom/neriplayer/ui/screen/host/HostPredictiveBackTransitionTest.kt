package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostPredictiveBackTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf<String?>(DETAIL)
    private val seekedBacks = mutableListOf<Boolean>()
    private var detailVisibility = Float.NaN
    private lateinit var transition: Transition<String?>

    private fun setHost(seekEnabled: Boolean = true) {
        composeRule.setContent {
            transition = rememberHostPredictiveBackTransition(
                targetState = selected,
                backEnabled = selected != null,
                backTargetState = null,
                onBack = { seeked ->
                    seekedBacks += seeked
                    selected = null
                },
                label = "test_host",
                seekEnabled = seekEnabled
            )
            val visibility by transition.animateFloat(
                transitionSpec = { tween(DURATION_MS, easing = LinearEasing) },
                label = "detail_visibility"
            ) { state -> if (state == null) 0f else 1f }
            detailVisibility = visibility
        }
        composeRule.waitForIdle()
    }

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun backEvent(progress: Float) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    private fun startGesture(vararg progress: Float) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        progress.forEach { value ->
            composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(value)) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `gesture progress seeks the detail transition toward the back target`() {
        setHost()

        startGesture(0.25f, 0.5f)

        composeRule.runOnIdle {
            assertEquals(DETAIL, transition.currentState)
            assertEquals(null, transition.targetState)
            assertEquals(0.5f, detailVisibility, 0.02f)
            assertTrue(seekedBacks.isEmpty())
        }
    }

    @Test
    fun `releasing the gesture commits back and finishes the transition`() {
        setHost()
        startGesture(0.4f)

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(listOf(true), seekedBacks)
        assertEquals(null, transition.currentState)
        assertEquals(null, transition.targetState)
        assertEquals(0f, detailVisibility, 0.001f)
    }

    @Test
    fun `cancelling the gesture springs back to the detail without navigating`() {
        setHost()
        startGesture(0.6f)

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        assertTrue(seekedBacks.isEmpty())
        assertEquals(DETAIL, selected)
        assertEquals(DETAIL, transition.currentState)
        assertEquals(DETAIL, transition.targetState)
        assertEquals(1f, detailVisibility, 0.001f)
    }

    @Test
    fun `plain back press keeps the regular close animation`() {
        setHost()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(DURATION_MS / 2L)

        assertEquals(listOf(false), seekedBacks)
        assertEquals(null, transition.targetState)
        val midway = detailVisibility
        assertTrue("expected a mid-animation value but was $midway", midway > 0.2f && midway < 0.8f)

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(null, transition.currentState)
        assertEquals(0f, detailVisibility, 0.001f)
    }

    @Test
    fun `disabled seeking still navigates back on release without moving the scene`() {
        setHost(seekEnabled = false)
        startGesture(0.5f)

        composeRule.runOnIdle {
            assertEquals(DETAIL, transition.targetState)
            assertEquals(1f, detailVisibility, 0.001f)
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(false), seekedBacks)
        assertEquals(null, transition.currentState)
    }

    @Test
    fun `cancel duration covers the seeked share of the transition`() {
        assertEquals(150, hostBackCancelDurationMillis(0.5f, 300_000_000L))
        assertEquals(0, hostBackCancelDurationMillis(0f, 300_000_000L))
    }

    private companion object {
        const val DETAIL = "detail"
        const val DURATION_MS = 300
    }
}
