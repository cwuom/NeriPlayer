package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.component.playback.PlaybackSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingCoverSourceBadgeRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val parentFlight = SharedCoverFlightState()
    private val artwork = ImageBitmap(16, 16)
    private var mounted by mutableStateOf(false)
    private var shown by mutableStateOf(true)
    private var animate by mutableStateOf(true)
    private var sourceType by mutableStateOf<PlaybackSourceType?>(PlaybackSourceType.LOCAL)

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalSharedCoverFlight provides parentFlight) {
                MaterialTheme(colorScheme = lightColorScheme(surface = Color.White, onSurface = Color.White)) {
                    Box(Modifier.size(320.dp, 160.dp).background(Color.Black).testTag(HostTag)) {
                        // 独立注册真实目标坐标，不用父层透明度替标签的门控制造假通过
                        Box(Modifier.size(1.dp).onGloballyPositioned { parentFlight.targetCoordinates = it })
                        if (mounted) {
                            NowPlayingCoverSourceBadge(
                                show = shown,
                                animate = animate,
                                sourceType = sourceType,
                                modifier = Modifier.align(Alignment.Center).testTag(BadgeTag)
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    @Test
    fun firstMountStartsAtZeroAndScalesNonlinearlyAroundItsCenter() {
        composeRule.runOnIdle { mounted = true }
        frame()
        composeRule.onNodeWithTag(BadgeTag).assertExists()
        val first = capture("first-mounted")
        advance(160L)
        val intermediate = capture("first-intermediate")
        advance(600L)
        val final = capture("first-final")
        assertEntry(first, intermediate, final)
    }

    @Test
    fun parentOwnershipDelaysEntryAndCancelledCollapseCanEnterAgain() {
        composeRule.runOnIdle {
            parentFlight.reportSource(Rect(0f, 0f, 16f, 16f), 0f, artwork)
            parentFlight.beginLinked(artwork)
            assertTrue("父层必须实际接管封面", parentFlight.hidesCovers)
            mounted = true
        }
        advance(800L)
        assertNull("遮挡期间不能提前显示标签", capture("parent-held").bounds)
        composeRule.runOnIdle { parentFlight.endLinked() }
        frame()
        val released = capture("parent-release")
        advance(160L)
        val intermediate = capture("parent-release-intermediate")
        advance(600L)
        val final = capture("parent-release-final")
        assertEntry(released, intermediate, final)

        composeRule.runOnIdle { parentFlight.beginLinked(artwork) }
        frame()
        assertNull("再次收起时标签立即隐藏", capture("collapse-start").bounds)
        composeRule.runOnIdle { parentFlight.endLinked() }
        frame()
        val cancelled = capture("collapse-cancelled")
        advance(160L)
        val cancelledIntermediate = capture("cancel-intermediate")
        advance(600L)
        val cancelledFinal = capture("cancel-final")
        assertEntry(cancelled, cancelledIntermediate, cancelledFinal)
    }

    @Test
    fun parentWithoutDecodedBitmapDelaysEntryUntilLinkedMorphEnds() {
        composeRule.runOnIdle {
            parentFlight.reportSource(Rect(0f, 0f, 16f, 16f), 0f, null)
            parentFlight.beginLinked(null)
            assertNull("首次打开时 mini 封面尚未解码", parentFlight.miniCoverBitmap)
            assertNull("父转场没有可接管的保留位图", parentFlight.linkedBitmap)
            assertTrue("无位图时父容器转场仍在进行", parentFlight.linked)
            assertFalse("没有位图时父层不会隐藏封面端点", parentFlight.hidesCovers)
            mounted = true
        }
        advance(800L)
        composeRule.runOnIdle {
            assertTrue("等待期间父容器转场必须保持进行", parentFlight.linked)
            assertFalse("等待期间仍没有可接管的封面", parentFlight.hidesCovers)
        }
        assertNull("无位图父转场期间不能提前播放标签入场", capture("parent-no-bitmap-held").bounds)
        composeRule.runOnIdle { parentFlight.endLinked() }
        frame()
        val released = capture("parent-no-bitmap-release")
        advance(160L)
        val intermediate = capture("parent-no-bitmap-intermediate")
        advance(600L)
        val final = capture("parent-no-bitmap-final")
        assertEntry(released, intermediate, final)
    }

    @Test
    fun staticPreviewIsImmediateAndHiddenOrUnknownSourcesDoNotDraw() {
        composeRule.runOnIdle {
            animate = false
            mounted = true
        }
        frame()
        val first = checkNotNull(capture("static-first").bounds)
        advance(160L)
        assertRect(first, checkNotNull(capture("static-later").bounds))
        composeRule.runOnIdle { shown = false }
        frame()
        assertNull("显式隐藏时不能绘制标签", capture("hidden").bounds)
        composeRule.runOnIdle {
            shown = true
            sourceType = null
        }
        frame()
        assertNull("来源未知时不能绘制标签", capture("unknown").bounds)
        composeRule.runOnIdle { sourceType = PlaybackSourceType.LOCAL }
        frame()
        // 目标先在组合帧提交，snap 在下一动画帧应用
        frame()
        assertRect(first, checkNotNull(capture("static-restored").bounds))
        composeRule.runOnIdle { assertFalse(parentFlight.hidesCovers) }
    }

    private fun assertEntry(first: BadgeFrame, intermediate: BadgeFrame, final: BadgeFrame) {
        assertNull("每次真正可见后的首帧必须从零开始: $first", first.bounds)
        val middleBounds = checkNotNull(intermediate.bounds) { "入场必须有可见中间尺寸: $intermediate" }
        val finalBounds = checkNotNull(final.bounds) { "入场必须完成: $final" }
        val ratio = middleBounds.width / finalBounds.width
        assertTrue("160ms 必须有非终点的非线性缩放，不能直接显示或改成线性: $intermediate / $final",
            ratio > 0.45f && ratio < 0.98f)
        assertEquals("缩放中心横向固定", finalBounds.center.x, middleBounds.center.x, 1.5f)
        assertEquals("缩放中心纵向固定", finalBounds.center.y, middleBounds.center.y, 1.5f)
        assertEquals("标签最终居于实际布局中心", 160f, finalBounds.center.x, 1.5f)
        assertEquals("标签最终居于实际布局中心", 80f, finalBounds.center.y, 1.5f)
    }

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.width, actual.width, 1f)
        assertEquals(expected.height, actual.height, 1f)
    }

    private fun capture(phase: String): BadgeFrame {
        val pixels = composeRule.onNodeWithTag(HostTag).captureToImage().toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.02f && color.green > 0.02f && color.blue > 0.02f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                    count++
                }
            }
        }
        val pixelsPerDp = pixels.width / 320f
        val bounds = if (right >= left && bottom >= top) {
            Rect(left / pixelsPerDp, top / pixelsPerDp, (right + 1) / pixelsPerDp, (bottom + 1) / pixelsPerDp)
        } else null
        return BadgeFrame(phase, composeRule.mainClock.currentTime, bounds, count).also {
            Log.i("CoverSourceBadgeRender", "frame=$it")
        }
    }

    private fun frame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) frame()
    }

    private data class BadgeFrame(
        val phase: String,
        val clockMillis: Long,
        val bounds: Rect?,
        val pixels: Int
    )

    private companion object {
        const val HostTag = "cover-source-badge-host"
        const val BadgeTag = "cover-source-badge"
    }
}
