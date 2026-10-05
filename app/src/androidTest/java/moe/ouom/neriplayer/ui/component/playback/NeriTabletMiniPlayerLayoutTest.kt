package moe.ouom.neriplayer.ui.component.playback

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class NeriTabletMiniPlayerLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val playback = mutableStateOf(Playback())
    private val viewportWidth = mutableStateOf(1280.dp)
    private val calls = mutableListOf<Action>()
    private val seekRequests = mutableListOf<SeekRequest>()
    private var pixelsPerDp = 1f

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletLandscapeUsesThreeGroupsAndEveryActionWithoutExpanding() {
        render(1280.dp, 800.dp)
        assertLayout(TabletMiniPlayerLayout.Full)
        val player = bounds(PlayerTag)
        assertEquals(player.center.x, bounds(Action.PlayPause.tag).center.x, 1f)
        assertTrue(bounds(MetadataTag).right <= bounds(Action.Shuffle.tag).left + 1f)
        assertTrue(bounds(Action.Repeat.tag).right <= bounds(Action.Volume.tag).left + 1f)
        exerciseEveryAction(TabletMiniPlayerLayout.Full)
        capture("tablet-mini-landscape")
    }

    @Test
    fun tabletPortraitKeepsUtilitiesAndOverflowModesReachable() {
        render(800.dp, 1280.dp)
        assertLayout(TabletMiniPlayerLayout.Compact)
        exerciseEveryAction(TabletMiniPlayerLayout.Compact)
        capture("tablet-mini-portrait")
    }

    @Test
    fun tablet600WithLargeFontUsesActualContentWidthAndKeepsAllActions() {
        render(600.dp, 960.dp, fontScale = 2f)
        assertLayout(TabletMiniPlayerLayout.Overflow)
        exerciseEveryAction(TabletMiniPlayerLayout.Overflow)
        capture("tablet-mini-600-large-font")
    }

    @Test
    fun narrowTabletMovesSkipAndUtilitiesIntoOverflowWithoutLosingMetadata() {
        render(360.dp, 640.dp, fontScale = 1.5f)
        assertLayout(TabletMiniPlayerLayout.Minimal)
        exerciseEveryAction(TabletMiniPlayerLayout.Minimal)
        capture("tablet-mini-narrow")
    }

    @Test
    fun resizeKeepsActionsReachableAcrossAllFourLayouts() {
        render(1280.dp, 800.dp)
        val cases = listOf(
            1280.dp to TabletMiniPlayerLayout.Full,
            800.dp to TabletMiniPlayerLayout.Compact,
            520.dp to TabletMiniPlayerLayout.Overflow,
            360.dp to TabletMiniPlayerLayout.Minimal
        )
        cases.forEach { (width, layout) ->
            composeRule.runOnIdle { viewportWidth.value = width }
            assertLayout(layout)
            clickAction(Action.Queue, layout)
        }
        composeRule.runOnIdle {
            assertEquals(List(4) { Action.Queue }, calls)
            assertTrue(seekRequests.isEmpty())
        }
    }

    @Test
    fun phoneWithTabletStateKeepsLegacyHeightAndControls() {
        render(1280.dp, 360.dp, smallestScreenWidthDp = 599)
        assertLegacyLayout()
        composeRule.onNodeWithTag(Action.Previous.tag).assertDoesNotExist()
        composeRule.onNodeWithTag(Action.Next.tag).assertDoesNotExist()
    }

    @Test
    fun tabletWithoutNewControlsPreservesLegacyLayout() {
        render(800.dp, 1280.dp, provideTabletControls = false)
        assertLegacyLayout()
        composeRule.onNodeWithTag(Action.Previous.tag).assertIsDisplayed()
        composeRule.onNodeWithTag(Action.Next.tag).assertIsDisplayed()
    }

    @Test
    fun progressSemanticsClampFiniteRequestsAndRejectNonFiniteRequests() {
        render(1280.dp, 800.dp)
        assertProgress(0.25f)
        setProgress(0.5f)
        setProgress(-1f)
        setProgress(2f)
        progressNode().performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertFalse(action(Float.NaN))
            assertFalse(action(Float.POSITIVE_INFINITY))
            assertFalse(action(Float.NEGATIVE_INFINITY))
        }
        composeRule.runOnIdle {
            assertEquals(listOf(60_000L, 0L, 120_000L), seekRequests.map { it.positionMs })
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun mergedAccessibilityKeepsProgressSeparateFromTheExpandablePlayer() {
        render(1280.dp, 800.dp)
        val player = composeRule.onNodeWithTag(PlayerTag).assertIsEnabled().fetchSemanticsNode()
        val progress = composeRule.onNodeWithTag(ProgressTag).assertIsDisplayed().assertIsEnabled()
            .fetchSemanticsNode()
        assertTrue("进度应有独立的可访问节点", progress.id != player.id)
        assertEquals(bounds(ProgressTag), progress.boundsInRoot)
        assertEquals(12f * pixelsPerDp, progress.boundsInRoot.height, 1f)
        assertEquals(player.boundsInRoot.top, progress.boundsInRoot.top, 1f)
        assertEquals(player.boundsInRoot.left, progress.boundsInRoot.left, 1f)
        assertEquals(player.boundsInRoot.right, progress.boundsInRoot.right, 1f)
        assertEquals(0.25f, progress.config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        assertNull(player.config.getOrNull(SemanticsActions.SetProgress))
        composeRule.onNodeWithTag(ProgressTag).performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(0.5f))
        }
        listOf(Playback(seekEnabled = false), Playback(durationMs = 0L)).forEach { state ->
            composeRule.runOnIdle { playback.value = state }
            val disabledProgress = composeRule.onNodeWithTag(ProgressTag).assertIsNotEnabled()
                .fetchSemanticsNode()
            assertNull(disabledProgress.config.getOrNull(SemanticsActions.SetProgress))
            composeRule.onNodeWithTag(PlayerTag).assertIsEnabled()
            composeRule.onNodeWithTag(MetadataTag, useUnmergedTree = true).performTouchInput { click() }
        }
        composeRule.runOnIdle {
            assertEquals(listOf(SeekRequest("track-a", 60_000L)), seekRequests)
            assertEquals(listOf(Action.Expand, Action.Expand), calls)
        }
    }

    @Test
    fun progressTapAndDragSeekWithoutChangingTrackOrExpandingPlayer() {
        render(1280.dp, 800.dp)
        progressNode().performTouchInput { click(Offset(width * 0.2f, centerY)) }
        progressNode().performTouchInput {
            swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY), durationMillis = 400L)
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.runOnIdle {
            assertEquals(2, seekRequests.size)
            assertTrue(seekRequests[0].positionMs in 22_000L..26_000L)
            assertTrue(seekRequests[1].positionMs in 94_000L..98_000L)
            assertTrue("拖动进度误触了其它动作: $calls", calls.isEmpty())
        }
        assertProgress(0.25f)
    }

    @Test
    fun progressRendersOnTheGlassTopEdgeAndKeepsEndpointThumbsInside() {
        render(600.dp, 240.dp, progressPixelFixture = true)
        val positions = listOf(0L, 30_000L, 120_000L)
        positions.forEach { position ->
            composeRule.runOnIdle { playback.value = playback.value.copy(positionMs = position) }
            val progress = position / 120_000f
            assertProgressPixelsAlignedToGlassEdge(progress, dragging = false)
            capture("tablet-mini-top-edge-$position")
        }
        listOf(0f, 1f).forEach { endpoint ->
            progressNode().performTouchInput {
                down(Offset(width * 0.5f, centerY))
                moveTo(Offset(width * endpoint, centerY), delayMillis = 100L)
            }
            assertProgress(endpoint)
            assertProgressPixelsAlignedToGlassEdge(endpoint, dragging = true)
            capture("tablet-mini-top-edge-drag-${endpoint.toInt()}")
            progressNode().performTouchInput { up() }
        }
        composeRule.runOnIdle {
            assertEquals(listOf(0L, 120_000L), seekRequests.map { it.positionMs })
            assertTrue("端点进度操作误触了其它动作: $calls", calls.isEmpty())
        }
    }

    @Test
    fun canceledDragDoesNotSeekOrInvokeTheParentPlayer() {
        render(1280.dp, 800.dp)
        startDrag()
        progressNode().performTouchInput { cancel() }
        assertNoPlaybackCallbacks()
        assertProgress(0.25f)
    }

    @Test
    fun changingTrackDuringDragDiscardsPreviewAndUsesTheNewCallback() {
        render(1280.dp, 800.dp)
        startDrag()
        composeRule.runOnIdle { playback.value = Playback(trackKey = "track-b", positionMs = 12_000L) }
        finishInterruptedDrag()
        assertNoPlaybackCallbacks()
        assertProgress(0.1f)
        setProgress(0.5f)
        composeRule.runOnIdle { assertEquals(listOf(SeekRequest("track-b", 60_000L)), seekRequests) }
    }

    @Test
    fun revokingSeekDuringDragDropsTheRequestAndRestoresLivePosition() {
        render(1280.dp, 800.dp)
        startDrag()
        composeRule.runOnIdle { playback.value = playback.value.copy(seekEnabled = false, positionMs = 12_000L) }
        finishInterruptedDrag()
        assertDisabledProgress()
        assertProgress(0.1f)
        assertNoPlaybackCallbacks()
        composeRule.runOnIdle { playback.value = playback.value.copy(seekEnabled = true) }
        setProgress(0.5f)
        composeRule.runOnIdle { assertEquals(listOf(SeekRequest("track-a", 60_000L)), seekRequests) }
    }

    @Test
    fun durationBecomingUnknownCancelsDragAndDoesNotReuseTheOldDuration() {
        render(1280.dp, 800.dp)
        startDrag()
        composeRule.runOnIdle { playback.value = playback.value.copy(durationMs = 0L) }
        finishInterruptedDrag()
        assertDisabledProgress()
        assertProgress(0f)
        assertNoPlaybackCallbacks()
        composeRule.runOnIdle { playback.value = playback.value.copy(durationMs = 300_000L, positionMs = 0L) }
        setProgress(0.5f)
        composeRule.runOnIdle { assertEquals(listOf(SeekRequest("track-a", 150_000L)), seekRequests) }
    }

    @Test
    fun unknownDurationAndRestrictedSeekCannotBeDraggedIntoOtherActions() {
        render(600.dp, 960.dp)
        listOf(
            Playback(durationMs = 0L),
            Playback(durationMs = -1L),
            Playback(seekEnabled = false)
        ).forEach { state ->
            composeRule.runOnIdle { playback.value = state }
            assertDisabledProgress()
            progressNode().performTouchInput { click(Offset(width * 0.7f, centerY)) }
            progressNode().performTouchInput {
                swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY), durationMillis = 400L)
            }
            assertNoPlaybackCallbacks()
        }
    }

    private fun render(
        width: Dp,
        height: Dp,
        smallestScreenWidthDp: Int = 800,
        fontScale: Float = 1f,
        provideTabletControls: Boolean = true,
        progressPixelFixture: Boolean = false
    ) {
        viewportWidth.value = width
        composeRule.setContent {
            MaterialTheme(colorScheme = if (progressPixelFixture) ProgressPixelColors else MaterialTheme.colorScheme) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hostDensity = LocalDensity.current
                    val logicalWidth = viewportWidth.value
                    val scale = minOf(maxWidth.value / logicalWidth.value, maxHeight.value / height.value)
                    val density = Density(hostDensity.density * scale, fontScale)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        this.smallestScreenWidthDp = smallestScreenWidthDp
                        screenWidthDp = logicalWidth.value.toInt()
                        screenHeightDp = height.value.toInt()
                        orientation = if (logicalWidth > height) Configuration.ORIENTATION_LANDSCAPE
                        else Configuration.ORIENTATION_PORTRAIT
                    }
                    CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides configuration) {
                        SideEffect { pixelsPerDp = density.density }
                        Box(
                            Modifier.requiredSize(logicalWidth, height).testTag(ViewportTag)
                                .then(if (progressPixelFixture) Modifier.background(Color.Cyan) else Modifier)
                        ) {
                            val state = playback.value
                            val controls = MiniPlayerTabletControls(
                                trackKey = state.trackKey,
                                positionMs = state.positionMs,
                                durationMs = state.durationMs,
                                seekEnabled = state.seekEnabled,
                                shuffleEnabled = false,
                                repeatMode = Player.REPEAT_MODE_OFF,
                                onSeek = { seekRequests += SeekRequest(state.trackKey, it) },
                                onShuffle = { calls += Action.Shuffle },
                                onRepeat = { calls += Action.Repeat },
                                onVolume = { calls += Action.Volume },
                                onListenTogether = { calls += Action.ListenTogether },
                                onQueue = { calls += Action.Queue }
                            )
                            NeriMiniPlayer(
                                title = Title,
                                artist = Artist,
                                coverUrl = null,
                                isPlaying = false,
                                modifier = (if (progressPixelFixture) Modifier.padding(16.dp) else Modifier)
                                    .testTag(PlayerTag),
                                onPlayPause = { calls += Action.PlayPause },
                                onPrevious = { calls += Action.Previous },
                                onNext = { calls += Action.Next },
                                onExpand = { calls += Action.Expand },
                                enableBlur = false,
                                tabletControls = controls.takeIf { provideTabletControls }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertLayout(layout: TabletMiniPlayerLayout) {
        val player = bounds(PlayerTag)
        assertEquals(80f * pixelsPerDp, player.height, 1f)
        composeRule.onNodeWithText(Title, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(Artist, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("00:30 / 02:00", useUnmergedTree = true).assertIsDisplayed()
        listOf(Title, Artist, "00:30 / 02:00").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    assertTrue(action(layouts))
                }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertTrue("紧凑播放器文字被竖向裁切: $text",
                layout.getLineBottom(0) <= layout.size.height + 1f)
            val textBounds = composeRule.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("歌曲信息没有保留底部间距: $text",
                textBounds.bottom <= player.bottom - 2f * pixelsPerDp + 1f)
        }
        val metadata = bounds(MetadataTag)
        assertTrue("歌曲信息空间不足: $metadata", metadata.width >= 120f * pixelsPerDp - 1f)
        val bodyTop = player.top + 4f * pixelsPerDp
        assertEquals("歌曲信息上下留白不一致", metadata.top - bodyTop, player.bottom - metadata.bottom, 1f)
        val direct = Action.entries.filter { isDirect(it, layout) }
        Action.entries.forEach { action ->
            if (action in direct) {
                composeRule.onNodeWithTag(action.tag).assertIsDisplayed().assertIsEnabled()
                val control = bounds(action.tag)
                assertContained(control, player)
                assertEquals(48f * pixelsPerDp, control.width, 1f)
                assertEquals(48f * pixelsPerDp, control.height, 1f)
                assertEquals("按钮上下留白不一致", control.top - bodyTop, player.bottom - control.bottom, 1f)
            } else composeRule.onNodeWithTag(action.tag).assertDoesNotExist()
        }
        val firstControl = direct.minOf { bounds(it.tag).left }
        assertTrue("文字与控件重叠: $metadata/$firstControl", metadata.right <= firstControl + 1f)
        if (layout == TabletMiniPlayerLayout.Full) composeRule.onNodeWithTag(OverflowTag).assertDoesNotExist()
        else composeRule.onNodeWithTag(OverflowTag).assertIsDisplayed()
        val progress = bounds(ProgressTag)
        assertContained(progress, player)
        assertEquals(12f * pixelsPerDp, progress.height, 1f)
        assertEquals(player.top, progress.top, 1f)
        assertEquals(player.left, progress.left, 1f)
        assertEquals(player.right, progress.right, 1f)
        val buttonTags = direct.map { it.tag } +
            listOfNotNull(OverflowTag.takeIf { layout != TabletMiniPlayerLayout.Full })
        assertTrue("进度区域与控件重叠", buttonTags.all { bounds(it).top >= progress.bottom - 1f })
    }

    private fun assertLegacyLayout() {
        assertEquals(64f * pixelsPerDp, bounds(PlayerTag).height, 1f)
        progressNode().assertDoesNotExist()
        composeRule.onNodeWithTag(OverflowTag).assertDoesNotExist()
        composeRule.onNodeWithTag(Action.Volume.tag).assertDoesNotExist()
        composeRule.onNodeWithTag(Action.PlayPause.tag).assertIsDisplayed()
        composeRule.onNodeWithText(Title, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertProgressPixelsAlignedToGlassEdge(progress: Float, dragging: Boolean) {
        val playerBounds = bounds(PlayerTag)
        val progressBounds = bounds(ProgressTag)
        val viewportBounds = bounds(ViewportTag)
        assertEquals(80f * pixelsPerDp, playerBounds.height, 1f)
        assertEquals(12f * pixelsPerDp, progressBounds.height, 1f)
        assertEquals(playerBounds.top, progressBounds.top, 1f)
        assertEquals(playerBounds.left, progressBounds.left, 1f)
        assertEquals(playerBounds.right, progressBounds.right, 1f)

        val pixels = progressNode().captureToImage().toPixelMap()
        val strokeBottom = ceil(4f * pixelsPerDp).toInt() + 1
        listOf(0.4f, 0.7f).forEach { fraction ->
            val x = (pixels.width * fraction).roundToInt().coerceIn(0, pixels.width - 1)
            val topInk = (0 until minOf(strokeBottom, pixels.height)).maxOf { y ->
                val color = pixels[x, y]
                maxOf(color.red, color.green, color.blue)
            }
            assertTrue("进度线未贴玻璃上边缘: progress=$progress x=$x topInk=$topInk", topInk > 0.03f)
            val thickLinePixel = pixels[x, (3f * pixelsPerDp).toInt().coerceIn(0, pixels.height - 1)]
            assertTrue("进度线未加粗到 4dp: progress=$progress x=$x color=$thickLinePixel",
                maxOf(thickLinePixel.red, thickLinePixel.green, thickLinePixel.blue) > 0.03f)
            for (y in strokeBottom until pixels.height) {
                val color = pixels[x, y]
                assertTrue("进度线离开了玻璃上边缘: progress=$progress x=$x y=$y color=$color",
                    maxOf(color.red, color.green, color.blue) < 0.03f)
            }
        }

        val radius = (if (dragging) 5f else 3f) * pixelsPerDp
        val thumbCenter = (progress * pixels.width).coerceIn(radius, pixels.width - radius)
        val thumbRow = maxOf(radius.roundToInt(), ceil(4f * pixelsPerDp).toInt())
            .coerceIn(0, pixels.height - 1)
        val sampleOffset = thumbRow + 0.5f - radius
        val thumbHalfWidth = sqrt((radius * radius - sampleOffset * sampleOffset).coerceAtLeast(0f))
        val thumbPixels = (0 until pixels.width).filter { x -> isProgressPrimary(pixels[x, thumbRow]) }
        assertTrue("进度圆点未完整显示: progress=$progress dragging=$dragging", thumbPixels.isNotEmpty())
        assertTrue("进度圆点左边缘被裁切: progress=$progress pixels=$thumbPixels",
            abs(thumbPixels.first() - (thumbCenter - thumbHalfWidth)) <= 2f)
        assertTrue("进度圆点右边缘被裁切: progress=$progress pixels=$thumbPixels",
            abs((thumbPixels.last() + 1) - (thumbCenter + thumbHalfWidth)) <= 2f)
        val thumbBottom = ceil(radius * 2f).toInt() + 1
        for (y in thumbBottom until pixels.height) {
            assertTrue("进度圆点未完整留在顶部区域: progress=$progress dragging=$dragging y=$y",
                (0 until pixels.width).none { x -> isProgressPrimary(pixels[x, y]) })
        }

        val viewportPixels = composeRule.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
        val left = playerBounds.left - viewportBounds.left
        val right = playerBounds.right - viewportBounds.left
        val top = playerBounds.top - viewportBounds.top
        val progressBottom = progressBounds.bottom - viewportBounds.top
        val padding = ceil(8f * pixelsPerDp).toInt()
        for (y in maxOf(0, floor(top).toInt() - padding) until minOf(viewportPixels.height, ceil(progressBottom).toInt())) {
            for (x in maxOf(0, floor(left).toInt() - padding) until minOf(viewportPixels.width, ceil(right).toInt() + padding)) {
                if (x < floor(left).toInt() - 1 || x > ceil(right).toInt() || y < floor(top).toInt() - 1) {
                    assertTrue("进度绘制超出玻璃区域: progress=$progress dragging=$dragging x=$x y=$y",
                        viewportPixels[x, y].red < 0.03f)
                }
            }
        }
    }

    private fun isProgressPrimary(color: Color): Boolean =
        color.red > 0.05f && color.blue > 0.05f && color.green < minOf(color.red, color.blue) * 0.35f

    private fun exerciseEveryAction(layout: TabletMiniPlayerLayout) {
        val expected = mutableListOf<Action>()
        Action.entries.filter { it != Action.Expand }.forEach { action ->
            clickAction(action, layout)
            expected += action
            composeRule.runOnIdle {
                assertEquals(expected, calls)
                assertTrue(seekRequests.isEmpty())
            }
        }
        clickAction(Action.Expand, layout)
        composeRule.runOnIdle { assertEquals(expected + Action.Expand, calls) }
    }

    private fun clickAction(action: Action, layout: TabletMiniPlayerLayout) {
        if (isDirect(action, layout)) composeRule.onNodeWithTag(action.tag).performTouchInput { click() }
        else {
            composeRule.onNodeWithTag(OverflowTag).performTouchInput { click() }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            composeRule.onNodeWithText(context.getString(action.description)).assertIsDisplayed()
                .performTouchInput { click() }
        }
    }

    private fun isDirect(action: Action, layout: TabletMiniPlayerLayout): Boolean = when (layout) {
        TabletMiniPlayerLayout.Full -> true
        TabletMiniPlayerLayout.Compact -> action in listOf(Action.Previous, Action.PlayPause, Action.Next,
            Action.Volume, Action.ListenTogether, Action.Queue)
        TabletMiniPlayerLayout.Overflow -> action in listOf(Action.Previous, Action.PlayPause, Action.Next)
        TabletMiniPlayerLayout.Minimal -> action == Action.PlayPause
    }

    private fun setProgress(value: Float) {
        progressNode().performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(value))
        }
    }

    private fun assertProgress(value: Float) {
        val info = progressNode().fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(value, info.current, 0.02f)
        assertEquals(0f..1f, info.range)
    }

    private fun assertDisabledProgress() {
        progressNode().assertIsNotEnabled()
        assertNull(progressNode().fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
    }

    private fun startDrag() {
        progressNode().performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.6f, centerY), delayMillis = 100L)
        }
        assertProgress(0.6f)
    }

    private fun finishInterruptedDrag() {
        progressNode().performTouchInput { up() }
    }

    private fun assertNoPlaybackCallbacks() {
        composeRule.runOnIdle {
            assertTrue("进度操作误触了其它动作: $calls", calls.isEmpty())
            assertTrue("已取消或禁用的进度仍发起 seek: $seekRequests", seekRequests.isEmpty())
        }
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun progressNode(): SemanticsNodeInteraction =
        composeRule.onNodeWithTag(ProgressTag, useUnmergedTree = true)

    private fun assertContained(child: Rect, parent: Rect) {
        assertTrue("控件越过 MiniPlayer 边界: $child/$parent", child.left >= parent.left - 1f &&
            child.right <= parent.right + 1f && child.top >= parent.top - 1f && child.bottom <= parent.bottom + 1f)
    }

    private fun capture(stage: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank) ?: "tablet-mini"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "$prefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag(ViewportTag).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("TabletMiniPlayerLayoutTest", "screenshot=${file.absolutePath}")
    }

    private data class Playback(
        val trackKey: String = "track-a",
        val positionMs: Long = 30_000L,
        val durationMs: Long = 120_000L,
        val seekEnabled: Boolean = true
    )

    private data class SeekRequest(val trackKey: String, val positionMs: Long)

    private enum class Action(val tag: String, val description: Int) {
        Shuffle("miniPlayerShuffle", CoreCommonR.string.player_shuffle),
        Previous("miniPlayerPrevious", CoreCommonR.string.player_previous),
        PlayPause("miniPlayerPlayPause", CoreCommonR.string.lyrics_play),
        Next("miniPlayerNext", CoreCommonR.string.player_next),
        Repeat("miniPlayerRepeat", CoreCommonR.string.player_repeat),
        Volume("miniPlayerVolume", CoreCommonR.string.mini_player_volume),
        ListenTogether("miniPlayerListenTogether", CoreCommonR.string.listen_together_title),
        Queue("miniPlayerQueue", CoreCommonR.string.playlist_queue),
        Expand("miniPlayerExpand", CoreCommonR.string.player_now_playing)
    }

    private companion object {
        const val PlayerTag = "tabletMiniPlayerFixture"
        const val ViewportTag = "tabletMiniPlayerViewport"
        const val MetadataTag = "miniPlayerMetadata"
        const val ProgressTag = "miniPlayerProgress"
        const val OverflowTag = "miniPlayerOverflow"
        const val Title = "很长的歌曲标题，在紧凑布局中仍然保留歌曲信息"
        const val Artist = "演唱者名称"
        val ProgressPixelColors = lightColorScheme(
            primary = Color.Magenta,
            primaryContainer = Color.DarkGray,
            secondaryContainer = Color.Black,
            onSecondaryContainer = Color.White
        )
    }
}
