package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class HostPredictiveBackTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf<String?>(DETAIL)
    private var backAllowed by mutableStateOf(true)
    private val seekedBacks = mutableListOf<Boolean>()
    private var detailVisibility = Float.NaN
    private lateinit var transition: Transition<String?>
    private var diagnosticPhase: String? = null

    private fun setHost(seekEnabled: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides true) {
                transition = rememberHostPredictiveBackTransition(
                    targetState = selected,
                    backEnabled = selected != null && backAllowed,
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
    fun `ordinary reversal retargets the scene before returning to its start state`() {
        selected = null
        setHost()
        composeRule.mainClock.autoAdvance = false
        diagnosticPhase = "ordinary_open_start"
        recordFrame()
        composeRule.runOnIdle { selected = DETAIL }
        val startDeadline = composeRule.mainClock.currentTime + 1_000L
        while ((transition.targetState != DETAIL || detailVisibility <= 0f) &&
            composeRule.mainClock.currentTime < startDeadline
        ) {
            advanceFrame()
        }
        repeat(6) { advanceFrame() }
        val interruptedVisibility = detailVisibility
        diagnosticPhase = "ordinary_open_before_reverse"
        recordFrame()
        assertTrue(
            "先确认实际正向动画中点，当前可见量=$interruptedVisibility " +
                "current=${transition.currentState} target=${transition.targetState}",
            interruptedVisibility > 0.2f && interruptedVisibility < 0.8f
        )

        composeRule.runOnIdle { selected = null }
        diagnosticPhase = "ordinary_open_reverse"
        recordFrame()
        assertOrdinaryReversalIsContinuous(expectedTarget = null)

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(null, transition.currentState)
        assertEquals(0f, detailVisibility, 0.001f)
        assertTrue(seekedBacks.isEmpty())
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        recordFrame()
    }

    private fun recordFrame() {
        val phase = diagnosticPhase ?: return
        println("HostTransitionFrame phase=$phase ${frameSnapshot()}")
    }

    private fun frameSnapshot() = TransitionFrame(
        clockMillis = composeRule.mainClock.currentTime,
        requestedState = selected,
        currentState = transition.currentState,
        targetState = transition.targetState,
        visibility = detailVisibility
    )

    private fun assertOrdinaryReversalIsContinuous(expectedTarget: String?) {
        val samples = mutableListOf(frameSnapshot())
        repeat(64) {
            advanceFrame()
            samples += frameSnapshot()
        }
        val trace = samples.joinToString()
        val maximumStep = samples.zipWithNext().maxOf { (previous, current) ->
            abs(current.visibility - previous.visibility)
        }
        println("HostTransitionContinuity phase=$diagnosticPhase maxStep=$maximumStep trace=$trace")
        assertEquals("普通反向必须在两帧内更新目标: $trace", expectedTarget, samples[2].targetState)
        assertTrue("反向不能通过瞬切完成: $trace", samples[2].visibility > 0f && samples[2].visibility < 1f)
        for ((previous, current) in samples.zipWithNext()) {
            val elapsedMillis = current.clockMillis - previous.clockMillis
            // 中断后旧动画初值与新目标同时推进，分别允许一个线性时间步长
            val maximumChange = 2f * elapsedMillis / DURATION_MS + 0.001f
            assertTrue("整个反向过程必须保持合法可见量: $trace", current.visibility in 0f..1f)
            assertTrue(
                "反向必须逐帧连续: budget=$maximumChange previous=$previous current=$current trace=$trace",
                abs(current.visibility - previous.visibility) <= maximumChange
            )
        }
        assertEquals(expectedTarget, samples.last().currentState)
        assertEquals(expectedTarget, samples.last().targetState)
        assertEquals(if (expectedTarget == null) 0f else 1f, samples.last().visibility, 0.001f)
        assertTrue(seekedBacks.isEmpty())
    }

    @Test
    fun `ordinary close reversal retargets the detail while keeping its visible progress`() {
        setHost()
        composeRule.mainClock.autoAdvance = false
        diagnosticPhase = "ordinary_close_start"
        recordFrame()
        composeRule.runOnIdle { selected = null }
        val startDeadline = composeRule.mainClock.currentTime + 1_000L
        while ((transition.targetState != null || detailVisibility >= 1f) &&
            composeRule.mainClock.currentTime < startDeadline
        ) {
            advanceFrame()
        }
        repeat(6) { advanceFrame() }
        val interruptedVisibility = detailVisibility
        diagnosticPhase = "ordinary_close_before_reverse"
        recordFrame()
        assertTrue("先确认实际关闭动画中点: $interruptedVisibility",
            interruptedVisibility > 0.2f && interruptedVisibility < 0.8f)

        composeRule.runOnIdle { selected = DETAIL }
        diagnosticPhase = "ordinary_close_reverse"
        recordFrame()
        assertOrdinaryReversalIsContinuous(expectedTarget = DETAIL)

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(DETAIL, transition.currentState)
        assertEquals(1f, detailVisibility, 0.001f)
        assertTrue(seekedBacks.isEmpty())
    }

    @Test
    fun `ordinary navigation clears a cancelled preview before another reversal`() {
        setHost()
        composeRule.mainClock.autoAdvance = false
        diagnosticPhase = "cancel_preview"
        recordFrame()
        startGesture(0.6f)
        val seekDeadline = composeRule.mainClock.currentTime + 1_000L
        while ((transition.targetState != null || abs(detailVisibility - 0.4f) > 0.02f) &&
            composeRule.mainClock.currentTime < seekDeadline
        ) {
            advanceFrame()
        }
        assertEquals("先确认真实返回预览", 0.4f, detailVisibility, 0.02f)
        val previewVisibility = detailVisibility
        diagnosticPhase = "cancel_requested"
        recordFrame()
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        val cancelDeadline = composeRule.mainClock.currentTime + 1_000L
        while (detailVisibility <= previewVisibility + 0.001f &&
            composeRule.mainClock.currentTime < cancelDeadline
        ) {
            advanceFrame()
        }
        assertTrue("取消手势后仍按原片段回弹", detailVisibility > 0.4f && detailVisibility < 1f)
        assertEquals(DETAIL, transition.currentState)
        assertEquals(null, transition.targetState)

        composeRule.runOnIdle { selected = null }
        diagnosticPhase = "cancel_external_close"
        recordFrame()
        advanceFrame()
        advanceFrame()
        composeRule.runOnIdle { selected = DETAIL }
        diagnosticPhase = "cancel_external_reverse"
        recordFrame()
        advanceFrame()
        advanceFrame()

        assertEquals("取消预览的归属不能污染后续普通反向", DETAIL, transition.targetState)
        assertTrue("后续导航继续使用可见过渡", detailVisibility > 0f && detailVisibility < 1f)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(DETAIL, transition.currentState)
        assertEquals(1f, detailVisibility, 0.001f)
        assertTrue(seekedBacks.isEmpty())
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
    fun `back handling resumes after being disabled without a target change`() {
        setHost()

        composeRule.runOnIdle { backAllowed = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(dispatcher.hasEnabledCallbacks())
            assertEquals(DETAIL, transition.currentState)
            backAllowed = true
        }
        composeRule.waitForIdle()
        startGesture(0.5f)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(listOf(true), seekedBacks)
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

    private data class TransitionFrame(
        val clockMillis: Long,
        val requestedState: String?,
        val currentState: String?,
        val targetState: String?,
        val visibility: Float
    )
}
