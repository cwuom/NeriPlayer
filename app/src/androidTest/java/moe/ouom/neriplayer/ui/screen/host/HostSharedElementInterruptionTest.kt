package moe.ouom.neriplayer.ui.screen.host

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalSharedTransitionApi::class)
class HostSharedElementInterruptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var lyricsVisible by mutableStateOf(false)
    private var immersive by mutableStateOf(false)
    private var realisticGeometry by mutableStateOf(false)
    private lateinit var transition: Transition<Boolean>
    private var hostBoundsInRoot: Rect? = null
    private var coverBoundsInRoot: Rect? = null
    private var lyricsBoundsInRoot: Rect? = null
    private var headerScale = Float.NaN
    private var headerOffsetPx = Float.NaN

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            transition = rememberHostPredictiveBackTransition(
                targetState = lyricsVisible,
                backEnabled = lyricsVisible,
                backTargetState = false,
                onBack = { lyricsVisible = false },
                label = "shared_cover_interruption"
            )
            if (realisticGeometry) {
                FittedTestViewport(RealViewportWidthDp.dp, 914.dp) {
                    SharedCoverFixture(RealViewportWidthDp.dp, 914.dp)
                }
            } else {
                SharedCoverFixture(320.dp, 520.dp)
            }
        }
        advance(32L)
    }

    @Test
    fun reversingToCoverKeepsSharedBoundsContinuousAfterImmersiveChanges() {
        assertInterruptedReturnIsContinuous(lyricsAtRest = false)
    }

    @Test
    fun reversingToLyricsKeepsSharedBoundsContinuousAfterImmersiveChanges() {
        assertInterruptedReturnIsContinuous(lyricsAtRest = true)
    }

    @Composable
    private fun SharedCoverFixture(width: Dp, height: Dp) {
        SharedTransitionLayout(
            Modifier.size(width, height).background(Color.Black).testTag(HostTag)
                .onGloballyPositioned { hostBoundsInRoot = it.boundsInRoot() }
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    fadeIn(tween(PageFadeMillis, easing = LinearEasing)) togetherWith
                        fadeOut(tween(PageFadeMillis, easing = LinearEasing))
                }
            ) { showLyrics ->
                Box(Modifier.fillMaxSize()) {
                    val coverSize: Dp
                    val coverOffset: Float
                    if (realisticGeometry && showLyrics) {
                        var entered by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { entered = true }
                        val scale by animateFloatAsState(
                            targetValue = if (entered) 0.6f else 1f,
                            animationSpec = spring(dampingRatio = 0.8f),
                            label = "fixture_header_scale"
                        )
                        val offset by animateFloatAsState(
                            targetValue = if (entered) -8f else 0f,
                            animationSpec = spring(dampingRatio = 0.8f),
                            label = "fixture_header_offset"
                        )
                        headerScale = scale
                        headerOffsetPx = offset
                        coverSize = (64 * scale).dp
                        coverOffset = offset
                    } else {
                        coverSize = if (realisticGeometry) {
                            if (immersive) 371.dp else 222.dp
                        } else {
                            if (showLyrics) 48.dp else if (immersive) 220.dp else 180.dp
                        }
                        coverOffset = 0f
                    }
                    DisposableEffect(showLyrics) {
                        onDispose {
                            if (showLyrics) lyricsBoundsInRoot = null else coverBoundsInRoot = null
                        }
                    }
                    Box(
                        Modifier
                            .offset(
                                x = if (showLyrics) {
                                    if (realisticGeometry) 76.dp else 20.dp
                                } else (width - coverSize) / 2,
                                y = if (realisticGeometry) {
                                    if (showLyrics) 70.dp else if (immersive) 132.dp else 125.dp
                                } else if (showLyrics) 20.dp else if (immersive) 80.dp else 120.dp
                            )
                            .size(coverSize)
                            .sharedElement(
                                rememberSharedContentState("cover"),
                                animatedVisibilityScope = this@AnimatedContent
                            )
                            .then(
                                if (realisticGeometry) Modifier
                                    .graphicsLayer { translationY = coverOffset }
                                    .clip(RoundedCornerShape(if (showLyrics) 10.dp else 24.dp))
                                else Modifier
                            )
                            .onGloballyPositioned {
                                if (showLyrics) lyricsBoundsInRoot = it.boundsInRoot()
                                else coverBoundsInRoot = it.boundsInRoot()
                            }
                            .background(Color.Red)
                    )
                }
            }
        }
    }

    @Test
    fun dynamicHeaderKeepsSharedBoundsContinuousDuringRapidReturnAfterRealCoverSizes() {
        val results = mutableListOf<DynamicCaseResult>()
        Log.i(
            "HostSharedInterruption",
            "dynamic fixture uses 8ms scheduler substeps; Compose animation frames remain 16ms"
        )
        for (immersiveMode in listOf(true, false, true)) {
            for (closeMillis in listOf(96L, 160L, 224L)) {
                composeRule.runOnIdle {
                    realisticGeometry = true
                    immersive = immersiveMode
                    lyricsVisible = true
                }
                advance(1_000L)
                val resting = captureDynamicFrame("rest")
                composeRule.runOnIdle { lyricsVisible = false }
                val closing = captureDynamicFrames(closeMillis, "close")
                composeRule.runOnIdle {
                    assertEquals("必须先确认真实关闭片段，不能把未提交输入当作反向", true, transition.currentState)
                    assertEquals(false, transition.targetState)
                }
                assertTrue(
                    "关闭必须确实改变共享封面尺寸: $closing",
                    abs(closing.last().bounds.width - resting.bounds.width) > 16f
                )
                composeRule.runOnIdle { lyricsVisible = true }
                val returning = listOf(closing.last()) + captureDynamicFrames(1_024L, "return")
                val maximumStep = returning.zipWithNext().maxOf { (previous, current) ->
                    boundsStep(previous.bounds, current.bounds)
                }
                val maximumClosingStep = (listOf(resting) + closing).zipWithNext().maxOf { (previous, current) ->
                    boundsStep(previous.bounds, current.bounds)
                }
                val widthChanges = returning.filterIndexed { index, frame ->
                    index == 0 || frame.bounds.width != returning[index - 1].bounds.width
                }
                val widthPulses = widthChanges.windowed(3).filter { (previous, current, next) ->
                    previous.bounds.width - current.bounds.width > DynamicGeometryToleranceDp &&
                        next.bounds.width - current.bounds.width > DynamicGeometryToleranceDp
                }
                val maximumLayoutError = (listOf(resting) + closing + returning).maxOf { frame ->
                    renderedLayoutError(frame)
                }
                val final = returning.last()
                assertEquals(resting.bounds.left, final.bounds.left, 2f)
                assertEquals(resting.bounds.top, final.bounds.top, 2f)
                assertEquals(resting.bounds.width, final.bounds.width, 2f)
                assertEquals(resting.bounds.height, final.bounds.height, 2f)
                assertEquals(true, final.currentState)
                assertEquals(true, final.targetState)
                results += DynamicCaseResult(
                    immersiveMode, closeMillis, maximumClosingStep, maximumStep,
                    widthPulses, maximumLayoutError
                )
                Log.i("HostSharedInterruption", "dynamic result=${results.last()}")
            }
        }
        assertTrue(
            "快速反向不能出现超过2dp的先缩后扩单帧回退，像素边界也必须匹配参与转场的实际布局: $results",
            results.all {
                it.widthPulses.isEmpty() && it.maximumLayoutErrorDp <= DynamicGeometryToleranceDp
            }
        )
    }

    private fun renderedLayoutError(frame: DynamicFrame): Float {
        val host = checkNotNull(frame.hostBoundsInRootPx)
        val pixelsPerDp = host.width / RealViewportWidthDp
        val participatingBounds = listOfNotNull(frame.coverBoundsInRootPx, frame.lyricsBoundsInRootPx)
        check(participatingBounds.isNotEmpty())
        return participatingBounds.minOf { bounds ->
            val localBounds = Rect(
                (bounds.left - host.left) / pixelsPerDp,
                (bounds.top - host.top) / pixelsPerDp,
                (bounds.right - host.left) / pixelsPerDp,
                (bounds.bottom - host.top) / pixelsPerDp
            )
            boundsStep(localBounds, frame.bounds)
        }
    }

    private fun captureDynamicFrames(milliseconds: Long, phase: String): List<DynamicFrame> {
        val frames = mutableListOf<DynamicFrame>()
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) {
            composeRule.mainClock.advanceTimeBy(8L, ignoreFrameDuration = true)
            composeRule.waitForIdle()
            frames += captureDynamicFrame(phase)
        }
        return frames
    }

    private fun captureDynamicFrame(phase: String): DynamicFrame {
        val frame = DynamicFrame(
            clockMillis = composeRule.mainClock.currentTime,
            bounds = renderedCoverBounds(),
            currentState = transition.currentState,
            targetState = transition.targetState,
            hostBoundsInRootPx = hostBoundsInRoot,
            coverBoundsInRootPx = coverBoundsInRoot,
            lyricsBoundsInRootPx = lyricsBoundsInRoot,
            headerScale = headerScale,
            headerOffsetPx = headerOffsetPx
        )
        Log.i("HostSharedInterruption", "dynamic phase=$phase immersive=$immersive frame=$frame")
        return frame
    }

    private fun boundsStep(previous: Rect, current: Rect): Float = maxOf(
        abs(current.left - previous.left),
        abs(current.top - previous.top),
        abs(current.width - previous.width),
        abs(current.height - previous.height)
    )

    private fun assertInterruptedReturnIsContinuous(lyricsAtRest: Boolean) {
        for (immersiveMode in listOf(false, true, false)) {
            composeRule.runOnIdle {
                immersive = immersiveMode
                lyricsVisible = lyricsAtRest
            }
            advance(1_000L)
            val restingCover = renderedCoverBounds()

            composeRule.runOnIdle { lyricsVisible = !lyricsAtRest }
            advance(160L)
            val beforeReverse = renderedCoverBounds()
            composeRule.runOnIdle {
                assertEquals("反向输入发生在上一转场尚未完成时", lyricsAtRest, transition.currentState)
                assertEquals(!lyricsAtRest, transition.targetState)
                lyricsVisible = lyricsAtRest
            }
            assertTrue("正向共享封面必须确实改变尺寸", abs(beforeReverse.width - restingCover.width) > 16f)

            val samples = mutableListOf(beforeReverse)
            repeat(64) {
                advance(16L)
                samples += renderedCoverBounds()
            }
            val maximumStep = samples.zipWithNext().maxOf { (previous, current) ->
                maxOf(
                    abs(current.left - previous.left),
                    abs(current.top - previous.top),
                    abs(current.width - previous.width),
                    abs(current.height - previous.height)
                )
            }
            Log.i(
                "HostSharedInterruption",
                "lyricsAtRest=$lyricsAtRest immersive=$immersiveMode maxStepDp=$maximumStep bounds=$samples"
            )
            assertTrue(
                "快速反向的共享封面不能在终点瞬间跳变: lyricsAtRest=$lyricsAtRest immersive=$immersiveMode " +
                    "maxStepDp=$maximumStep bounds=$samples",
                maximumStep <= MaximumSizeStepDp
            )
            val finalCover = samples.last()
            assertEquals("共享封面最终必须恢复原横向位置", restingCover.left, finalCover.left, 2f)
            assertEquals("共享封面最终必须恢复原尺寸", restingCover.width, finalCover.width, 2f)
            assertEquals("共享封面最终必须恢复原位置", restingCover.top, finalCover.top, 2f)
            assertEquals("共享封面最终必须恢复原高度", restingCover.height, finalCover.height, 2f)
            composeRule.runOnIdle {
                assertEquals(lyricsAtRest, transition.currentState)
                assertEquals(lyricsAtRest, transition.targetState)
            }
        }
    }

    private fun renderedCoverBounds(): Rect {
        val pixels = composeRule.onNodeWithTag(HostTag).captureToImage().toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height step 2) {
            for (x in 0 until pixels.width step 2) {
                val color = pixels[x, y]
                if (color.red > 0.2f && color.red > color.green * 2f && color.red > color.blue * 2f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("共享封面不能在反向过程中消失", right >= left && bottom >= top)
        val pixelsPerDp = pixels.width / (if (realisticGeometry) RealViewportWidthDp else 320f)
        return Rect(
            left / pixelsPerDp,
            top / pixelsPerDp,
            (right + 2) / pixelsPerDp,
            (bottom + 2) / pixelsPerDp
        )
    }

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private companion object {
        const val HostTag = "sharedCoverInterruptionHost"
        const val PageFadeMillis = 300
        const val MaximumSizeStepDp = 24f
        const val RealViewportWidthDp = 411f
        const val DynamicGeometryToleranceDp = 2f
    }

    private data class DynamicFrame(
        val clockMillis: Long,
        val bounds: Rect,
        val currentState: Boolean,
        val targetState: Boolean,
        val hostBoundsInRootPx: Rect?,
        val coverBoundsInRootPx: Rect?,
        val lyricsBoundsInRootPx: Rect?,
        val headerScale: Float,
        val headerOffsetPx: Float
    )

    private data class DynamicCaseResult(
        val immersive: Boolean,
        val closeMillis: Long,
        val maximumClosingStepDp: Float,
        val maximumReturnStepDp: Float,
        val widthPulses: List<List<DynamicFrame>>,
        val maximumLayoutErrorDp: Float
    )
}
