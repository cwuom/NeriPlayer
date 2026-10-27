package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.screen.host.rememberHostPredictiveBackTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class NowPlayingLyricsCoverFlightTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selected by mutableStateOf(false)
    private var showHeader by mutableStateOf(true)
    private var headerCollapsed by mutableStateOf(false)
    private var rootGeneration by mutableStateOf(0)
    private var coverSize by mutableFloatStateOf(320f)
    private lateinit var transition: Transition<Boolean>
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private lateinit var headerScale: State<Float>
    private lateinit var headerOffset: State<Float>
    private val backs = mutableListOf<Boolean>()

    private fun setHost(initialLyrics: Boolean = false) {
        selected = initialLyrics
        composeRule.setContent {
            CompositionLocalProvider(
                LocalPredictiveBackPreviewEnabled provides true,
                LocalDensity provides Density(1f)
            ) {
                transition = rememberHostPredictiveBackTransition(
                    targetState = selected,
                    backEnabled = selected,
                    backTargetState = false,
                    onBack = { seeked -> backs += seeked; selected = false },
                    label = "single_cover_host"
                )
                transition.animateFloat(
                    transitionSpec = { tween(300, easing = LinearEasing) },
                    label = "fixture_page_fade"
                ) { if (it) 1f else 0f }
                flight = rememberNowPlayingLyricsCoverFlight(transition)
                headerScale = animateFloatAsState(
                    if (headerCollapsed) 0.6f else 1f,
                    spring(dampingRatio = 0.8f),
                    label = "fixture_header_scale"
                )
                headerOffset = animateFloatAsState(
                    if (headerCollapsed) -8f else 0f,
                    spring(dampingRatio = 0.8f),
                    label = "fixture_header_offset"
                )
                key(rootGeneration) {
                    Box(Modifier.requiredSize(400.dp, 800.dp)) {
                        Box(Modifier.offset(24.dp, 200.dp).size(coverSize.dp)
                            .nowPlayingCoverFlightEndpoint(flight))
                        if (showHeader) {
                            Row(
                                Modifier.offset(16.dp, 32.dp).width(368.dp).height(64.dp)
                                    .nowPlayingLyricsCoverFlightRow(flight),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.width(64.dp))
                                Box(Modifier.size((64f * headerScale.value).dp)
                                    .nowPlayingLyricsCoverFlightEndpoint(
                                        flight, headerScale, headerOffset
                                    )
                                    .graphicsLayer { translationY = headerOffset.value })
                            }
                        }
                        NowPlayingLyricsCoverFlightLayer(flight) { modifier ->
                            Box(modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `initial settled layouts snap to known geometry and fallback never starts from zero`() {
        showHeader = false
        setHost()
        assertRect(Rect(24f, 200f, 344f, 520f), requireNotNull(flight.bounds))
        assertFalse(flight.hidesEndpoints)

        composeRule.runOnIdle { selected = true }
        advanceUntil("目标布局未出现时也要由已知源接管") { transition.targetState }
        assertTrue(flight.hidesEndpoints)
        assertTrue(flight.shouldDraw)
        assertRect(Rect(24f, 200f, 344f, 520f), requireNotNull(flight.bounds))
        composeRule.runOnIdle { showHeader = true }
        advanceUntil("迟到目标必须沿同一动画完成") { atRest(true) }
        assertRect(Rect(80f, 32f, 144f, 96f), requireNotNull(flight.bounds))
        assertFalse(flight.hidesEndpoints)
    }

    @Test
    fun `predictive seeking uses original shared spring and cancellation has no second dip`() {
        setHost(initialLyrics = true)
        val source = flight.targetBounds(true)
        val target = flight.targetBounds(false)
        val reference = TargetBasedAnimation(
            animationSpec = spring<Rect>(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = Rect.VisibilityThreshold
            ),
            typeConverter = Rect.VectorConverter,
            initialValue = source,
            targetValue = target
        )
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(event(0f)) }
        for (fraction in listOf(0.2f, 0.65f, 0.35f)) {
            composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(event(fraction)) }
            val expected = reference.getValueFromNanos((reference.durationNanos * fraction).toLong())
            advanceUntil("手势必须直接拖动唯一封面 $fraction") {
                transition.currentState != transition.targetState &&
                    rectDistance(expected, flight.bounds) <= 1f
            }
            assertRect(expected, requireNotNull(flight.bounds), 1f)
            assertTrue(flight.shouldDraw)
        }
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        val rebound = advanceUntil("取消必须返回原头部几何并释放绘制接管") { atRest(true) }
        for ((previous, current) in rebound.zipWithNext()) {
            assertTrue("取消回弹不能二次扩张: $previous -> $current trace=$rebound",
                current.width <= previous.width + 1f)
        }
        assertRect(source, requireNotNull(flight.bounds))
        assertTrue(backs.isEmpty())
        assertFalse(flight.hidesEndpoints)
    }

    @Test
    fun `committing a seek continues from current bounds until the cover endpoint is reached`() {
        setHost(initialLyrics = true)
        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(0.35f))
        }
        advanceUntil("提交前必须处于真实中间几何") {
            transition.currentState != transition.targetState &&
                requireNotNull(flight.bounds).width > 100f
        }
        assertTrue(flight.shouldDraw)
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        val committed = advanceUntil("提交必须完成封面目标") { atRest(false) && backs == listOf(true) }
        for ((previous, current) in committed.zipWithNext()) {
            assertTrue("提交不能先缩回头部: $previous -> $current trace=$committed",
                current.width >= previous.width - 1f)
        }
        assertRect(flight.targetBounds(false), requireNotNull(flight.bounds))
        assertEquals(listOf(true), backs)
        assertFalse(flight.hidesEndpoints)
    }

    @Test
    fun `ordinary rapid reversal retains one continuous rect instead of selecting another endpoint layer`() {
        setHost()
        repeat(3) {
            composeRule.runOnIdle { selected = true }
            advanceUntil("进入歌词时先确认中间几何") {
                transition.targetState && requireNotNull(flight.bounds).width < 280f
            }
            val before = requireNotNull(flight.bounds)
            composeRule.runOnIdle { selected = false }
            val trace = advanceUntil("普通反向必须完成封面目标") { atRest(false) }
            val completeTrace = listOf(before) + trace
            for ((previous, current) in completeTrace.zipWithNext()) {
                assertTrue("16ms 采样不能在端点之间瞬切: $previous -> $current trace=$completeTrace",
                    rectDistance(previous, current) < 80f)
            }
            assertRect(flight.targetBounds(false), requireNotNull(flight.bounds))
        }
        assertTrue(backs.isEmpty())
    }

    @Test
    fun `header target reads its scale and pixel offset states without another positioning callback`() {
        setHost()
        val initial = flight.targetBounds(true)
        assertRect(Rect(80f, 32f, 144f, 96f), initial)
        composeRule.runOnIdle { headerCollapsed = true }
        advanceUntil("保留独立头部 spring 的动态目标") {
            abs(headerScale.value - 0.6f) < 0.001f && abs(headerOffset.value + 8f) < 0.001f
        }
        val header = flight.targetBounds(true)
        assertEquals(38.4f, header.width, 0.1f)
        assertEquals(56f, header.center.y, 0.1f)
        assertEquals(initial.left, header.left, 0.1f)
        assertRect(flight.targetBounds(false), requireNotNull(flight.bounds))
        assertFalse(flight.shouldDraw)
        assertFalse(flight.hidesEndpoints)
    }

    @Test
    fun `settled geometry updates never start a flight and the next navigation uses the visible source`() {
        setHost()
        composeRule.runOnIdle { coverSize = 280f; headerCollapsed = true }
        repeat(36) {
            advanceFrame()
            assertFalse("静止布局和头部 spring 不应接管封面", flight.shouldDraw)
            assertFalse(flight.hidesEndpoints)
        }
        val visibleSource = Rect(24f, 200f, 304f, 480f)
        assertRect(visibleSource, requireNotNull(flight.bounds))
        composeRule.runOnIdle { selected = true }
        advanceUntil("下一次导航必须从当前可见尺寸开始") {
            transition.targetState && flight.shouldDraw
        }
        assertTrue("不能从旧 320px 缓存回跳: ${flight.bounds}",
            requireNotNull(flight.bounds).width <= visibleSource.width + 1f)
        advanceUntil("进入歌词必须完成") { atRest(true) }

        composeRule.runOnIdle { headerCollapsed = false }
        repeat(36) {
            advanceFrame()
            assertFalse("静止歌词页保留自己的头部 spring", flight.shouldDraw)
            assertFalse(flight.hidesEndpoints)
        }
        assertRect(flight.targetBounds(true), requireNotNull(flight.bounds))
    }

    @Test
    fun `detached header retains last valid geometry and replacement root adopts current coordinates`() {
        setHost()
        val header = flight.targetBounds(true)
        composeRule.runOnIdle { showHeader = false }
        advanceFrame()
        assertRect(header, flight.targetBounds(true))
        composeRule.runOnIdle {
            rootGeneration += 1
            coverSize = 280f
        }
        advanceUntil("配置重建后必须采用新根坐标且不访问失效端点") {
            flight.targetBounds(false).width == 280f && flight.bounds?.width == 280f
        }
        assertRect(header, flight.targetBounds(true))
        composeRule.runOnIdle { showHeader = true; headerCollapsed = true }
        advanceUntil("新的头部注册不能被旧端点清理掉") {
            abs(flight.targetBounds(true).width - 38.4f) < 0.1f
        }
        assertNotNull(flight.bounds)
        assertFalse(flight.hidesEndpoints)
    }

    private fun atRest(lyrics: Boolean): Boolean = transition.currentState == lyrics &&
        transition.targetState == lyrics && !transition.isRunning &&
        rectDistance(flight.targetBounds(lyrics), flight.bounds) < 0.1f

    private fun advanceFrame(): Rect {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        return requireNotNull(flight.bounds)
    }

    private fun advanceUntil(message: String, predicate: () -> Boolean): List<Rect> {
        val trace = mutableListOf<Rect>()
        val deadline = composeRule.mainClock.currentTime + 1500L
        while (!predicate() && composeRule.mainClock.currentTime < deadline) trace += advanceFrame()
        assertTrue("$message current=${transition.currentState} target=${transition.targetState} " +
            "running=${transition.isRunning} rect=${flight.bounds} trace=$trace",
            predicate())
        return trace
    }

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher
    private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)
    private fun rectDistance(first: Rect, second: Rect?): Float = if (second == null) Float.POSITIVE_INFINITY
        else maxOf(abs(first.left - second.left), abs(first.top - second.top),
            abs(first.right - second.right), abs(first.bottom - second.bottom))

    private fun assertRect(expected: Rect, actual: Rect, tolerance: Float = 0.1f) {
        assertTrue("expected=$expected actual=$actual tolerance=$tolerance",
            rectDistance(expected, actual) <= tolerance)
    }
}
