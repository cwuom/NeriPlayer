package moe.ouom.neriplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingAccentBackdrop
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingContainerMorph
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingMorphState
import moe.ouom.neriplayer.ui.playback.visual.backdropAccentRequest
import moe.ouom.neriplayer.ui.playback.visual.rememberNowPlayingMorphState
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingUnderlayOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val flight = SharedCoverFlightState()
    private var visible by mutableStateOf(false)
    private var backProgress by mutableFloatStateOf(0f)
    private var underlayPulse by mutableIntStateOf(0)
    private var optimize by mutableStateOf(true)
    private var attachMorph by mutableStateOf(true)
    private var uiStyle by mutableStateOf(UiStyle.Material)
    private val draws = AtomicInteger()
    private val mounts = AtomicInteger()
    private val disposals = AtomicInteger()
    private lateinit var morph: NowPlayingMorphState

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = true,
                dynamicColor = false,
                seedColorHex = "336699",
                uiStyle = uiStyle,
                animateColors = false
            ) {
                CompositionLocalProvider(LocalSharedCoverFlight provides flight) {
                    morph = rememberNowPlayingMorphState(visible, flight, backProgress = { backProgress })
                    Box(
                        Modifier.requiredSize(280.dp, 480.dp)
                            .background(Color.Cyan)
                            .testTag("underlayViewport")
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .nowPlayingBackdropRecede(flight)
                                .drawNowPlayingUnderlay(
                                    flight = flight,
                                    playbackVisible = { optimize && visible },
                                    backProgress = { backProgress }
                                )
                        ) {
                            DisposableEffect(Unit) {
                                mounts.incrementAndGet()
                                onDispose { disposals.incrementAndGet() }
                            }
                            Canvas(Modifier.fillMaxSize()) {
                                val color = if (underlayPulse % 2 == 0) Color.Red else Color.Green
                                draws.incrementAndGet()
                                drawRect(color)
                            }
                        }
                        if (attachMorph && morph.mounted) {
                            NowPlayingContainerMorph(
                                morph = morph,
                                flight = flight,
                                capsuleInWindow = { Rect(16f, 400f, 264f, 470f) }
                            ) {
                                NowPlayingAccentBackdrop(
                                    request = backdropAccentRequest(null, "underlay-fixture", true, 0, true),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else if (!attachMorph) {
                            Box(Modifier.fillMaxSize().background(Color.Black))
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun fullyOpenedMorphStopsUnderlyingDrawingWithoutUnmountingItsContent() {
        assertUnderlayDraws("未展开")
        openPlayer()
        assertUnderlayStops("完全展开")
        composeRule.runOnIdle {
            assertEquals(1, mounts.get())
            assertEquals(0, disposals.get())
        }
    }

    @Test
    fun closingAndReopeningRestoreDrawingUntilTheNewOpenAnimationCompletes() {
        openPlayer()
        composeRule.runOnIdle { visible = false }
        advance(64L)
        assertUnderlayDraws("关闭过程中")
        composeRule.runOnIdle { visible = true }
        advance(64L)
        assertUnderlayDraws("打断关闭后重新展开")
        advance(1_500L)
        assertUnderlayStops("重新展开完成")
        composeRule.runOnIdle { visible = false }
        advance(1_500L)
        assertUnderlayDraws("完全关闭")
    }

    @Test
    fun draggingAndCancelledBackPreviewKeepEveryExposedUnderlayFrame() {
        composeRule.runOnIdle { flight.dragOpenProgress = 0.25f }
        advance(64L)
        assertUnderlayDraws("拖动展开")
        composeRule.runOnIdle { flight.dragOpenProgress = 0.75f }
        advance(64L)
        assertUnderlayDraws("拖动尚未完成")
        composeRule.runOnIdle {
            visible = true
            flight.dragOpenProgress = null
        }
        advance(1_500L)
        assertUnderlayStops("拖动提交后展开完成")
        composeRule.runOnIdle { backProgress = 0.6f }
        advance(64L)
        assertUnderlayDraws("预测返回预览")
        composeRule.runOnIdle { backProgress = 0f }
        advance(1_500L)
        assertUnderlayStops("预测返回取消后恢复")
    }

    @Test
    fun removingTheMorphBindingKeepsTheUnboundLayoutDrawing() {
        openPlayer()
        composeRule.runOnIdle { attachMorph = false }
        advance(64L)
        assertUnderlayDraws("没有手机 morph 绑定的布局")
        composeRule.runOnIdle { attachMorph = true }
        advance(64L)
        assertUnderlayStops("新的 morph 绑定")
    }

    @Test
    fun bothThemesKeepTheSamePixelsDuringForwardAndInterruptedMorphFrames() {
        openPlayer()
        for (style in listOf(UiStyle.Material, UiStyle.Clean)) {
            composeRule.runOnIdle { uiStyle = style }
            for (progress in listOf(0f, 0.18f, 0.58f, 1f, 0.72f, 1f, 0.35f)) {
                composeRule.runOnIdle { runBlocking { morph.progress.snapTo(progress) } }
                advance(32L)
                composeRule.runOnIdle { optimize = false }
                advance(32L)
                val reference = capture()
                composeRule.runOnIdle { optimize = true }
                advance(32L)
                assertPixelsMatch("$style / progress=$progress", reference, capture())
            }
        }
    }

    private fun openPlayer() {
        composeRule.runOnIdle { visible = true }
        advance(1_500L)
        composeRule.runOnIdle { assertEquals(1f, morph.progress.value, 0f) }
    }

    private fun advance(milliseconds: Long) {
        composeRule.mainClock.advanceTimeBy(milliseconds)
        composeRule.waitForIdle()
    }

    private fun nextUnderlayDrawCount(): Int {
        val before = composeRule.runOnIdle { draws.get() }
        composeRule.runOnIdle { underlayPulse++ }
        advance(32L)
        return composeRule.runOnIdle { draws.get() - before }
    }

    private fun assertUnderlayDraws(stage: String) {
        assertTrue("$stage 仍有露底的可能，必须绘制下层", nextUnderlayDrawCount() > 0)
    }

    private fun assertUnderlayStops(stage: String) {
        assertEquals("$stage 不应重画被完整遮挡的下层", 0, nextUnderlayDrawCount())
    }

    private fun capture(): ImageBitmap =
        composeRule.onNodeWithTag("underlayViewport").captureToImage()

    private fun assertPixelsMatch(stage: String, expected: ImageBitmap, actual: ImageBitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val reference = expected.toPixelMap()
        val candidate = actual.toPixelMap()
        var totalError = 0.0
        var samples = 0
        var outliers = 0
        for (y in 0 until expected.height step 2) {
            for (x in 0 until expected.width step 2) {
                val left = reference[x, y]
                val right = candidate[x, y]
                val error = maxOf(abs(left.red - right.red), abs(left.green - right.green), abs(left.blue - right.blue))
                totalError += error
                samples++
                if (error > 3f / 255f) outliers++
            }
        }
        assertTrue("$stage 平均像素差过大", totalError / samples <= 1.0 / 255.0)
        assertTrue("$stage 异常像素过多", outliers.toDouble() / samples <= 0.005)
    }
}
