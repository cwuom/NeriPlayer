package moe.ouom.neriplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.animation.sharedCoverTarget
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingContainerMorph
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingMorphState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingMorphRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val flight = SharedCoverFlightState()
    private val morph = NowPlayingMorphState(initiallyVisible = true)
    private var viewportPixelsPerDp = 1f

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun phonePortraitWithoutBitmapKeepsThePlaceholderVisible() {
        render(390.dp, 844.dp, hasSource = true, hasBitmap = false)
        assertCoverThroughoutMorph()
    }

    @Test
    fun tabletLandscapeWithoutBitmapKeepsThePlaceholderVisible() {
        render(1280.dp, 800.dp, hasSource = true, hasBitmap = false)
        assertCoverThroughoutMorph()
    }

    @Test
    fun tabletPortraitWithoutMiniPlayerSourceKeepsItsRealCoverVisible() {
        render(800.dp, 1280.dp, hasSource = false, hasBitmap = true)
        assertCoverThroughoutMorph()
    }

    @Test
    fun narrowLandscapeWithoutMiniPlayerSourceKeepsItsRealCoverVisible() {
        render(640.dp, 360.dp, hasSource = false, hasBitmap = false)
        assertCoverThroughoutMorph()
    }

    @Test
    fun phonePortraitWithArtworkKeepsTheFlyingCoverVisible() {
        render(390.dp, 844.dp, hasSource = true, hasBitmap = true)
        assertCoverThroughoutMorph()
    }

    @Test
    fun tabletLandscapeWithArtworkKeepsTheFlyingCoverVisible() {
        render(1280.dp, 800.dp, hasSource = true, hasBitmap = true)
        assertCoverThroughoutMorph()
    }

    @Test
    fun tabletPortraitWithArtworkKeepsTheFlyingCoverVisible() {
        render(800.dp, 1280.dp, hasSource = true, hasBitmap = true)
        assertCoverThroughoutMorph()
    }

    @Test
    fun flyingCoverTracksTheCurrentFrameWhenTheMorphReverses() {
        render(390.dp, 844.dp, hasSource = true, hasBitmap = true)
        listOf(0.2f, 0.7f, 0.4f, 0.9f, 0.6f, 0.3f).forEach { progress ->
            composeRule.runOnIdle { runBlocking { morph.progress.snapTo(progress) } }
            val actual = coverBounds()
            val expected = composeRule.runOnIdle {
                val target = checkNotNull(flight.targetCoordinates)
                val origin = checkNotNull(flight.miniPlayerContainerBounds).let {
                    Offset(it.left, it.bottom - 844f * viewportPixelsPerDp)
                }
                val source = checkNotNull(flight.miniCoverBounds).translate(-origin)
                val destination = Rect(
                    target.localToWindow(Offset.Zero) - origin,
                    target.localToWindow(Offset(target.size.width.toFloat(), target.size.height.toFloat())) - origin
                )
                androidx.compose.ui.geometry.lerp(source, destination, progress)
            }
            assertEquals("cover left at progress=$progress", expected.left, actual.left, 2f)
            assertEquals("cover top at progress=$progress", expected.top, actual.top, 2f)
            assertEquals("cover right at progress=$progress", expected.right, actual.right, 2f)
            assertEquals("cover bottom at progress=$progress", expected.bottom, actual.bottom, 2f)
        }
    }

    @Test
    fun artworkArrivingMidMorphDoesNotTakeTheCoverFromThePage() {
        render(390.dp, 844.dp, hasSource = true, hasBitmap = false)
        composeRule.runOnIdle { runBlocking { morph.progress.snapTo(0.5f) } }
        val before = coverBounds()

        composeRule.runOnIdle {
            flight.reportSource(checkNotNull(flight.miniCoverBounds), 8f, magentaBitmap())
        }

        assertEquals("late artwork must not move the visible page cover", before, coverBounds())
    }

    @Test
    fun losingTheMiniPlayerBitmapMidMorphDoesNotHandTheCoverBackEarly() {
        render(390.dp, 844.dp, hasSource = true, hasBitmap = true)
        composeRule.runOnIdle { runBlocking { morph.progress.snapTo(0.5f) } }
        val before = coverBounds()

        composeRule.runOnIdle {
            flight.reportSource(checkNotNull(flight.miniCoverBounds), 8f, null)
        }

        assertEquals("the flying cover must keep its position until handoff", before, coverBounds())
    }

    private fun magentaBitmap() = ImageBitmap(48, 48).also {
        Canvas(it).drawRect(Rect(0f, 0f, 48f, 48f), Paint().apply { color = Color.Magenta })
    }

    private fun coverBounds(): Rect {
        val frame = composeRule.onNodeWithTag("morphViewport").captureToImage().toPixelMap()
        var left = frame.width
        var top = frame.height
        var right = 0
        var bottom = 0
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val pixel = frame[x, y]
            if (pixel.red > 0.7f && pixel.blue > 0.7f && pixel.green < 0.2f) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue("the cover must remain visible", right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun render(width: Dp, height: Dp, hasSource: Boolean, hasBitmap: Boolean) {
        val bitmap = if (hasBitmap) magentaBitmap() else null
        composeRule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val hostDensity = LocalDensity.current
                val scale = minOf(maxWidth.value / width.value, maxHeight.value / height.value)
                val density = Density(hostDensity.density * scale)
                CompositionLocalProvider(
                    LocalDensity provides density,
                    LocalSharedCoverFlight provides flight
                ) {
                    Box(
                        Modifier.requiredSize(width, height).background(Color.Black).testTag("morphViewport")
                            .onGloballyPositioned { coordinates ->
                                viewportPixelsPerDp = coordinates.size.width / width.value
                                if (hasSource) with(density) {
                                    val origin = coordinates.positionInWindow()
                                    flight.reportSource(
                                        Rect(16.dp.toPx(), (height - 56.dp).toPx(), 56.dp.toPx(), (height - 16.dp).toPx())
                                            .translate(origin),
                                        8.dp.toPx(), bitmap
                                    )
                                    flight.reportMiniPlayerContainer(
                                        Rect(0f, (height - 72.dp).toPx(), width.toPx(), height.toPx())
                                            .translate(origin)
                                    )
                                }
                            }
                    ) {
                        NowPlayingContainerMorph(
                            morph = morph,
                            flight = flight,
                            capsuleInWindow = { flight.miniPlayerContainerBounds },
                            artwork = { flight.miniCoverBitmap }
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.Black)) {
                                Box(
                                    Modifier.offset(32.dp, 64.dp).size(96.dp)
                                        .sharedCoverTarget(12.dp)
                                        .background(Color.Magenta)
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { flight.beginLinked() }
    }

    private fun assertCoverThroughoutMorph() {
        // 内容显现后的往返帧应保留真实封面或替代封面
        listOf(0.5f, 0.8f, 0.95f, 0.8f, 0.5f).forEach { progress ->
            composeRule.runOnIdle { runBlocking { morph.progress.snapTo(progress) } }
            val frame = composeRule.onNodeWithTag("morphViewport").captureToImage().toPixelMap()
            var coverPixels = 0
            for (y in 0 until frame.height step 2) {
                for (x in 0 until frame.width step 2) {
                    val pixel = frame[x, y]
                    if (pixel.red > 0.7f && pixel.blue > 0.7f && pixel.green < 0.2f) coverPixels++
                }
            }
            val minimumSide = 24f * viewportPixelsPerDp
            assertTrue("cover disappeared at morph progress=$progress", coverPixels > minimumSide * minimumSide / 4f)
        }
    }
}
