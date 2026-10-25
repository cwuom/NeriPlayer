package moe.ouom.neriplayer.ui.screen.nowplaying.cover

import android.graphics.Bitmap
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
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.animation.sharedCoverSource
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingContainerMorph
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingMorphState
import moe.ouom.neriplayer.ui.screen.host.rememberHostPredictiveBackTransition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalSharedTransitionApi::class)
class NowPlayingCoverParentHandoffRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val artwork: ImageBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
    private val parentFlight = SharedCoverFlightState()
    private val source = NowPlayingCoverSource(
        "content://parent-handoff/decoded-red", "parent-handoff-song", emptyList(),
        "parent-handoff-cache", "decoded red fixture"
    )
    private var lyricsVisible by mutableStateOf(false)
    private lateinit var transition: Transition<Boolean>
    private lateinit var flight: NowPlayingLyricsCoverFlight
    private lateinit var morph: NowPlayingMorphState
    private lateinit var owner: NowPlayingCoverOwner
    private var hostBoundsInWindow: Rect? = null
    private var slotBoundsInWindow: Rect? = null
    private var caseName = "unstarted"
    private val samples = mutableListOf<FrameSample>()

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FittedTestViewport(ViewportWidthDp.dp, ViewportHeightDp.dp) {
                CompositionLocalProvider(LocalSharedCoverFlight provides parentFlight) {
                    val rememberedOwner = rememberNowPlayingCoverOwner()
                    owner = remember(rememberedOwner) {
                        val request = requireNotNull(rememberedOwner.requestFor(
                            NowPlayingCoverRequestInput(source.coverUrl, source.songKey, source.cacheKey)
                        ))
                        rememberedOwner.publishDecodedFrame(request, request.frame.copy(decodedBitmap = artwork))
                        rememberedOwner
                    }
                    transition = rememberHostPredictiveBackTransition(
                        targetState = lyricsVisible,
                        backEnabled = lyricsVisible,
                        backTargetState = false,
                        onBack = { lyricsVisible = false },
                        label = "parent_handoff_host"
                    )
                    flight = rememberNowPlayingLyricsCoverFlight(transition)
                    // 固定 p=1，仅切换绘制归属，不让正常收起位移掩盖交接跳变
                    morph = remember { NowPlayingMorphState(initiallyVisible = true) }
                    HandoffFixture()
                }
            }
        }
        advance(1_024L)
    }

    @Test
    fun settledCoverKeepsItsVisibleBoundsWhenParentTakesOwnershipAtTheSameClock() {
        caseName = "settled-cover-control"
        val before = captureFrame("before-parent")
        assertFalse("控制例应停在大封面", before.drawsFlight)
        assertRect(LargeCoverDp, requirePixels(before), "控制例的大封面位置必须正确")
        assertHandoffAtSameClock(before)
    }

    @Test
    fun returningCoverKeepsItsIntermediateVisibleBoundsWhenParentTakesOwnership() {
        caseName = "returning-cover-handoff"
        composeRule.runOnIdle { lyricsVisible = true }
        advance(1_024L)
        composeRule.runOnIdle { lyricsVisible = false }
        advance(160L)
        val before = captureFrame("before-parent")
        composeRule.runOnIdle {
            assertTrue("前置必须处于歌词回封面的实际转场中: $before", transition.currentState)
            assertFalse(transition.targetState)
            assertTrue("唯一封面层必须已经接管: $before", flight.shouldDraw)
        }
        val pixels = requirePixels(before)
        assertRect(requireNotNull(before.slotDp), pixels, "接管前像素必须位于实际飞行 slot")
        assertTrue("前置必须离大封面终点至少16dp，不能用已结束转场制造假通过: $before",
            boundsError(pixels, LargeCoverDp) > 16f)
        assertHandoffAtSameClock(before)
    }

    private fun assertHandoffAtSameClock(before: FrameSample) {
        composeRule.runOnIdle {
            assertEquals("探针不能提前启动父容器运动", 1f, morph.progress.value, 0f)
            assertFalse(parentFlight.linked)
            assertSame("真实 CoverPanel 必须使用预置解码图", artwork, owner.displayedFrame?.decodedBitmap)
            assertTrue("真实 mini source 必须已注册", parentFlight.miniCoverBounds != null)
            assertTrue("真实 CoverPanel.sharedCoverTarget 必须仍 attached",
                parentFlight.targetCoordinates?.isAttached == true)
        }
        assertRect(LargeCoverDp, requireNotNull(before.parentTargetDp), "父目标必须来自真实大封面端点")
        composeRule.runOnIdle { parentFlight.beginLinked(artwork) }
        composeRule.waitForIdle()
        val after = captureFrame("after-parent")
        composeRule.runOnIdle {
            assertTrue("父层必须实际满足 linked Canvas 条件", parentFlight.linked && parentFlight.hidesCovers)
            assertSame(artwork, parentFlight.linkedBitmap)
            assertFalse("接管后不能继续画子飞行层", flight.shouldDraw)
            assertTrue("接管后端点必须隐藏", flight.hidesEndpoints)
            assertEquals("probe 全程保持 p=1", 1f, morph.progress.value, 0f)
        }
        assertEquals("两次截图之间不推进 Host 动画", before.clockMillis, after.clockMillis)
        assertRect(requirePixels(before), requirePixels(after),
            "p=1 只切绘制归属时封面不能跳到未动画的大端点 before=$before after=$after")
    }

    @Composable
    private fun HandoffFixture() {
        Box(
            Modifier.size(ViewportWidthDp.dp, ViewportHeightDp.dp)
                .background(Color.Black).testTag(HostTag)
                .onGloballyPositioned { hostBoundsInWindow = it.boundsInWindow() }
        ) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier.offset(20.dp, 850.dp).size(40.dp)
                    .onGloballyPositioned { parentFlight.reportMiniPlayerContainer(it.boundsInWindow()) }
                    .sharedCoverSource(10.dp, artwork)
            )
            NowPlayingContainerMorph(
                morph = morph,
                flight = parentFlight,
                capsuleInWindow = { parentFlight.miniPlayerContainerBounds }
            ) {
                SharedTransitionLayout(Modifier.fillMaxSize().background(Color.Black)) {
                    transition.AnimatedContent(
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            fadeIn(tween(300, easing = LinearEasing)) togetherWith
                                fadeOut(tween(300, easing = LinearEasing))
                        }
                    ) { showLyrics ->
                        Box(Modifier.fillMaxSize()) {
                            if (showLyrics) {
                                LyricsEndpoint()
                            } else {
                                NowPlayingCoverPanel(
                                    owner = owner, source = source, song = null,
                                    previewSessionKey = source.songKey, offlineMode = true,
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    showSourceBadge = false, animateSourceBadge = false,
                                    playbackSourceType = null, onPreviewUnavailable = {},
                                    modifier = Modifier.offset(20.dp, 132.dp).size(371.dp),
                                    preferredSize = 371.dp, coverFlight = flight
                                )
                            }
                        }
                    }
                    NowPlayingLyricsCoverFlightLayer(flight) { modifier ->
                        PassiveNowPlayingCoverImage(owner, source,
                            modifier.onGloballyPositioned { slotBoundsInWindow = it.boundsInWindow() })
                    }
                }
            }
        }
    }

    @Composable
    private fun LyricsEndpoint() {
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val scale = animateFloatAsState(if (entered) 0.6f else 1f, spring(dampingRatio = 0.8f), label = "handoff_header_scale")
        val offset = animateFloatAsState(if (entered) -8f else 0f, spring(dampingRatio = 0.8f), label = "handoff_header_offset")
        Row(
            Modifier.offset(20.dp, 56.dp).width(371.dp).height(56.dp)
                .nowPlayingLyricsCoverFlightRow(flight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(56.dp))
            PassiveNowPlayingCoverImage(owner, source,
                Modifier.size((64 * scale.value).dp)
                    .nowPlayingLyricsCoverFlightEndpoint(flight, scale, offset)
                    .graphicsLayer { translationY = offset.value }
                    .clip(RoundedCornerShape(10.dp)))
        }
    }

    private fun captureFrame(phase: String): FrameSample {
        val image = composeRule.onNodeWithTag(HostTag).captureToImage().toPixelMap()
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = image[x, y]
                if (color.red > 0.8f && color.red > color.green * 2f && color.red > color.blue * 2f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        val pixelsPerDp = image.width / ViewportWidthDp
        val pixels = if (right < left || bottom < top) null else Rect(
            left / pixelsPerDp, top / pixelsPerDp, (right + 1) / pixelsPerDp, (bottom + 1) / pixelsPerDp)
        val frame = composeRule.runOnIdle {
            val host = requireNotNull(hostBoundsInWindow)
            FrameSample(phase, composeRule.mainClock.currentTime, pixels,
                slotBoundsInWindow.localDp(host),
                parentFlight.targetCoordinates?.takeIf { it.isAttached }?.boundsInWindow().localDp(host),
                transition.currentState, transition.targetState, flight.shouldDraw,
                parentFlight.linked, parentFlight.hidesCovers, morph.progress.value)
        }
        samples += frame
        Log.i(LogTag, "case=$caseName frame=$frame")
        return frame
    }

    @After
    fun releaseParentAndWriteDiagnostics() {
        if (samples.isEmpty()) return
        composeRule.runOnIdle { parentFlight.endLinked() }
        val rows = JSONArray()
        samples.forEach { frame ->
            rows.put(JSONObject().put("phase", frame.phase).put("clockMillis", frame.clockMillis)
                .put("pixelBoundsDp", frame.pixelsDp.json()).put("slotBoundsDp", frame.slotDp.json())
                .put("parentTargetDp", frame.parentTargetDp.json())
                .put("currentState", frame.current).put("targetState", frame.target)
                .put("drawsFlight", frame.drawsFlight).put("parentLinked", frame.parentLinked)
                .put("parentHidesCovers", frame.parentHidesCovers).put("morphProgress", frame.progress))
        }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "cover-parent-handoff-$caseName.json")
        output.writeText(JSONObject().put("case", caseName).put("frames", rows)
            .put("scope", "fixed p=1 ownership probe; no physical refresh-rate or full-dismissal claim").toString(2))
        Log.i(LogTag, "raw diagnostics=${output.absolutePath}")
    }

    private fun requirePixels(frame: FrameSample): Rect {
        assertTrue("真实封面不能在交接帧消失: $frame", frame.pixelsDp != null)
        return requireNotNull(frame.pixelsDp)
    }

    private fun assertRect(expected: Rect, actual: Rect, message: String) {
        assertTrue("$message expected=$expected actual=$actual samples=$samples",
            boundsError(expected, actual) <= 2f)
    }

    private fun boundsError(first: Rect, second: Rect): Float = maxOf(
        abs(first.left - second.left), abs(first.top - second.top),
        abs(first.width - second.width), abs(first.height - second.height))

    private fun Rect?.localDp(host: Rect): Rect? = this?.let {
        val scale = host.width / ViewportWidthDp
        Rect((it.left - host.left) / scale, (it.top - host.top) / scale,
            (it.right - host.left) / scale, (it.bottom - host.top) / scale)
    }

    private fun Rect?.json(): Any = this?.let {
        JSONObject().put("left", it.left).put("top", it.top).put("width", it.width).put("height", it.height)
    } ?: JSONObject.NULL

    private fun advance(milliseconds: Long) {
        val deadline = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private data class FrameSample(
        val phase: String, val clockMillis: Long, val pixelsDp: Rect?, val slotDp: Rect?,
        val parentTargetDp: Rect?, val current: Boolean, val target: Boolean,
        val drawsFlight: Boolean, val parentLinked: Boolean, val parentHidesCovers: Boolean,
        val progress: Float
    )

    private companion object {
        const val ViewportWidthDp = 411f
        const val ViewportHeightDp = 914f
        const val HostTag = "coverParentHandoffHost"
        const val LogTag = "CoverParentHandoff"
        val LargeCoverDp = Rect(20f, 132f, 391f, 503f)
    }
}
