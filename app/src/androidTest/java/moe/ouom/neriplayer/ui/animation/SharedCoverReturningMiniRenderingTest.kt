package moe.ouom.neriplayer.ui.animation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingContainerMorph
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingMorphState
import moe.ouom.neriplayer.ui.playback.visual.rememberNowPlayingMorphState
import moe.ouom.neriplayer.ui.theme.LocalNeriDesign
import moe.ouom.neriplayer.ui.theme.MaterialDesignTokens
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
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
class SharedCoverReturningMiniRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val flight = SharedCoverFlightState()
    private val artwork: ImageBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
    private val ownerKey = "returning-mini-${System.nanoTime()}"
    private val coverUrl = "content://returning-mini/$ownerKey"
    private var visible by mutableStateOf(true)
    private var backProgress by mutableFloatStateOf(0f)
    private lateinit var morph: NowPlayingMorphState
    private lateinit var mini: LayoutCoordinates
    private lateinit var host: LayoutCoordinates
    private var miniPositionEvents = 0
    private var miniPositionClosing = false
    private var lastPreClosingMini: Rect? = null
    private var lastPreClosingProgress = 0f

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.mainClock.autoAdvance = false
    }

    private fun renderFixture() {
        RetainedPlaybackCoverBitmapCache.put(ownerKey, coverUrl, null, artwork)
        composeRule.setContent {
            FittedTestViewport(320.dp, 480.dp) {
                CompositionLocalProvider(
                    LocalSharedCoverFlight provides flight,
                    LocalNeriDesign provides MaterialDesignTokens
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(
                        secondaryContainer = Color.Black,
                        onSecondaryContainer = Color.White
                    )) {
                        Box(Modifier.fillMaxSize().background(Color.Black).testTag(HostTag)
                            .onGloballyPositioned { host = it }) {
                            // 与应用相同的 Mini 入场和退场，初始展开态没有可复用的静止源坐标
                            AnimatedVisibility(
                                visible = !visible || backProgress > 0f,
                                modifier = Modifier.align(Alignment.BottomStart),
                                enter = slideInVertically(
                                    tween(220, easing = FastOutSlowInEasing),
                                    initialOffsetY = { it / 2 }
                                ) + fadeIn(tween(180)),
                                exit = slideOutVertically(
                                    tween(180, easing = FastOutSlowInEasing),
                                    targetOffsetY = { it / 2 }
                                ) + fadeOut(tween(120))
                            ) {
                                NeriMiniPlayer(
                                    title = "Song", artist = "Artist", coverUrl = coverUrl,
                                    coverIdentityKey = ownerKey,
                                    isPlaying = false, enableBlur = false,
                                    onPlayPause = {}, onPrevious = {}, onNext = {}, onExpand = { visible = true },
                                    modifier = Modifier.testTag(MiniTag).onGloballyPositioned {
                                        mini = it
                                        miniPositionEvents++
                                        miniPositionClosing = ::morph.isInitialized && morph.closing
                                        if (!miniPositionClosing && ::morph.isInitialized) {
                                            lastPreClosingMini = it.boundsInWindow()
                                            lastPreClosingProgress = morph.progress.value
                                        }
                                    }
                                )
                            }
                            morph = rememberNowPlayingMorphState(
                                visible, flight, artwork = { artwork }, backProgress = { backProgress }
                            )
                            if (morph.mounted) {
                                NowPlayingContainerMorph(morph, flight, { flight.miniPlayerContainerBounds }) {
                                    Box(Modifier.offset(80.dp, 80.dp).size(56.dp)
                                        .sharedCoverTarget(10.dp).background(Color.Red))
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(morph.mounted)
            assertEquals(1f, morph.progress.value, 0f)
            assertEquals(null, flight.miniCoverBounds)
        }
    }

    @Test
    fun closeFollowsTheReturningMiniInsteadOfKeepingItsEntryOffset() {
        renderFixture()
        composeRule.runOnIdle { visible = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue("关闭必须由父级实际接管", flight.linked && flight.hidesCovers)
            assertSame(artwork, flight.linkedBitmap)
        }

        // Mini 的 220ms 入场已落位，250ms 的父级关闭仍在绘制最后几帧
        composeRule.mainClock.advanceTimeBy(224)
        composeRule.waitForIdle()
        assertClosingFrame()
        finishClosing()
    }

    @Test
    fun predictivePreviewFollowsMiniEntryBeforeTheCloseIsCommitted() {
        renderFixture()
        composeRule.runOnIdle { backProgress = 0.5f }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertPreviewFrame("preview-start")

        composeRule.mainClock.advanceTimeBy(224)
        composeRule.waitForIdle()
        assertPreviewFrame("preview-settled")

        composeRule.runOnIdle { visible = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(224)
        composeRule.waitForIdle()
        assertClosingFrame()
        finishClosing()
    }

    @Test
    fun reversingAnOpeningKeepsTheRenderedCoverContinuousWhenMiniGeometryResumes() {
        renderFixture()
        composeRule.runOnIdle { visible = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(320)
        composeRule.waitForIdle()
        val restingMini = composeRule.runOnIdle {
            assertFalse(morph.mounted)
            assertFalse(flight.linked)
            assertTrue(mini.isAttached)
            Triple(mini.boundsInWindow(), requireNotNull(flight.miniCoverBounds),
                requireNotNull(flight.miniPlayerContainerBounds))
        }

        composeRule.onNodeWithTag(MiniTag).performClick()
        composeRule.mainClock.advanceTimeBy(112)
        composeRule.waitForIdle()
        val opening = captureFrame("reverse-opening")
        composeRule.runOnIdle {
            assertTrue("采样必须处于 opening spring 与 Mini 退场重叠阶段",
                visible && morph.mounted && !morph.closing && mini.isAttached &&
                    opening.progress > 0f && opening.progress < 0.9f)
            assertTrue("真实 Mini 必须已经离开冻结源，才覆盖本次反向风险",
                abs(opening.source.center.y - opening.mini.center.y) > 4f)
            assertSame(artwork, flight.linkedBitmap)
        }
        assertRenderedBounds(opening, expectedCoverBounds(opening), "opening")

        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        val reversed = captureFrame("reverse-same-clock")
        assertEquals("反向比较不能推进时钟", opening.clock, reversed.clock)
        assertEquals("同 clock 不能用不同 progress 掩盖交接跳位", opening.progress, reversed.progress, 0f)
        assertRenderedBounds(reversed, singleRedBounds(opening), "same-clock reversal", tolerance = 1f)
        composeRule.runOnIdle { assertSame("反向不能重抓位图", artwork, flight.linkedBitmap) }

        // 坐标回调可能先于关闭 effect，使用最后一次接管前的真实位置作为连续性锚点
        var firstPositioned = reversed
        repeat(4) {
            if (firstPositioned.positionEvents == opening.positionEvents) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                firstPositioned = captureFrame("reverse-positioned-$it")
            }
        }
        assertTrue("必须观察到反向后的真实 Mini 几何回调",
            firstPositioned.positionEvents > opening.positionEvents)
        var closingFrame = firstPositioned
        repeat(3) { index ->
            if (!closingFrame.positionClosing) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                closingFrame = captureFrame("reverse-closing-$index")
            }
        }
        assertTrue("必须检验接管后的真实位置回调", closingFrame.positionClosing)
        val anchorMini = requireNotNull(closingFrame.preClosingMini)
        val anchorProgress = closingFrame.preClosingProgress
        assertTrue("锚点必须来自本轮进行中的展开", anchorProgress > 0f && anchorProgress < 1f)
        fun correctedBounds(rest: Rect, frozen: Rect, frame: RenderedFrame): Rect {
            val live = rest.translate(frame.mini.topLeft - restingMini.first.topLeft)
            val anchor = rest.translate(anchorMini.topLeft - restingMini.first.topLeft)
            val remaining = (frame.progress / anchorProgress).coerceIn(0f, 1f)
            return Rect(live.left + (frozen.left - anchor.left) * remaining,
                live.top + (frozen.top - anchor.top) * remaining,
                live.right + (frozen.right - anchor.right) * remaining,
                live.bottom + (frozen.bottom - anchor.bottom) * remaining)
        }
        var observedMovementAfterHandoff = false
        repeat(3) { index ->
            val frame = closingFrame
            assertEquals(restingMini.first.width, frame.mini.width, 1f)
            assertEquals(restingMini.first.height, frame.mini.height, 1f)
            observedMovementAfterHandoff = observedMovementAfterHandoff ||
                (frame.mini.topLeft - anchorMini.topLeft).getDistance() > 1f
            assertRenderedBounds(frame, expectedCoverBounds(frame,
                correctedBounds(restingMini.second, opening.source, frame),
                correctedBounds(restingMini.third, opening.capsule, frame)),
                "live geometry after handoff-$index", tolerance = 3f)
            if (index < 2) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                closingFrame = captureFrame("reverse-live-$index")
            }
        }
        assertTrue("接管后的采样必须包含真实 Mini 移动，不能只检查冻结首帧", observedMovementAfterHandoff)
        composeRule.runOnIdle { assertSame("实时几何回调不能替换 linked 位图", artwork, flight.linkedBitmap) }

        composeRule.mainClock.advanceTimeBy(320)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(morph.mounted)
            assertFalse(flight.linked)
        }
    }

    private fun assertPreviewFrame(phase: String) {
        val frame = captureFrame(phase)
        composeRule.runOnIdle {
            val sourceDelta = frame.source.center.y - frame.mini.center.y
            assertTrue("预览时播放页仍打开且 Mini 已挂载", visible && morph.mounted && mini.isAttached)
            assertEquals("父级预览必须实际推进到 0.8", 0.8f, morph.progress.value, 0.001f)
            assertTrue("预览必须由同一 linked 位图接管", flight.linked && flight.hidesCovers)
            assertSame("预览和提交之间不能重抓位图", artwork, flight.linkedBitmap)
            assertTrue("预览源必须跟随 Mini 入场: phase=$phase, delta=$sourceDelta", abs(sourceDelta) <= 1f)
        }
        val expected = expectedCoverBounds(frame)
        assertRenderedBounds(frame, expected, phase)
        val target = expectedPageBounds(frame)
        val targetProbe = Offset(target.center.x, target.top + target.height / 4f)
        assertFalse("探针必须位于 Canvas 外，才能检验真实目标图层是否隐藏", expected.contains(targetProbe))
        val pixel = frame.image.toPixelMap()[targetProbe.x.toInt(), targetProbe.y.toInt()]
        assertTrue("预览时真实目标封面不能留下第二块红图层: phase=$phase, point=$targetProbe",
            pixel.red < 0.1f)
    }

    private data class RenderedFrame(
        val image: ImageBitmap,
        val redBounds: List<Rect>,
        val clock: Long,
        val progress: Float,
        val source: Rect,
        val capsule: Rect,
        val mini: Rect,
        val host: Rect,
        val positionEvents: Int,
        val positionClosing: Boolean,
        val preClosingMini: Rect?,
        val preClosingProgress: Float
    )

    private fun captureFrame(phase: String): RenderedFrame {
        val image = composeRule.onNodeWithTag(HostTag).captureToImage()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, ownerKey)
            .apply { mkdirs() }
        val png = File(output, "$phase.png")
        png.outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val redBounds = redComponents(image)
        return composeRule.runOnIdle {
            RenderedFrame(image, redBounds, composeRule.mainClock.currentTime, morph.progress.value,
                requireNotNull(flight.miniCoverBounds), requireNotNull(flight.miniPlayerContainerBounds),
                mini.boundsInWindow(), host.boundsInWindow(), miniPositionEvents,
                miniPositionClosing, lastPreClosingMini, lastPreClosingProgress).also {
                Log.i("ReturningMiniEndpoint", "phase=$phase, clock=${it.clock}, progress=${it.progress}, " +
                    "source=${it.source}, capsule=${it.capsule}, mini=${it.mini}, " +
                    "redBounds=$redBounds, positionEvents=${it.positionEvents}, " +
                    "lastPositionClosing=$miniPositionClosing, closing=${morph.closing}, " +
                    "preClosingMini=$lastPreClosingMini, preClosingProgress=$lastPreClosingProgress, " +
                    "linkedBitmap=${System.identityHashCode(flight.linkedBitmap)}, png=${png.absolutePath}")
            }
        }
    }

    private fun expectedPageBounds(frame: RenderedFrame, capsule: Rect = frame.capsule): Rect {
        val p = frame.progress.coerceIn(0f, 1f)
        val localCapsule = capsule.translate(-frame.host.topLeft)
        val cardLeft = localCapsule.left * (1f - p)
        val cardTop = localCapsule.top * (1f - p)
        val cardWidth = localCapsule.width + (frame.host.width - localCapsule.width) * p
        val scale = cardWidth / frame.host.width
        val density = frame.host.width / 320f
        return Rect(cardLeft + 80f * density * scale, cardTop + 80f * density * scale,
            cardLeft + 136f * density * scale, cardTop + 136f * density * scale)
    }

    private fun expectedCoverBounds(
        frame: RenderedFrame,
        source: Rect = frame.source,
        capsule: Rect = frame.capsule
    ): Rect = androidx.compose.ui.geometry.lerp(
        source.translate(-frame.host.topLeft), expectedPageBounds(frame, capsule), frame.progress.coerceIn(0f, 1f)
    )

    private fun singleRedBounds(frame: RenderedFrame): Rect {
        assertEquals("实际画面必须只有一个红色封面: ${frame.redBounds}", 1, frame.redBounds.size)
        return frame.redBounds.single()
    }

    private fun assertRenderedBounds(frame: RenderedFrame, expected: Rect, phase: String, tolerance: Float = 2f) {
        val actual = singleRedBounds(frame)
        val error = maxOf(abs(actual.left - expected.left), abs(actual.top - expected.top),
            abs(actual.right - expected.right), abs(actual.bottom - expected.bottom))
        assertTrue("Canvas 必须符合该帧真实几何: phase=$phase, p=${frame.progress}, " +
            "actual=$actual, expected=$expected, error=$error", error <= tolerance)
    }

    private fun redComponents(image: ImageBitmap): List<Rect> {
        val pixels = image.toPixelMap()
        val mask = BooleanArray(image.width * image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = pixels[x, y]
            mask[y * image.width + x] = color.red > 0.9f && color.green < 0.1f && color.blue < 0.1f
        }
        val queue = IntArray(mask.size)
        val bounds = mutableListOf<Rect>()
        for (start in mask.indices) {
            if (!mask[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            mask[start] = false
            var left = image.width
            var top = image.height
            var right = -1
            var bottom = -1
            while (head < tail) {
                val index = queue[head++]
                val x = index % image.width
                val y = index / image.width
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
                fun enqueue(next: Int) {
                    if (mask[next]) {
                        mask[next] = false
                        queue[tail++] = next
                    }
                }
                if (x > 0) enqueue(index - 1)
                if (x + 1 < image.width) enqueue(index + 1)
                if (y > 0) enqueue(index - image.width)
                if (y + 1 < image.height) enqueue(index + image.width)
            }
            if (tail >= 16) bounds += Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
        }
        return bounds
    }

    private fun assertClosingFrame() {
        val image = composeRule.onNodeWithTag(HostTag).captureToImage()
        val pixels = image.toPixelMap()
        var redTop = image.height
        var redBottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = pixels[x, y]
                if (color.red > 0.9f && color.green < 0.1f && color.blue < 0.1f) {
                    redTop = minOf(redTop, y)
                    redBottom = maxOf(redBottom, y)
                }
            }
        }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, ownerKey)
            .apply { mkdirs() }
        File(output, "closing.png").outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        composeRule.runOnIdle {
            val source = requireNotNull(flight.miniCoverBounds)
            val actualMini = mini.boundsInWindow()
            val sourceDelta = source.center.y - actualMini.center.y
            val renderedCenterY = host.boundsInWindow().top + (redTop + redBottom + 1) / 2f
            Log.i("ReturningMiniEndpoint", "progress=${morph.progress.value}, source=$source, " +
                "mini=$actualMini, sourceDelta=$sourceDelta, renderedCenterY=$renderedCenterY, " +
                "png=${File(output, "closing.png").absolutePath}")
            assertTrue("采样必须仍在实际关闭末段", morph.mounted && flight.hidesCovers &&
                morph.progress.value > 0f && morph.progress.value < 0.02f)
            assertSame("移动几何不能替换本轮位图", artwork, flight.linkedBitmap)
            assertTrue("父级必须实际画出红色封面", redBottom >= redTop)
            assertTrue("关闭源必须跟随真实 Mini，不能保留入场半高偏移: delta=$sourceDelta", abs(sourceDelta) <= 1f)
            assertTrue("关闭末帧封面必须落在 Mini 槽位: delta=${renderedCenterY - actualMini.center.y}",
                abs(renderedCenterY - actualMini.center.y) <= 4f)
        }
    }

    private fun finishClosing() {
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(morph.mounted)
            assertFalse(flight.linked)
        }
    }

    @After
    fun releaseLinkedCover() {
        composeRule.runOnIdle { flight.endLinked() }
    }

    private companion object {
        const val HostTag = "returningMiniHost"
        const val MiniTag = "returningMini"
    }
}
