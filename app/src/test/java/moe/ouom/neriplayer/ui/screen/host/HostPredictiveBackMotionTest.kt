package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
class HostPredictiveBackMotionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf(true)
    private lateinit var transition: Transition<Boolean>
    private lateinit var progress: State<Float>
    private val backs = mutableListOf<Boolean>()

    private fun setHost() {
        composeRule.setContent {
            CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides true) {
                transition = rememberHostPredictiveBackTransition(
                    targetState = selected,
                    backEnabled = selected,
                    backTargetState = false,
                    onBack = { seeked -> backs += seeked; selected = false },
                    label = "slow_start_back_motion",
                    previewEasing = FastOutLinearInEasing,
                    cancelMinimumDurationMillis = MinimumCancelMillis
                )
                progress = transition.animateFloat(
                    transitionSpec = { tween(DurationMillis, easing = LinearEasing) },
                    label = "auditable_host_fraction"
                ) { if (it) 0f else 1f }
                Box(Modifier.size(20.dp))
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `configured preview starts slowly and follows monotonic progress and finger reversal`() {
        setHost()
        startGesture()
        val outward = listOf(0.05f, 0.1f, 0.2f, 0.4f, 0.7f).map { raw ->
            seek(raw)
            assertTrue("小进度响应应慢于原始手势 raw=$raw actual=${progress.value}",
                progress.value < raw - 0.01f)
            progress.value
        }
        assertTrue("向外拖动必须单调: $outward", outward.zipWithNext().all { (a, b) -> b > a })
        val returning = listOf(0.4f, 0.2f, 0.05f).map { raw -> seek(raw); progress.value }
        assertTrue("回拖必须跟随手指减少进度: $returning",
            returning.zipWithNext().all { (a, b) -> b < a })
        assertTrue(selected)
        assertTrue(transition.currentState)
        assertFalse(transition.targetState)
        assertTrue(backs.isEmpty())
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        advanceUntil("取消回拖后必须恢复原页") { atRest(true) }
        assertTrue(backs.isEmpty())
    }

    @Test
    fun `nonlinear preview configuration preserves the ordinary return timeline`() {
        setHost()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        val frames = advanceUntil("普通返回必须沿原有时长完成") { atRest(false) }
        val middle = frames.filter { it.value in 0.08f..0.92f }
        assertTrue("普通返回应保留连续的中间帧: $frames", middle.size >= 5)
        for ((previous, current) in middle.zipWithNext()) {
            val originalStep = (current.clockMillis - previous.clockMillis).toFloat() / DurationMillis
            assertEquals("预览曲线不能改变普通返回的时钟步长", originalStep,
                current.value - previous.value, Tolerance)
        }
        assertEquals(listOf(false), backs)
    }

    @Test
    fun `committing from eased progress continues without an endpoint jump`() {
        setHost()
        startGesture()
        seek(0.35f)
        val before = snapshot()
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        assertEquals("提交事件不能把当前画面瞬切到终点", before.value, progress.value, Tolerance)
        val frames = listOf(before) + advanceUntil("提交必须完成同一 Host 动画") {
            atRest(false) && backs == listOf(true)
        }
        for ((previous, current) in frames.zipWithNext()) {
            val elapsed = (current.clockMillis - previous.clockMillis).toFloat()
            val budget = 2f * elapsed / DurationMillis + Tolerance
            assertTrue("提交单帧变化超过实际时钟预算 $budget: $previous -> $current trace=$frames",
                abs(current.value - previous.value) <= budget)
            assertTrue("提交不能先退回原端点: $previous -> $current trace=$frames",
                current.value >= previous.value - Tolerance)
        }
        assertTrue("提交必须保留中间帧: $frames",
            frames.any { it.value > before.value + 0.02f && it.value < 0.98f })
        assertEquals(1f, progress.value, Tolerance)
        assertEquals(listOf(true), backs)
    }

    @Test
    fun `small preview cancellation remains animated for the configured minimum and then restores`() {
        setHost()
        startGesture()
        seek(0.2f)
        val before = snapshot()
        assertTrue(before.value > 0.01f && before.value < 0.2f)
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        val frames = mutableListOf(before)
        while (composeRule.mainClock.currentTime - before.clockMillis < MinimumCancelMillis - FrameMillis) {
            val frame = advanceFrame()
            frames += frame
            assertTrue("最短回弹时间前不能归并原端点: $frame trace=$frames", frame.current)
            assertFalse("最短回弹时间前不能归并原端点: $frame trace=$frames", frame.target)
            if (frame.clockMillis - before.clockMillis in 80L..112L) {
                assertTrue("回弹中段不能提前贴到端点: $frame trace=$frames", frame.value > 0.0001f)
            }
        }
        frames += advanceUntil("小进度取消必须完成回弹与状态恢复") { atRest(true) }
        val elapsed = frames.last().clockMillis - before.clockMillis
        assertTrue("取消时长 $elapsed 不得短于 $MinimumCancelMillis", elapsed >= MinimumCancelMillis)
        for ((previous, current) in frames.zipWithNext()) {
            assertTrue("回弹不能二次向外运动: $previous -> $current trace=$frames",
                current.value <= previous.value + Tolerance)
        }
        assertEquals(0f, progress.value, Tolerance)
        assertTrue(selected)
        assertTrue(backs.isEmpty())
    }

    @Test
    fun `zero displacement cancels immediately while positive displacement observes the duration floor`() {
        assertEquals(0, hostBackCancelDurationMillis(0f, 300_000_000L, MinimumCancelMillis))
        assertEquals(0, hostBackCancelDurationMillis(0.5f, 0L, MinimumCancelMillis))
        assertEquals(MinimumCancelMillis,
            hostBackCancelDurationMillis(0.01f, 300_000_000L, MinimumCancelMillis))
        assertEquals(225, hostBackCancelDurationMillis(0.75f, 300_000_000L, MinimumCancelMillis))
        setHost()
        startGesture()
        seek(0f)
        val before = snapshot()
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        val frames = advanceUntil("零位移取消只需完成状态收尾") { atRest(true) }
        val elapsed = composeRule.mainClock.currentTime - before.clockMillis
        assertTrue("零位移不应人为等待160ms: elapsed=$elapsed trace=$frames",
            elapsed < MinimumCancelMillis)
        assertTrue("零位移取消全程应停在原端点: $frames", frames.all { abs(it.value) <= Tolerance })
        assertTrue(backs.isEmpty())
    }

    private fun startGesture() {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(event(0f)) }
    }

    private fun seek(raw: Float) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(raw)) }
        val expected = FastOutLinearInEasing.transform(raw)
        advanceUntil("实际 Host 进度必须使用 easing raw=$raw expected=$expected") {
            transition.currentState && !transition.targetState && abs(progress.value - expected) <= Tolerance
        }
        assertEquals(expected, progress.value, Tolerance)
    }

    private fun atRest(state: Boolean): Boolean = transition.currentState == state &&
        transition.targetState == state && !transition.isRunning &&
        abs(progress.value - if (state) 0f else 1f) <= Tolerance

    private fun advanceFrame(): Frame {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return snapshot()
    }

    private fun advanceUntil(message: String, predicate: () -> Boolean): List<Frame> {
        val frames = mutableListOf<Frame>()
        val deadline = composeRule.mainClock.currentTime + 1500L
        while (!predicate() && composeRule.mainClock.currentTime < deadline) frames += advanceFrame()
        assertTrue("$message frame=${snapshot()} trace=$frames", predicate())
        return frames
    }

    private fun snapshot() = Frame(composeRule.mainClock.currentTime, progress.value,
        transition.currentState, transition.targetState)
    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher
    private fun event(raw: Float) = BackEventCompat(0f, 0f, raw, BackEventCompat.EDGE_LEFT)
    private data class Frame(val clockMillis: Long, val value: Float, val current: Boolean, val target: Boolean)

    private companion object {
        const val DurationMillis = 300
        const val MinimumCancelMillis = 160
        const val FrameMillis = 16
        const val Tolerance = 0.002f
    }
}
