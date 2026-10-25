package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.screen.host.rememberHostPredictiveBackTransition
import moe.ouom.neriplayer.ui.theme.NeriDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingCoverParentTargetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val parent = SharedCoverFlightState()
    private val bitmap = ImageBitmap(2, 2)
    private val scale = mutableFloatStateOf(0.6f)
    private val offset = mutableFloatStateOf(-8f)
    private var selected by mutableStateOf(true)
    private var mounted by mutableStateOf(true)
    private lateinit var transition: Transition<Boolean>
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private lateinit var host: LayoutCoordinates
    private lateinit var slot: LayoutCoordinates
    private var cleanCover = false
    private var headerRowHeight = Float.NaN
    private var rootLive = false
    private var rootDisposedAtMillis: Long? = null
    private val lifecycleTrace = mutableListOf<String>()

    private fun setHost() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f), LocalSharedCoverFlight provides parent
            ) {
                cleanCover = NeriDesign.isClean
                transition = rememberHostPredictiveBackTransition(
                    selected, selected, false, { selected = false }, "parent_target_host"
                )
                flight = rememberNowPlayingLyricsCoverFlight(transition)
                Box(Modifier.requiredSize(411.dp, 914.dp).onGloballyPositioned { host = it }) {
                    if (mounted) {
                        DisposableEffect(Unit) {
                            rootLive = true
                            onDispose {
                                rootLive = false
                                rootDisposedAtMillis = composeRule.mainClock.currentTime
                                val attached = if (::slot.isInitialized) slot.isAttached else null
                                lifecycleTrace += "onDispose clock=$rootDisposedAtMillis slotAttached=$attached"
                            }
                        }
                        transition.AnimatedContent(
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                fadeIn(tween(300, easing = LinearEasing)) togetherWith
                                    fadeOut(tween(300, easing = LinearEasing))
                            }
                        ) { lyrics ->
                            Box(Modifier.fillMaxSize()) {
                                if (lyrics) {
                                    Row(Modifier.offset(20.dp, 56.dp).width(371.dp).height(56.dp)
                                        .nowPlayingLyricsCoverFlightRow(flight)
                                        .onGloballyPositioned { headerRowHeight = it.size.height.toFloat() },
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.width(56.dp))
                                        Box(Modifier.size((64f * scale.floatValue).dp)
                                            .nowPlayingLyricsCoverFlightEndpoint(flight, scale, offset)
                                            .graphicsLayer { translationY = offset.floatValue })
                                    }
                                } else {
                                    Box(Modifier.offset(20.dp, 132.dp).size(371.dp)
                                        .nowPlayingCoverFlightEndpoint(flight))
                                }
                            }
                        }
                        NowPlayingLyricsCoverFlightLayer(flight) { modifier ->
                            Box(modifier.onGloballyPositioned { slot = it })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { parent.reportSource(Rect(16f, 840f, 56f, 880f), 10f, bitmap) }
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `actual idle header provider supports linked ownership and root disposal releases its registration`() {
        setHost()
        val expected = Rect(76f, 56.8f, 114.4f, 95.2f)
        assertNull("没有通过大封面端点注册 legacy 目标", parent.targetCoordinates)
        assertFalse(flight.shouldDraw)
        assertEquals("真实页面的 Row 高度不能继承 AnimatedContent 的 914px 最小高度",
            56f, headerRowHeight, 0f)
        val header = requireNotNull(parent.targetFrameIn(host))
        assertRect(expected, header.bounds)
        assertEquals(10f, header.cornerPx, 0.01f)
        assertFalse(header.continuousCorners)

        composeRule.runOnIdle { parent.beginLinked(bitmap) }
        assertTrue(parent.hidesCovers)
        assertTrue(flight.hidesEndpoints)
        assertRect(expected, requireNotNull(parent.targetFrameIn(host)).bounds)
        assertTrue("卸载前真实根必须已经提交", rootLive)
        val unmountClock = composeRule.mainClock.currentTime
        composeRule.runOnIdle { mounted = false }
        var frames = 0
        while (rootLive && frames < 8) {
            advanceFrame()
            frames += 1
            lifecycleTrace += "afterFrame clock=${composeRule.mainClock.currentTime} rootLive=$rootLive " +
                "slotAttached=${slot.isAttached} target=${parent.targetFrameIn(host)}"
        }
        println("parent target lifecycle unmount=$unmountClock disposed=$rootDisposedAtMillis trace=$lifecycleTrace")
        assertFalse("根卸载必须在8帧128ms内真正提交: $lifecycleTrace", rootLive)
        assertNull("根卸载必须清除真实 provider", parent.targetFrameIn(host))
        assertFalse(parent.hidesCovers)

        composeRule.runOnIdle { mounted = true }
        advanceFrame()
        assertTrue("新根重新注册后可继续交接", parent.hidesCovers)
        assertRect(expected, requireNotNull(parent.targetFrameIn(host)).bounds)
        composeRule.runOnIdle { parent.endLinked() }
    }

    @Test
    fun `actual navigating provider hands the measured slot to linked and material parents at the same clock`() {
        setHost()
        composeRule.runOnIdle { selected = false }
        repeat(10) { advanceFrame() }
        assertTrue(transition.currentState)
        assertFalse(transition.targetState)
        assertTrue(flight.shouldDraw)
        val visible = host.localBoundingBoxOf(slot, clipBounds = false)
        assertTrue("交接必须在有效中间尺寸而非终点", visible.width > 80f && visible.width < 350f)
        val before = requireNotNull(parent.targetFrameIn(host))
        assertRect(visible, before.bounds, 1f)
        assertTrue("中间交接不能瞬贴任一端点圆角", before.cornerPx > 10f && before.cornerPx < 24f)
        assertEquals(flight.cornerPx, before.cornerPx, 0.01f)
        assertEquals(cleanCover, before.continuousCorners)
        assertEquals(flight.cornerSmoothing(visible.width, visible.height), before.cornerSmoothing, 0.001f)
        val clock = composeRule.mainClock.currentTime
        composeRule.runOnIdle {
            runBlocking { parent.progress.snapTo(1f) }
            parent.beginLinked(bitmap)
        }
        composeRule.waitForIdle()
        assertEquals(clock, composeRule.mainClock.currentTime)
        assertTrue(parent.hidesCovers)
        assertFalse(flight.shouldDraw)
        assertTrue(flight.hidesEndpoints)
        assertRect(before.bounds, requireNotNull(parent.targetFrameIn(host)).bounds)
        val actualWindowSlot = Rect(slot.positionInWindow(),
            Size(slot.size.width.toFloat(), slot.size.height.toFloat()))
        val materialFrame = requireNotNull(parent.currentFrame())
        assertRect(actualWindowSlot, materialFrame.bounds, 1f)
        assertEquals(before.cornerPx, materialFrame.cornerPx, 0.01f)
        assertEquals(before.continuousCorners, materialFrame.continuousCorners)
        composeRule.runOnIdle { parent.endLinked() }
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun assertRect(expected: Rect, actual: Rect, tolerance: Float = 0.1f) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }
}
