package moe.ouom.neriplayer.ui.animation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingContainerMorph
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingMorphState
import moe.ouom.neriplayer.ui.theme.CleanDesignTokens
import moe.ouom.neriplayer.ui.theme.LocalNeriDesign
import moe.ouom.neriplayer.ui.theme.MaterialDesignTokens
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
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
class SharedCoverMiniEndpointRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val flight = SharedCoverFlightState()
    private val artwork: ImageBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
    private val ownerKey = "mini-corner-decoded-${System.nanoTime()}"
    private val coverUrl = "content://mini-corner-fixture/$ownerKey"
    private var contentDensity = Float.NaN
    private lateinit var morph: NowPlayingMorphState
    private lateinit var host: LayoutCoordinates
    private lateinit var mini: LayoutCoordinates
    private lateinit var target: LayoutCoordinates

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun materialMiniKeepsItsEightDpCircularCornersAtLinkedCloseEndpoint() {
        assertLinkedEndpoint(clean = false, targetContinuous = true)
    }

    @Test
    fun cleanMiniKeepsItsContinuousCornersWhenClosingFromCircularLyricsHeader() {
        assertLinkedEndpoint(clean = true, targetContinuous = false)
    }

    private fun assertLinkedEndpoint(clean: Boolean, targetContinuous: Boolean) {
        RetainedPlaybackCoverBitmapCache.put(ownerKey, coverUrl, null, artwork)
        composeRule.setContent {
            FittedTestViewport(320.dp, 480.dp) {
                CompositionLocalProvider(
                    LocalSharedCoverFlight provides flight,
                    LocalNeriDesign provides if (clean) CleanDesignTokens else MaterialDesignTokens
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(
                        primaryContainer = Color.Red,
                        onPrimaryContainer = Color.Red,
                        secondaryContainer = Color.Black,
                        surfaceContainerLow = Color.Black
                    )) {
                        Box(Modifier.fillMaxSize().background(Color.Black).testTag(HostTag)
                            .onGloballyPositioned { host = it }) {
                            // 从现有缓存同步读取同一张图，真实 Mini 也走 Image 裁剪路径
                            NeriMiniPlayer(
                                title = "Song", artist = "Artist", coverUrl = coverUrl,
                                coverIdentityKey = ownerKey,
                                isPlaying = false, enableBlur = false,
                                onPlayPause = {}, onPrevious = {}, onNext = {}, onExpand = {},
                                modifier = Modifier.offset(y = 360.dp)
                                    .onGloballyPositioned { mini = it }
                            )
                            morph = remember { NowPlayingMorphState(initiallyVisible = false) }
                            val targetToken = remember { Any() }
                            val density = LocalDensity.current
                            contentDensity = density.density
                            val targetCornerPx = with(density) { 10.dp.toPx() }
                            NowPlayingContainerMorph(morph, flight, { flight.miniPlayerContainerBounds }) {
                                Box(Modifier.offset(80.dp, 80.dp).size(56.dp)
                                    .onGloballyPositioned { coordinates ->
                                        target = coordinates
                                        flight.targetCoordinates = coordinates
                                        flight.reportTarget(coordinates.boundsInWindow(), targetCornerPx)
                                        flight.reportVisualTarget(targetToken, coordinates) {
                                            CoverFlightFrame(
                                                Rect(Offset.Zero, Size(
                                                    coordinates.size.width.toFloat(),
                                                    coordinates.size.height.toFloat()
                                                )),
                                                targetCornerPx,
                                                continuousCorners = targetContinuous
                                            )
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val source = composeRule.runOnIdle {
            assertFalse("对照截图必须由真实 Mini 绘制", flight.hidesCovers)
            assertEquals(0f, morph.progress.value, 0f)
            assertTrue("Mini 和目标必须实际 attached", mini.isAttached && target.isAttached)
            assertSame("真实 Mini 必须已经从缓存取得同一张解码图", artwork, flight.miniCoverBitmap)
            requireNotNull(flight.miniCoverBounds).also {
                assertTrue("真实 Mini source 必须有有效尺寸", !it.isEmpty && it.width > 30f)
            }
        }
        val before = capture("before", clean, targetContinuous)
        val clock = composeRule.mainClock.currentTime
        composeRule.runOnIdle { flight.beginLinked(artwork) }
        composeRule.waitForIdle()
        val after = capture("after", clean, targetContinuous)
        composeRule.runOnIdle {
            assertTrue("必须由 linked Canvas 实际接管", flight.linked && flight.hidesCovers)
            assertFalse("不能用独立 Flight 代替 linked Canvas", flight.flying)
            assertSame("Canvas 必须持有纯红位图", artwork, flight.linkedBitmap)
            assertSame("交接期间 Mini 保留同一张解码图", artwork, flight.miniCoverBitmap)
            assertTrue("交接时两个端点仍须 attached", mini.isAttached && target.isAttached)
            assertSame(target, flight.targetCoordinates)
            assertEquals("固定 p=0 只比较角部交接", 0f, morph.progress.value, 0f)
        }
        assertEquals("两张截图之间不能推进动画时钟", clock, composeRule.mainClock.currentTime)
        assertCornerPixelsMatch(before, after, source, clean)
    }

    private fun assertCornerPixelsMatch(before: PixelMap, after: PixelMap, source: Rect, clean: Boolean) {
        val hostBounds = composeRule.runOnIdle { host.boundsInWindow() }
        val left = (source.left - hostBounds.left).roundToInt()
        val top = (source.top - hostBounds.top).roundToInt()
        val width = source.width.roundToInt()
        val height = source.height.roundToInt()
        val pixelsPerDp = source.width / 40f
        val cornerSize = (12f * pixelsPerDp).roundToInt()
        assertTrue("真实 Mini 对照必须有红色封面", before[left + width / 2, top + height / 2].red > 0.95f)
        assertTrue("隐藏 Mini 后 Canvas 必须仍画出红色封面", after[left + width / 2, top + height / 2].red > 0.95f)
        var difference = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((x < cornerSize || x >= width - cornerSize) &&
                    (y < cornerSize || y >= height - cornerSize)
                ) {
                    difference += abs(before[left + x, top + y].red - after[left + x, top + y].red)
                }
            }
        }
        val differenceDpSquared = difference / (pixelsPerDp * pixelsPerDp)
        Log.i("MiniCornerEndpoint", "clean=$clean cornerDifferenceDpSquared=$differenceDpSquared")
        assertTrue("p=0 交接必须保持真实 Mini 角部像素 clean=$clean differenceDpSquared=$differenceDpSquared",
            differenceDpSquared <= 0.75f)
    }

    private fun capture(phase: String, clean: Boolean, targetContinuous: Boolean): PixelMap {
        val image = composeRule.onNodeWithTag(HostTag).captureToImage()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, ownerKey)
            .apply { mkdirs() }
        File(output, "$phase.png").outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val geometry = composeRule.runOnIdle {
            val hostBounds = host.boundsInWindow()
            JSONObject()
                .put("phase", phase).put("clean", clean).put("clockMillis", composeRule.mainClock.currentTime)
                .put("density", contentDensity).put("imageWidthPx", image.width).put("imageHeightPx", image.height)
                .put("sourceBoundsWindow", flight.miniCoverBounds.json())
                .put("sourceBoundsInHost", flight.miniCoverBounds?.translate(-hostBounds.topLeft).json())
                .put("sourceCornerPx", flight.miniCoverCornerPx)
                .put("sourceCornerSmoothing", flight.miniCoverCornerSmoothing)
                .put("hostBoundsWindow", hostBounds.json()).put("hostAttached", host.isAttached)
                .put("miniBoundsWindow", mini.boundsInWindow().json()).put("miniAttached", mini.isAttached)
                .put("capsuleBoundsWindow", flight.miniPlayerContainerBounds.json())
                .put("targetBoundsWindow", target.boundsInWindow().json()).put("targetAttached", target.isAttached)
                .put("targetContinuousRequested", targetContinuous)
                .put("targetFrameInHost", flight.targetFrameIn(host)?.toString() ?: JSONObject.NULL)
                .put("miniBitmapIdentity", System.identityHashCode(flight.miniCoverBitmap))
                .put("linkedBitmapIdentity", System.identityHashCode(flight.linkedBitmap))
                .put("artworkIdentity", System.identityHashCode(artwork))
                .put("miniBitmapIsArtwork", flight.miniCoverBitmap === artwork)
                .put("linkedBitmapIsArtwork", flight.linkedBitmap === artwork)
                .put("linked", flight.linked).put("flying", flight.flying).put("hidesCovers", flight.hidesCovers)
                .put("morphProgress", morph.progress.value)
                .put("morphProgressState", flight.nowPlayingMorphProgressState?.value ?: JSONObject.NULL)
                .put("png", File(output, "$phase.png").absolutePath)
        }
        File(output, "$phase.json").writeText(geometry.toString(2))
        Log.i("MiniCornerEndpoint", geometry.toString())
        return image.toPixelMap()
    }

    private fun Rect?.json(): Any = this?.let {
        JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom)
            .put("width", it.width).put("height", it.height)
    } ?: JSONObject.NULL

    @After
    fun releaseLinkedCover() {
        composeRule.runOnIdle { flight.endLinked() }
    }

    private companion object {
        const val HostTag = "miniCornerEndpointHost"
    }
}
