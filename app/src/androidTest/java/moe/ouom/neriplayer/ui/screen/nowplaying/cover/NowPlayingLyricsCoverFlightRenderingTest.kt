package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.sqrt
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.host.rememberHostPredictiveBackTransition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingLyricsCoverFlightRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var lyricsVisible by mutableStateOf(false)
    private var immersive by mutableStateOf(false)
    private lateinit var transition: Transition<Boolean>
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private var hostBoundsInRoot: Rect? = null
    private var slotBoundsInRoot: Rect? = null
    private var coverBoundsInRoot: Rect? = null
    private var lyricsBoundsInRoot: Rect? = null
    private var headerScale = Float.NaN
    private var headerOffsetPx = Float.NaN
    private var diagnosticName = "unstarted"
    private val diagnostics = mutableListOf<FrameSample>()

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        // 测试时钟每帧 16ms，只检查转场逻辑和像素，不代表真实 120Hz 覆盖
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FittedTestViewport(ViewportWidthDp.dp, ViewportHeightDp.dp) {
                transition = rememberHostPredictiveBackTransition(
                    targetState = lyricsVisible,
                    backEnabled = lyricsVisible,
                    backTargetState = false,
                    onBack = { lyricsVisible = false },
                    label = "cover_flight_rendering"
                )
                flight = rememberNowPlayingLyricsCoverFlight(transition)
                FlightFixture()
            }
        }
        advance(1_024L)
    }

    @Test
    fun reversingToLyricsKeepsOneArtworkAtItsActualLayoutAcrossImmersiveChanges() {
        diagnosticName = "reverse-to-lyrics"
        assertInterruptedReturn(lyricsAtRest = true)
    }

    @Test
    fun reversingToCoverKeepsIntermediateFramesAndFinishesAtTheCoverEndpoint() {
        diagnosticName = "reverse-to-cover"
        assertInterruptedReturn(lyricsAtRest = false)
    }

    @After
    fun writeRawFrameDiagnostics() {
        if (diagnostics.isEmpty()) return
        val frames = JSONArray()
        diagnostics.forEach { frame ->
            frames.put(
                JSONObject()
                    .put("case", frame.caseName)
                    .put("phase", frame.phase)
                    .put("clockMillis", frame.clockMillis)
                    .put("pixelBoundsDp", frame.pixelBounds.json())
                    .put("slotBoundsDp", frame.slotBounds.json())
                    .put("coverBoundsDp", frame.coverBounds.json())
                    .put("lyricsBoundsDp", frame.lyricsBounds.json())
                    .put("currentState", frame.currentState)
                    .put("targetState", frame.targetState)
                    .put("isRunning", frame.isRunning)
                    .put("drawsFlight", frame.drawsFlight)
                    .put("headerScale", frame.headerScale.finiteJson())
                    .put("headerOffsetPx", frame.headerOffsetPx.finiteJson())
            )
        }
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "lyrics-cover-flight-$diagnosticName.json"
        )
        output.writeText(
            JSONObject()
                .put("clock", "MainTestClock nominal 16ms; not physical 120Hz evidence")
                .put("viewportDp", JSONArray(listOf(ViewportWidthDp, ViewportHeightDp)))
                .put("frames", frames)
                .toString(2)
        )
        Log.i(LogTag, "raw diagnostics=${output.absolutePath} frames=${diagnostics.size}")
    }

    @Composable
    private fun FlightFixture() {
        Box(
            Modifier.size(ViewportWidthDp.dp, ViewportHeightDp.dp)
                .background(Color.Black)
                .testTag(HostTag)
                .onGloballyPositioned { hostBoundsInRoot = it.boundsInRoot() }
        ) {
            transition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(tween(PageFadeMillis, easing = LinearEasing)) togetherWith
                        fadeOut(tween(PageFadeMillis, easing = LinearEasing))
                }
            ) { showLyrics ->
                Box(Modifier.fillMaxSize()) {
                    if (showLyrics) LyricsHeaderEndpoint() else CoverEndpoint()
                }
            }
            NowPlayingLyricsCoverFlightLayer(flight) { modifier ->
                Box(
                    modifier.onGloballyPositioned { slotBoundsInRoot = it.boundsInRoot() }
                        .background(Color.Red)
                )
            }
        }
    }

    @Composable
    private fun CoverEndpoint() {
        DisposableEffect(Unit) { onDispose { coverBoundsInRoot = null } }
        val coverSize = if (immersive) ImmersiveCoverSizeDp else CoverSizeDp
        Box(
            Modifier.offset(
                x = ((ViewportWidthDp - coverSize) / 2f).dp,
                y = (if (immersive) 132 else 125).dp
            )
                .size(coverSize.dp)
                .nowPlayingCoverFlightEndpoint(flight)
                .clip(RoundedCornerShape(24.dp))
                .onGloballyPositioned { coverBoundsInRoot = it.boundsInRoot() }
                .background(Color.Red)
        )
    }

    @Composable
    private fun LyricsHeaderEndpoint() {
        DisposableEffect(Unit) { onDispose { lyricsBoundsInRoot = null } }
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val scale = animateFloatAsState(
            targetValue = if (entered) 0.6f else 1f,
            animationSpec = spring(dampingRatio = 0.8f),
            label = "fixture_header_scale"
        )
        val offset = animateFloatAsState(
            targetValue = if (entered) -8f else 0f,
            animationSpec = spring(dampingRatio = 0.8f),
            label = "fixture_header_offset"
        )
        headerScale = scale.value
        headerOffsetPx = offset.value
        Row(
            Modifier.offset(x = 20.dp, y = 56.dp)
                .width(371.dp).height(56.dp)
                .nowPlayingLyricsCoverFlightRow(flight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(56.dp))
            Box(
                Modifier.size((64 * scale.value).dp)
                    .nowPlayingLyricsCoverFlightEndpoint(flight, scale, offset)
                    .graphicsLayer { translationY = offset.value }
                    .clip(RoundedCornerShape(10.dp))
                    .onGloballyPositioned { lyricsBoundsInRoot = it.boundsInRoot() }
                    .background(Color.Red)
            )
        }
    }

    private fun assertInterruptedReturn(lyricsAtRest: Boolean) {
        val failures = mutableListOf<String>()
        for ((modeIndex, immersiveMode) in listOf(false, true, false).withIndex()) {
            for (closeMillis in listOf(96L, 160L, 224L)) {
                val caseName = "mode=$modeIndex/$immersiveMode close=$closeMillis rest=$lyricsAtRest"
                composeRule.runOnIdle {
                    immersive = immersiveMode
                    lyricsVisible = lyricsAtRest
                }
                advance(1_024L)
                val resting = captureFrame(caseName, "rest")
                composeRule.runOnIdle { lyricsVisible = !lyricsAtRest }
                val closing = captureFrames(closeMillis.toInt() / FrameMillis, caseName, "closing")
                val beforeReverse = closing.last()
                composeRule.runOnIdle { lyricsVisible = lyricsAtRest }
                val returning = listOf(beforeReverse) + captureFrames(64, caseName, "returning")
                val allFrames = listOf(resting) + closing + returning.drop(1)
                val expected = expectedEndpoint(lyricsAtRest, immersiveMode)
                val final = returning.last()
                val restingPixels = resting.pixelBounds
                val beforePixels = beforeReverse.pixelBounds
                if (restingPixels == null || beforePixels == null ||
                    abs(restingPixels.width - beforePixels.width) <= 16f
                ) failures += "$caseName: 正向未产生有效尺寸移动"
                if (beforeReverse.currentState != lyricsAtRest || beforeReverse.targetState == lyricsAtRest) {
                    failures += "$caseName: 反向没有发生在未完成转场内"
                }
                allFrames.forEach { frame ->
                    val pixels = frame.pixelBounds
                    val layout = frame.slotBounds
                    if (pixels == null || layout == null) {
                        failures += "$caseName: ${frame.phase}@${frame.clockMillis} 封面或实际 slot 布局缺失"
                    } else if (boundsError(pixels, layout) > GeometryToleranceDp) {
                        failures += "$caseName: ${frame.phase}@${frame.clockMillis} pixels=$pixels slot=$layout"
                    }
                }
                val visibleReturn = returning.mapNotNull { it.pixelBounds }
                val span = (if (immersiveMode) ImmersiveCoverSizeDp else CoverSizeDp) - 64f * 0.6f
                // 临界阻尼 spring 的峰值速度随距离增长，反向时为保留的速度留出余量
                val maximumStep = span * sqrt(Spring.StiffnessMediumLow.toDouble()).toFloat() /
                    E.toFloat() * FrameMillis / 1_000f * 1.5f + GeometryToleranceDp
                val observedStep = visibleReturn.zipWithNext().maxOfOrNull { (previous, current) ->
                    boundsError(previous, current)
                } ?: Float.POSITIVE_INFINITY
                if (observedStep > maximumStep) {
                    failures += "$caseName: 单帧步长 $observedStep 超过距离相关上限 $maximumStep"
                }
                if (beforePixels == null || visibleReturn.drop(1).none {
                        abs(it.width - beforePixels.width) > GeometryToleranceDp &&
                            abs(it.width - expected.width) > GeometryToleranceDp
                    }
                ) failures += "$caseName: 反向缺少非终点的中间尺寸"
                if (lyricsAtRest && hasShrinkThenExpansion(visibleReturn)) {
                    failures += "$caseName: 反向到歌词出现超过2dp的先缩后扩 pulse"
                }
                if (final.pixelBounds == null || boundsError(final.pixelBounds, expected) > GeometryToleranceDp) {
                    failures += "$caseName: 最终位置或尺寸错误 pixels=${final.pixelBounds} expected=$expected"
                }
                if (final.currentState != lyricsAtRest || final.targetState != lyricsAtRest || final.isRunning) {
                    failures += "$caseName: 最终 Host 未停在目标状态 $final"
                }
                Log.i(LogTag, "case=$caseName maximumStepDp=$maximumStep observedStepDp=$observedStep")
            }
        }
        assertTrue("单层封面转场回归: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    private fun expectedEndpoint(lyrics: Boolean, immersiveMode: Boolean): Rect {
        if (lyrics) {
            val pixelsPerDp = checkNotNull(hostBoundsInRoot).width / ViewportWidthDp
            val size = 64f * 0.6f
            val top = 56f + 56f / 2f - size / 2f - 8f / pixelsPerDp
            return Rect(76f, top, 76f + size, top + size)
        }
        val size = if (immersiveMode) ImmersiveCoverSizeDp else CoverSizeDp
        val left = (ViewportWidthDp - size) / 2f
        val top = if (immersiveMode) 132f else 125f
        return Rect(left, top, left + size, top + size)
    }

    private fun hasShrinkThenExpansion(bounds: List<Rect>): Boolean {
        if (bounds.isEmpty()) return false
        var peakWidth = bounds.first().width
        var minimumAfterShrink = peakWidth
        var shrinking = false
        for (current in bounds.drop(1)) {
            if (!shrinking) {
                peakWidth = maxOf(peakWidth, current.width)
                shrinking = peakWidth - current.width > GeometryToleranceDp
                minimumAfterShrink = current.width
            } else {
                minimumAfterShrink = minOf(minimumAfterShrink, current.width)
                if (current.width > minimumAfterShrink + GeometryToleranceDp) return true
            }
        }
        return false
    }

    private fun captureFrames(count: Int, caseName: String, phase: String): List<FrameSample> =
        List(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            captureFrame(caseName, phase)
        }

    private fun captureFrame(caseName: String, phase: String): FrameSample {
        val pixelBounds = renderedCoverBounds()
        val frame = composeRule.runOnIdle {
            val host = checkNotNull(hostBoundsInRoot)
            FrameSample(
                caseName, phase, composeRule.mainClock.currentTime, pixelBounds,
                slotBoundsInRoot.localDp(host), coverBoundsInRoot.localDp(host),
                lyricsBoundsInRoot.localDp(host), transition.currentState, transition.targetState,
                transition.isRunning, flight.shouldDraw, headerScale, headerOffsetPx
            )
        }
        diagnostics += frame
        Log.i(LogTag, "raw frame=$frame")
        return frame
    }

    private fun renderedCoverBounds(): Rect? {
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
        if (right < left || bottom < top) return null
        val pixelsPerDp = pixels.width / ViewportWidthDp
        return Rect(left / pixelsPerDp, top / pixelsPerDp, (right + 2) / pixelsPerDp, (bottom + 2) / pixelsPerDp)
    }

    private fun Rect?.localDp(host: Rect): Rect? = this?.let { bounds ->
        val pixelsPerDp = host.width / ViewportWidthDp
        Rect(
            (bounds.left - host.left) / pixelsPerDp, (bounds.top - host.top) / pixelsPerDp,
            (bounds.right - host.left) / pixelsPerDp, (bounds.bottom - host.top) / pixelsPerDp
        )
    }

    private fun boundsError(first: Rect, second: Rect): Float = maxOf(
        abs(first.left - second.left), abs(first.top - second.top),
        abs(first.width - second.width), abs(first.height - second.height)
    )

    private fun Rect?.json(): Any = this?.let { bounds ->
        JSONObject().put("left", bounds.left).put("top", bounds.top)
            .put("width", bounds.width).put("height", bounds.height)
    } ?: JSONObject.NULL

    private fun Float.finiteJson(): Any = if (isFinite()) this else JSONObject.NULL

    private fun advance(milliseconds: Long) {
        val end = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < end) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private data class FrameSample(
        val caseName: String,
        val phase: String,
        val clockMillis: Long,
        val pixelBounds: Rect?,
        val slotBounds: Rect?,
        val coverBounds: Rect?,
        val lyricsBounds: Rect?,
        val currentState: Boolean,
        val targetState: Boolean,
        val isRunning: Boolean,
        val drawsFlight: Boolean,
        val headerScale: Float,
        val headerOffsetPx: Float
    )

    private companion object {
        const val HostTag = "lyricsCoverFlightRenderingHost"
        const val LogTag = "LyricsCoverFlightRender"
        const val ViewportWidthDp = 411f
        const val ViewportHeightDp = 914f
        const val CoverSizeDp = 222f
        const val ImmersiveCoverSizeDp = 371f
        const val PageFadeMillis = 300
        const val FrameMillis = 16
        const val GeometryToleranceDp = 2f
    }
}
