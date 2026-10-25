package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Transition
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.animation.CoverFlightFrame
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.screen.host.rememberHostPredictiveBackTransition
import moe.ouom.neriplayer.ui.theme.CleanDesignTokens
import moe.ouom.neriplayer.ui.theme.LocalNeriDesign
import moe.ouom.neriplayer.ui.theme.MaterialDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingLyricsCoverCornerContinuityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val parent = SharedCoverFlightState()
    private val scale = mutableFloatStateOf(0.6f)
    private val offset = mutableFloatStateOf(-8f)
    private var selected by mutableStateOf(false)
    private lateinit var transition: Transition<Boolean>
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private lateinit var host: LayoutCoordinates
    private lateinit var slot: LayoutCoordinates

    private fun setHost(clean: Boolean, predictiveBackPreviewActive: Boolean = false) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalNeriDesign provides if (clean) CleanDesignTokens else MaterialDesignTokens,
                LocalSharedCoverFlight provides parent
            ) {
                transition = rememberHostPredictiveBackTransition(
                    selected, selected, false, { selected = false }, "child_corner_continuity"
                )
                flight = rememberNowPlayingLyricsCoverFlight(transition, predictiveBackPreviewActive)
                Box(Modifier.requiredSize(411.dp, 914.dp).onGloballyPositioned { host = it }) {
                    Box(Modifier.offset(20.dp, 132.dp).size(371.dp)
                        .nowPlayingCoverFlightEndpoint(flight))
                    Row(Modifier.offset(20.dp, 56.dp).width(371.dp).height(56.dp)
                        .nowPlayingLyricsCoverFlightRow(flight),
                        verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(56.dp))
                        Box(Modifier.size(38.4.dp)
                            .nowPlayingLyricsCoverFlightEndpoint(flight, scale, offset))
                    }
                    NowPlayingLyricsCoverFlightLayer(flight) { modifier ->
                        Box(modifier.fillMaxSize().onGloballyPositioned { slot = it })
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun materialReversalKeepsTheCurrentSmallArtworkRadiusInsteadOfApplyingTheLargeEndpoint() {
        assertRadiusContinuity(clean = false)
    }

    @Test
    fun cleanReversalKeepsTheCurrentSmallArtworkRadiusInsteadOfApplyingTheLargeEndpoint() {
        assertRadiusContinuity(clean = true)
    }

    @Test
    fun cleanReversalKeepsTheCurrentCornerCurveInsteadOfSwitchingToTheTargetCurve() {
        assertCurveContinuity(predictiveBackPreviewActive = false)
    }

    @Test
    fun predictivePreviewReversalKeepsTheCurrentSmallArtworkRadius() {
        assertRadiusContinuity(clean = true, predictiveBackPreviewActive = true)
    }

    @Test
    fun predictivePreviewReversalKeepsTheCurrentCornerCurve() {
        assertCurveContinuity(predictiveBackPreviewActive = true)
    }

    private fun assertCurveContinuity(predictiveBackPreviewActive: Boolean) {
        setHost(clean = true, predictiveBackPreviewActive = predictiveBackPreviewActive)
        val before = reachSmallIntermediateArtwork()
        composeRule.runOnIdle { selected = false }
        advanceFrame()
        val after = currentFrame()

        assertTrue("反向首帧不能跳到目标角型 before=$before after=$after",
            abs(after.cornerSmoothing - before.cornerSmoothing) <= 0.1f)
        assertMeasuredFrame(after)
        settleAtCover()
        assertEquals(1f, currentFrame().cornerSmoothing, 0.001f)
    }

    private fun assertRadiusContinuity(clean: Boolean, predictiveBackPreviewActive: Boolean = false) {
        setHost(clean, predictiveBackPreviewActive)
        val before = reachSmallIntermediateArtwork()
        composeRule.runOnIdle { selected = false }
        advanceFrame()
        val after = currentFrame()

        assertTrue("反向首帧不能把仍很小的封面套上 24px 圆角 before=$before after=$after",
            abs(after.cornerPx - before.cornerPx) <= 1.5f)
        assertTrue("圆角检查必须发生在同一连续几何 before=$before after=$after",
            rectDistance(before.bounds, after.bounds) < 50f)
        assertMeasuredFrame(after)
        settleAtCover()
        assertEquals(24f, currentFrame().cornerPx, 0.01f)
        assertEquals(if (clean) 1f else 0f, currentFrame().cornerSmoothing, 0.001f)
    }

    private fun reachSmallIntermediateArtwork(): CoverFlightFrame {
        composeRule.runOnIdle { selected = true }
        advanceUntil("先进入与真机圆形脉冲相同的小封面中间区间") {
            transition.targetState && flight.shouldDraw && currentFrame().bounds.width in 75f..115f
        }
        return currentFrame().also(::assertMeasuredFrame)
    }

    private fun settleAtCover() {
        advanceUntil("反向后仍须完成原封面端点") {
            !transition.currentState && !transition.targetState && !transition.isRunning
        }
        assertEquals(371f, currentFrame().bounds.width, 0.1f)
    }

    private fun currentFrame(): CoverFlightFrame = requireNotNull(parent.targetFrameIn(host))

    private fun assertMeasuredFrame(frame: CoverFlightFrame) {
        val visible = host.localBoundingBoxOf(slot, clipBounds = false)
        assertTrue("父级必须取得同一帧的实际子级绘制位置 frame=$frame visible=$visible",
            rectDistance(visible, frame.bounds) <= 1f)
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun advanceUntil(message: String, condition: () -> Boolean) {
        val deadline = composeRule.mainClock.currentTime + 2_000L
        while (!condition() && composeRule.mainClock.currentTime < deadline) advanceFrame()
        assertTrue("$message frame=${currentFrame()} current=${transition.currentState} " +
            "target=${transition.targetState} running=${transition.isRunning}", condition())
    }

    private fun rectDistance(first: Rect, second: Rect): Float = maxOf(
        abs(first.left - second.left), abs(first.top - second.top),
        abs(first.right - second.right), abs(first.bottom - second.bottom)
    )
}
