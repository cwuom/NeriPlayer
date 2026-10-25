package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class HostChildTransitionRecoveryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf<String?>(DETAIL)
    private lateinit var hostTransition: Transition<String?>
    private val liveScopes = mutableMapOf<String?, AnimatedVisibilityScope>()
    private val visibilityValues = mutableMapOf<String?, State<Float>>()
    private val seekedBacks = mutableListOf<Boolean>()

    @Composable
    @OptIn(ExperimentalTransitionApi::class)
    private fun <S> rememberChildScope(
        hostTransition: Transition<S>,
        contentState: S,
        label: String
    ): AnimatedVisibilityScope {
        val child = hostTransition.createChildTransition(label) {
            if (it == contentState) EnterExitState.Visible else EnterExitState.PostExit
        }
        return remember(child) {
            object : AnimatedVisibilityScope {
                override val transition = child
            }
        }
    }

    private fun setHost() {
        composeRule.setContent {
            hostTransition = rememberHostPredictiveBackTransition(
                targetState = selected,
                backEnabled = selected != null,
                backTargetState = null,
                onBack = { seeked ->
                    seekedBacks += seeked
                    selected = null
                },
                label = "shared_scope_host"
            )
            hostTransition.AnimatedContent(
                transitionSpec = {
                    fadeIn(tween(PAGE_FADE_MS, easing = LinearEasing)) togetherWith
                        (fadeOut(tween(PAGE_FADE_MS, easing = LinearEasing)) +
                            ExitTransition.KeepUntilTransitionsFinished)
                }
            ) { contentState ->
                val scope = rememberChildScope(
                    hostTransition = hostTransition,
                    contentState = contentState,
                    label = "shared_scope_$contentState"
                )
                visibilityValues[contentState] = scope.transition.animateFloat(
                    transitionSpec = { tween(SHARED_ANIMATION_MS, easing = LinearEasing) },
                    label = "shared_visibility_$contentState"
                ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
                DisposableEffect(scope) {
                    liveScopes[contentState] = scope
                    onDispose {
                        if (liveScopes[contentState] === scope) liveScopes.remove(contentState)
                    }
                }
                Box(Modifier.size(20.dp))
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `host seeking supports reversal and cancellation without changing scope identity`() {
        setHost()
        val detailScope = requireNotNull(liveScopes[DETAIL])

        startGesture()
        seekAndAssert(0.2f)
        val nullScope = requireNotNull(liveScopes[null])
        seekAndAssert(0.65f)
        seekAndAssert(0.35f)

        assertSame(detailScope, liveScopes[DETAIL])
        assertSame(nullScope, liveScopes[null])
        assertEquals(EnterExitState.PostExit, detailScope.transition.targetState)
        assertEquals(EnterExitState.Visible, nullScope.transition.targetState)
        assertTrue(seekedBacks.isEmpty())

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        val reboundFrames = advanceUntil("取消必须恢复 detail 并释放 nullable 退出页") {
            hostTransition.currentState == DETAIL && hostTransition.targetState == DETAIL &&
                !liveScopes.containsKey(null) &&
                detailScope.transition.currentState == EnterExitState.Visible &&
                detailScope.transition.targetState == EnterExitState.Visible &&
                abs(visibility(DETAIL) - 1f) <= TOLERANCE
        }
        for ((previous, current) in reboundFrames.zipWithNext()) {
            assertTrue(
                "取消回弹不能二次减少可见量: previous=$previous current=$current trace=$reboundFrames",
                current.detailVisibility >= previous.detailVisibility - TOLERANCE
            )
        }

        assertSame(detailScope, liveScopes[DETAIL])
        assertEquals(DETAIL, selected)
        assertEquals(EnterExitState.Visible, detailScope.transition.currentState)
        assertEquals(EnterExitState.Visible, detailScope.transition.targetState)
        assertEquals(1f, visibility(DETAIL), TOLERANCE)
        assertTrue(seekedBacks.isEmpty())
    }

    @Test
    fun `committing a seek keeps the outgoing scope until the host finishes`() {
        setHost()
        val detailScope = requireNotNull(liveScopes[DETAIL])
        startGesture()
        seekAndAssert(0.2f)
        seekAndAssert(0.65f)
        seekAndAssert(0.35f)
        val nullScope = requireNotNull(liveScopes[null])

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        advanceUntil("先确认手势提交事件真正到达 host") {
            seekedBacks == listOf(true) && selected == null
        }

        assertEquals(listOf(true), seekedBacks)
        assertSame(detailScope, liveScopes[DETAIL])
        assertSame(nullScope, liveScopes[null])
        assertTrue("提交后共享元素仍要沿当前进度运动: ${snapshot()}",
            visibility(DETAIL) > 0f && visibility(DETAIL) < 1f)
        advanceUntil("提交必须到达 nullable 页并释放 detail") {
            hostTransition.currentState == null && hostTransition.targetState == null &&
                !liveScopes.containsKey(DETAIL)
        }

        assertSame(nullScope, liveScopes[null])
        assertEquals(null, selected)
        assertEquals(EnterExitState.Visible, nullScope.transition.currentState)
        assertEquals(EnterExitState.Visible, nullScope.transition.targetState)
        assertEquals(1f, visibility(null), TOLERANCE)
    }

    @Test
    fun `ordinary reversals preserve shared progress and keep the exiting page alive`() {
        setHost()
        val detailScope = requireNotNull(liveScopes[DETAIL])
        composeRule.runOnIdle { selected = null }
        advanceUntil("先确认关闭片段真正开始") {
            hostTransition.targetState == null && visibility(DETAIL) < 0.9f
        }
        repeat(5) { advanceFrame() }
        assertTrue("短页面淡出结束后仍要保留未完成的共享元素: ${snapshot()}",
            visibility(DETAIL) > 0.2f && visibility(DETAIL) < 0.8f)
        assertTrue(liveScopes.containsKey(DETAIL))
        val nullScope = requireNotNull(liveScopes[null])

        composeRule.runOnIdle { selected = DETAIL }
        assertContinuousUntilRest(DETAIL)
        assertSame(detailScope, liveScopes[DETAIL])
        assertFalse(liveScopes.containsKey(null))

        composeRule.runOnIdle { selected = null }
        advanceUntil("第二次关闭必须进入中间进度") {
            hostTransition.targetState == null && visibility(DETAIL) < 0.8f
        }
        val nextNullScope = requireNotNull(liveScopes[null])
        composeRule.runOnIdle { selected = DETAIL }
        advanceUntil("先确认第二次普通反向已经提交") {
            hostTransition.targetState == DETAIL && visibility(DETAIL) > 0.2f
        }
        assertSame(detailScope, liveScopes[DETAIL])
        assertSame(nextNullScope, liveScopes[null])
        composeRule.runOnIdle { selected = null }
        assertContinuousUntilRest(null)

        assertFalse(liveScopes.containsKey(DETAIL))
        assertSame(nextNullScope, liveScopes[null])
        assertTrue("已退出又重新进入的 nullable 页不能复用失效的 scope", nullScope !== nextNullScope)
        assertTrue(seekedBacks.isEmpty())
    }

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun backEvent(progress: Float) = BackEventCompat(
        touchX = 0f,
        touchY = 0f,
        progress = progress,
        swipeEdge = BackEventCompat.EDGE_LEFT
    )

    private fun startGesture() {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
    }

    private fun seekAndAssert(progress: Float) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(progress)) }
        advanceUntil("共享 child 必须跟随真实手势进度 $progress") {
            liveScopes.containsKey(null) &&
                abs(visibility(DETAIL) - (1f - progress)) <= TOLERANCE &&
                abs(visibility(null) - progress) <= TOLERANCE
        }
        assertEquals(DETAIL, hostTransition.currentState)
        assertEquals(null, hostTransition.targetState)
        assertEquals(1f - progress, visibility(DETAIL), TOLERANCE)
        assertEquals(progress, visibility(null), TOLERANCE)
    }

    private fun assertContinuousUntilRest(expectedState: String?) {
        val frames = mutableListOf(snapshot())
        repeat(64) {
            advanceFrame()
            frames += snapshot()
        }
        val trace = frames.joinToString()
        println("HostSharedScopeContinuity expected=$expectedState trace=$trace")
        assertEquals("普通反向应及时更新 host target: $trace", expectedState, frames[2].targetState)
        assertTrue("共享过渡不能瞬切: $trace", frames[2].detailVisibility > 0f &&
            frames[2].detailVisibility < 1f)
        for ((previous, current) in frames.zipWithNext()) {
            val budget = 2f * (current.clockMillis - previous.clockMillis) / SHARED_ANIMATION_MS + 0.001f
            assertTrue("共享可见量必须合法: $trace", current.detailVisibility in 0f..1f)
            assertTrue("共享反向必须逐帧连续: budget=$budget previous=$previous current=$current trace=$trace",
                abs(current.detailVisibility - previous.detailVisibility) <= budget)
        }
        assertEquals(expectedState, hostTransition.currentState)
        assertEquals(expectedState, hostTransition.targetState)
        assertEquals(if (expectedState == DETAIL) 1f else 0f, visibility(DETAIL), TOLERANCE)
        assertEquals(if (expectedState == null) 1f else 0f, visibility(null), TOLERANCE)
        assertTrue(seekedBacks.isEmpty())
    }

    private fun advanceUntil(description: String, condition: () -> Boolean): List<SharedFrame> {
        val deadline = composeRule.mainClock.currentTime + 1_500L
        val frames = mutableListOf(snapshot())
        while (!condition() && composeRule.mainClock.currentTime < deadline) {
            advanceFrame()
            frames += snapshot()
        }
        println("HostSharedScopeWait description=$description trace=$frames")
        assertTrue("$description: ${snapshot()} live=${liveScopes.keys} trace=$frames", condition())
        return frames
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun visibility(contentState: String?) = visibilityValues[contentState]?.value ?: Float.NaN

    private fun snapshot() = SharedFrame(
        clockMillis = composeRule.mainClock.currentTime,
        currentState = hostTransition.currentState,
        targetState = hostTransition.targetState,
        detailCurrentState = liveScopes[DETAIL]?.transition?.currentState,
        detailTargetState = liveScopes[DETAIL]?.transition?.targetState,
        nullableCurrentState = liveScopes[null]?.transition?.currentState,
        nullableTargetState = liveScopes[null]?.transition?.targetState,
        detailVisibility = visibility(DETAIL),
        nullableVisibility = visibility(null)
    )

    private data class SharedFrame(
        val clockMillis: Long,
        val currentState: String?,
        val targetState: String?,
        val detailCurrentState: EnterExitState?,
        val detailTargetState: EnterExitState?,
        val nullableCurrentState: EnterExitState?,
        val nullableTargetState: EnterExitState?,
        val detailVisibility: Float,
        val nullableVisibility: Float
    )

    private companion object {
        const val DETAIL = "detail"
        const val PAGE_FADE_MS = 48
        const val SHARED_ANIMATION_MS = 300
        const val TOLERANCE = 0.02f
    }
}
