package moe.ouom.neriplayer.ui.screen.playlist

import android.graphics.Bitmap
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LocalPlaylistReorderIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { LocalPlaylistRepository.getInstance(context) }
    private val screenVisible = mutableStateOf(false)
    private var ownedPlaylistId: Long? = null
    private var screenMounted = false
    private var nearEdgeDistancePx = 0f
    private var partialRowTargetDistancePx = 0f
    private var dragSlopDistancePx = 0f
    private var pastBottomBoundaryPx = 0f
    private var frameTolerancePx = 0f

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun removeOwnedPlaylist() {
        composeRule.mainClock.autoAdvance = true
        val playlistId = ownedPlaylistId ?: return
        runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                repository.deletePlaylist(playlistId)
            }
        }
        if (screenMounted) {
            // 先让生产页面按歌单删除退出，避免 onDispose 又写入测试歌单的使用记录
            composeRule.waitUntil(UiTimeoutMs) { !screenVisible.value }
        }
        AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
    }

    @Test
    fun holdingTheRealSongHandleInsideTheListTopContinuesScrollingAndPersistsItsMove() {
        verifyEdgeHold(EdgeTarget.InsideList)
    }

    @Test
    fun holdingTheRealSongHandleAtTheListTopEdgeContinuesScrollingAndPersistsItsMove() {
        verifyEdgeHold(EdgeTarget.ListEdge)
    }

    @Test
    fun holdingTheRealSongHandleOverTheTopBarContinuesScrollingAndPersistsItsMove() {
        verifyEdgeHold(EdgeTarget.TopBar)
    }

    @Test
    fun holdingThePartiallyVisibleFirstSongAfterDownwardActivationScrollsTowardTheBeginning() {
        verifyEdgeHold(EdgeTarget.InsideList, partiallyVisibleStart = true)
    }

    @Test
    fun holdingTheRealSongHandleJustPastTheListBottomContinuesScrollingAndPersistsItsMove() {
        verifyEdgeHold(EdgeTarget.PastBottomBoundary)
    }

    @Test
    fun holdingTheRealSongHandleFarPastTheListBottomContinuesScrollingAndPersistsItsMove() {
        verifyEdgeHold(EdgeTarget.FarPastBottomBoundary)
    }

    @Test
    fun holdingTheRealSongHandleOverTheTopBarKeepsTheHeldRowStableOnEveryFrame() {
        verifyEdgeHold(EdgeTarget.TopBar, verifySmoothness = true)
    }

    @Test
    fun holdingTheRealSongHandleAtTheListTopEdgeKeepsTheHeldRowStableOnEveryFrame() {
        verifyEdgeHold(EdgeTarget.ListEdge, verifySmoothness = true)
    }

    @Test
    fun ordinaryPlaylistAcceptsThreeRapidUpwardSwipesAndContinuesWithInertia() {
        val songs = createAndShowPlaylist()
        val originalOrder = songs.map { it.id }
        val list = playlistList()
        list.performScrollToIndex(FirstVisibleSongIndex + FixedHeaderCount)
        val rowHeightPx = songRow(songs[DraggedSongIndex]).fetchSemanticsNode().boundsInRoot.height
        assertTrue("song rows cannot be decoded with the current scroll semantics", rowHeightPx < LazyItemSemanticsStep)
        val frames = mutableListOf<ScrollFrameSample>()
        fun sample(phase: String) {
            val position = scrollPosition()
            frames += ScrollFrameSample(
                phase = phase,
                testClockTimeMs = composeRule.mainClock.currentTime,
                scrollPosition = position,
                songScrollPixels = songScrollPixels(position, rowHeightPx)
            )
        }
        composeRule.mainClock.autoAdvance = false
        try {
            sample("initial")
            repeat(3) { swipeIndex ->
                val before = scrollPosition()
                list.performTouchInput {
                    swipe(
                        start = Offset(center.x, height * 0.7f),
                        end = Offset(center.x, height * 0.3f),
                        durationMillis = 120
                    )
                }
                repeat(2) {
                    advanceFrames(1)
                    sample("swipe-$swipeIndex")
                }
                assertTrue(
                    "ordinary upward swipe $swipeIndex did not advance the playlist: $frames",
                    scrollPosition() > before
                )
                assertEquals("ordinary scrolling changed the song order", originalOrder, currentOrder())
            }
            val beforeInertia = scrollPosition()
            repeat(30) { frame ->
                advanceFrames(1)
                sample("inertia-$frame")
            }
            assertTrue(
                "ordinary upward release did not continue with inertia: $frames",
                scrollPosition() > beforeInertia
            )
            frames.zipWithNext().forEach { (previous, current) ->
                assertTrue(
                    "ordinary upward scrolling reversed or jumped back: $previous -> $current",
                    current.songScrollPixels >= previous.songScrollPixels - ScrollTolerancePx
                )
            }
            assertEquals(originalOrder, currentOrder())
        } finally {
            File(context.cacheDir, "playlist-ordinary-upward-swipe-frames.txt").writeText(
                "timestamps use the Compose test clock, not rendered frame timing\n" + frames.joinToString("\n")
            )
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun verifyEdgeHold(
        target: EdgeTarget,
        partiallyVisibleStart: Boolean = false,
        verifySmoothness: Boolean = false
    ) {
        val upward = target != EdgeTarget.PastBottomBoundary && target != EdgeTarget.FarPastBottomBoundary
        val songs = createAndShowPlaylist()
        val originalOrder = songs.map { it.id }
        val draggedSongIndex = if (partiallyVisibleStart) FirstVisibleSongIndex else DraggedSongIndex
        val draggedSong = songs[draggedSongIndex]
        playlistList().performScrollToIndex(FirstVisibleSongIndex + FixedHeaderCount)
        songRow(draggedSong).assertIsDisplayed()
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
        if (partiallyVisibleStart) {
            val rowHeight = songRow(draggedSong)
                .fetchSemanticsNode().boundsInRoot.height
            playlistList().performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                scrollBy(0f, rowHeight * 0.45f)
            }
            composeRule.waitForIdle()
            val rowTop = songRow(draggedSong).getUnclippedBoundsInRoot().top
            val listTop = playlistList().getUnclippedBoundsInRoot().top
            assertTrue("fixture did not produce a partially visible first song: row=$rowTop, list=$listTop", rowTop < listTop)
        }

        val root = composeRule.onRoot(useUnmergedTree = true)
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val listBounds = playlistList().fetchSemanticsNode().boundsInRoot
        val restingRowBounds = songRow(draggedSong).fetchSemanticsNode().boundsInRoot
        val handle = songHandle(draggedSong)
        handle.assertIsDisplayed()
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        assertTrue("real handle was outside its playlist viewport", listBounds.contains(handleBounds.center))
        val start = handleBounds.center - rootBounds.topLeft
        val slop = start + Offset(
            0f,
            if (partiallyVisibleStart || !upward) dragSlopDistancePx else -dragSlopDistancePx
        )
        val destination = Offset(
            x = start.x,
            y = when (target) {
                EdgeTarget.InsideList -> listBounds.top + if (partiallyVisibleStart) {
                    partialRowTargetDistancePx
                } else {
                    nearEdgeDistancePx
                }
                EdgeTarget.ListEdge -> listBounds.top + 1f
                EdgeTarget.TopBar -> (rootBounds.top + listBounds.top) / 2f
                EdgeTarget.PastBottomBoundary -> listBounds.bottom + pastBottomBoundaryPx
                EdgeTarget.FarPastBottomBoundary -> listBounds.bottom + nearEdgeDistancePx
            } - rootBounds.top
        )
        val samples = mutableListOf(scrollPosition())
        val frameSamples = mutableListOf<DragFrameSample>()
        val diagnostics = "target=$target, partiallyVisibleStart=$partiallyVisibleStart, " +
            "start=$start, destination=$destination, " +
            "handleBounds=$handleBounds, listBounds=$listBounds"
        if (partiallyVisibleStart) {
            assertTrue("partial-row fixture did not preserve the initial downward direction: $diagnostics", destination.y >= start.y)
        }
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            // 从真实手柄命中路径开始，移到列表外时仍把同一根手指交给生产拖拽逻辑
            root.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(slop, delayMillis = 16)
            }
            advanceFrames(2)
            if (verifySmoothness) {
                // 先让抓取时的正常放大动画稳定，之后只观察手指静止时的重排和滚动
                advanceFrames(60)
                val settledRow = captureDragFrame(draggedSong, frame = 0)
                assertEquals("the real handle did not retain one row after activation: $settledRow", 1, settledRow.rowNodeCount)
                assertTrue("the activated row was not placed: $settledRow", settledRow.rowPlaced)
                assertEquals("activation unexpectedly scrolled the fixture: $settledRow", samples[0], settledRow.scrollPosition, ScrollTolerancePx)
                assertEquals("activation reordered the fixture before the top hold", originalOrder, currentOrder())
            }
            root.performTouchInput { moveTo(destination, delayMillis = 16) }
            if (partiallyVisibleStart) {
                advanceFrames(1)
                songRow(draggedSong).assertIsDisplayed()
            }
            repeat(3) { stage ->
                if (verifySmoothness) {
                    repeat(60) { frame ->
                        advanceFrames(1)
                        frameSamples += captureDragFrame(draggedSong, stage * 60 + frame + 1)
                    }
                } else {
                    advanceFrames(60)
                }
                samples += scrollPosition()
                saveDragScreenshot("$target-$partiallyVisibleStart-${stage + 1}")
                assertTrue(
                    "drag was committed while the pointer was still down: samples=$samples, $diagnostics",
                    originalOrder == currentOrder()
                )
                if (!verifySmoothness) {
                    try {
                        songRow(draggedSong).assertIsDisplayed()
                    } catch (failure: AssertionError) {
                        val row = composeRule.onAllNodesWithText(draggedSong.name)
                            .fetchSemanticsNodes().singleOrNull()
                        throw AssertionError(
                            "held song disappeared: bounds=${row?.boundsInRoot}, " +
                                "samples=$samples, $diagnostics",
                            failure
                        )
                    }
                }
            }
            if (verifySmoothness) {
                assertSmoothDragFrames(frameSamples, listBounds, restingRowBounds, diagnostics)
                if (target == EdgeTarget.TopBar) {
                    assertSaturatedTopHoldCadence(frameSamples, restingRowBounds.height, diagnostics)
                }
            }
            assertTrue(
                "real handle hold did not scroll in the intended direction: samples=$samples, $diagnostics",
                if (upward) samples[1] < samples[0] else samples[1] > samples[0]
            )
            for (index in 2 until samples.size) {
                assertTrue(
                    "real handle scrolling stopped while held: samples=$samples, $diagnostics",
                    if (upward) {
                        samples[index] < samples[index - 1] || samples[index] <= ScrollTolerancePx
                    } else {
                        samples[index] > samples[index - 1] ||
                            abs(samples[index] - playlistList().fetchSemanticsNode()
                                .config[VerticalScrollAxisRange].maxValue()) <= ScrollTolerancePx
                    }
                )
            }
            assertEquals("drag order was persisted before the pointer was released: $diagnostics", originalOrder, currentOrder())
            root.performTouchInput {
                up()
                pointerDown = false
            }
            advanceFrames(2)
            composeRule.mainClock.autoAdvance = true
            composeRule.waitUntil(RepositoryTimeoutMs) { currentOrder() != originalOrder }
            val committedOrder = currentOrder()
            assertEquals("songs disappeared or duplicated after the real drag", originalOrder.size, committedOrder.size)
            assertEquals(originalOrder.toSet(), committedOrder.toSet())
            assertEquals(committedOrder.size, committedOrder.distinct().size)
            assertTrue(
                "the held song was not moved in the intended direction: before=$draggedSongIndex, " +
                    "after=${committedOrder.indexOf(draggedSong.id)}, samples=$samples, $diagnostics",
                if (upward) committedOrder.indexOf(draggedSong.id) < draggedSongIndex
                else committedOrder.indexOf(draggedSong.id) > draggedSongIndex
            )
            val persistedOrder = runBlocking {
                withTimeout(RepositoryTimeoutMs) {
                    repository.readFastPlaylist(requireNotNull(ownedPlaylistId))?.songs?.map { it.id }
                }
            }
            assertEquals("authoritative persisted order did not match the released drag", committedOrder, persistedOrder)
        } finally {
            if (verifySmoothness) saveDragFrameSamples(target, frameSamples, restingRowBounds.height)
            if (pointerDown) root.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun createAndShowPlaylist(): List<SongItem> {
        val token = UUID.randomUUID().toString().take(8)
        val baseId = System.currentTimeMillis() * 1_000L
        val songs = List(SongCount) { index ->
            SongItem(
                id = baseId + index,
                name = "reorder-$token-song-${index + 1}",
                artist = "synthetic artist",
                album = "reorder-integration-$token",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = null
            )
        }
        val playlist = runBlocking {
            withTimeout(RepositoryTimeoutMs) {
                assertTrue("local playlist repository failed to initialize", repository.awaitInitialized())
                repository.createPlaylistWithPreparedSongs("UI$token", songs)
            }
        }
        ownedPlaylistId = playlist.id
        screenVisible.value = true
        composeRule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides AdvancedGlassOverscrollFactory) {
                MaterialTheme {
                    with(LocalDensity.current) {
                        nearEdgeDistancePx = 40.dp.toPx()
                        partialRowTargetDistancePx = 36.dp.toPx()
                        dragSlopDistancePx = 24.dp.toPx()
                        pastBottomBoundaryPx = 4.dp.toPx()
                        frameTolerancePx = 1.dp.toPx()
                    }
                    val navController = rememberNavController()
                    NavHost(navController, startDestination = FixtureRoute) {
                        composable(FixtureRoute) {
                            if (screenVisible.value) {
                                LocalPlaylistDetailScreen(
                                    playlistId = playlist.id,
                                    onBack = { screenVisible.value = false },
                                    onDeleted = { screenVisible.value = false },
                                    offlineMode = true
                                )
                            }
                        }
                    }
                }
            }
        }
        screenMounted = true
        composeRule.waitUntil(UiTimeoutMs) {
            composeRule.onAllNodesWithText(playlist.name).fetchSemanticsNodes().isNotEmpty()
        }
        return playlist.songs
    }

    private fun songRow(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(
        songRowMatcher(song),
        useUnmergedTree = true
    )

    private fun songRowMatcher(song: SongItem): SemanticsMatcher =
        hasClickAction().and(hasAnyDescendant(hasText(song.name)))

    private fun songHandle(song: SongItem): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription(context.getString(CoreCommonR.string.common_drag_handle)).and(
            hasAnyAncestor(hasClickAction().and(hasAnyDescendant(hasText(song.name))))
        ),
        useUnmergedTree = true
    )

    private fun playlistList(): SemanticsNodeInteraction = composeRule.onNode(
        hasScrollToIndexAction().and(SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange))
    )

    private fun scrollPosition(): Float =
        playlistList().fetchSemanticsNode().config[VerticalScrollAxisRange].value()

    private fun saveDragScreenshot(name: String) {
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "playlist-drag-$name.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun captureDragFrame(song: SongItem, frame: Int): DragFrameSample {
        val nodes = composeRule.onAllNodes(songRowMatcher(song), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val node = nodes.singleOrNull()
        val actualBounds = node?.let {
            // 同时记录未裁剪布局位置和实际可见范围，避免裁剪把离屏跳动藏在视口边缘
            Rect(
                left = it.positionInRoot.x,
                top = it.positionInRoot.y,
                right = it.positionInRoot.x + it.size.width,
                bottom = it.positionInRoot.y + it.size.height
            )
        }
        return DragFrameSample(
            frame = frame,
            testClockTimeMs = composeRule.mainClock.currentTime,
            rowNodeCount = nodes.size,
            rowPlaced = node?.layoutInfo?.isPlaced == true,
            actualBounds = actualBounds,
            visibleBounds = node?.boundsInRoot,
            scrollPosition = scrollPosition()
        )
    }

    private fun assertSmoothDragFrames(
        samples: List<DragFrameSample>,
        listBounds: Rect,
        restingRowBounds: Rect,
        diagnostics: String
    ) {
        assertEquals("the smoothness fixture did not sample all held frames: $diagnostics", 180, samples.size)
        samples.forEachIndexed { index, sample ->
            val nearbyFrames = samples.subList((index - 4).coerceAtLeast(0), (index + 5).coerceAtMost(samples.size))
                .joinToString("\n")
            val detail = "tolerance=${frameTolerancePx}px, $diagnostics\n$nearbyFrames"
            assertEquals("held row was missing or duplicated on frame ${sample.frame}: $detail", 1, sample.rowNodeCount)
            assertTrue("held row was not placed on frame ${sample.frame}: $detail", sample.rowPlaced)
            val actualBounds = requireNotNull(sample.actualBounds)
            val visibleBounds = requireNotNull(sample.visibleBounds)
            assertTrue(
                "the top hold did not keep a full stationary row in the viewport on frame ${sample.frame}: $detail",
                abs(visibleBounds.top - listBounds.top) <= frameTolerancePx &&
                    abs(actualBounds.width - restingRowBounds.width) <= frameTolerancePx &&
                    abs(actualBounds.height - restingRowBounds.height) <= frameTolerancePx &&
                    visibleBounds.height >= restingRowBounds.height - frameTolerancePx &&
                    visibleBounds.width >= restingRowBounds.width - frameTolerancePx
            )
            val previous = samples.getOrNull(index - 1) ?: return@forEachIndexed
            val previousBounds = requireNotNull(previous.actualBounds)
            val previousVisibleBounds = requireNotNull(previous.visibleBounds)
            assertTrue(
                "held row jumped between frames ${previous.frame} and ${sample.frame}: $detail",
                abs(actualBounds.top - previousBounds.top) <= frameTolerancePx &&
                    abs(actualBounds.bottom - previousBounds.bottom) <= frameTolerancePx &&
                    abs(actualBounds.left - previousBounds.left) <= frameTolerancePx &&
                    abs(actualBounds.right - previousBounds.right) <= frameTolerancePx &&
                    abs(visibleBounds.top - previousVisibleBounds.top) <= frameTolerancePx &&
                    abs(visibleBounds.bottom - previousVisibleBounds.bottom) <= frameTolerancePx &&
                    abs(visibleBounds.left - previousVisibleBounds.left) <= frameTolerancePx &&
                    abs(visibleBounds.right - previousVisibleBounds.right) <= frameTolerancePx
            )
            assertTrue(
                "upward scrolling jumped backward between frames ${previous.frame} and ${sample.frame}: $detail",
                sample.scrollPosition <= previous.scrollPosition + frameTolerancePx
            )
        }
    }

    private fun assertSaturatedTopHoldCadence(
        samples: List<DragFrameSample>,
        rowHeightPx: Float,
        diagnostics: String
    ) {
        val deltas = cadenceDeltas(samples, rowHeightPx)
        val stops = cadenceStops(deltas)
        val summary = cadenceSummary(samples, rowHeightPx)
        assertTrue("saturated top hold never sampled the song area: $summary, $diagnostics", deltas.isNotEmpty())
        assertTrue(
            "saturated top hold reversed while still away from the playlist start: $summary, $diagnostics",
            deltas.all { it.progressPx >= 0f }
        )
        assertTrue(
            "saturated top hold repeatedly froze after activation: $summary, $diagnostics\n" +
                deltas.take(40).joinToString("\n"),
            stops.all { it.stoppedFrames <= 2 }
        )
    }

    private fun songScrollPixels(position: Float, rowHeightPx: Float): Float {
        val index = (position / LazyItemSemanticsStep).toInt()
        val offset = position - index * LazyItemSemanticsStep
        return index * rowHeightPx + offset
    }

    private fun cadenceDeltas(samples: List<DragFrameSample>, rowHeightPx: Float): List<CadenceDelta> =
        samples.zipWithNext().mapNotNull { (previous, current) ->
            val previousIndex = (previous.scrollPosition / LazyItemSemanticsStep).toInt()
            val currentIndex = (current.scrollPosition / LazyItemSemanticsStep).toInt()
            if (current.frame <= CadenceStartupFrames ||
                previousIndex < FixedHeaderCount || currentIndex < FixedHeaderCount
            ) {
                null
            } else {
                CadenceDelta(
                    frame = current.frame,
                    elapsedTestClockMs = current.testClockTimeMs - previous.testClockTimeMs,
                    progressPx = songScrollPixels(previous.scrollPosition, rowHeightPx) -
                        songScrollPixels(current.scrollPosition, rowHeightPx)
                )
            }
        }

    private fun cadenceStops(deltas: List<CadenceDelta>): List<CadenceStop> = buildList {
        var firstFrame = 0
        var stoppedFrames = 0
        var lastFrame = 0
        deltas.forEach { delta ->
            if (delta.progressPx == 0f) {
                if (stoppedFrames == 0) firstFrame = delta.frame
                stoppedFrames += 1
            } else if (stoppedFrames > 0) {
                add(CadenceStop(firstFrame, lastFrame, stoppedFrames))
                stoppedFrames = 0
            }
            lastFrame = delta.frame
        }
        if (stoppedFrames > 0) add(CadenceStop(firstFrame, lastFrame, stoppedFrames))
    }

    private fun cadenceSummary(samples: List<DragFrameSample>, rowHeightPx: Float): String {
        val deltas = cadenceDeltas(samples, rowHeightPx)
        val speeds = deltas.map { it.progressPx * 1_000f / it.elapsedTestClockMs.coerceAtLeast(1L) }
        val changes = deltas.zipWithNext().map { (previous, current) ->
            abs(current.progressPx - previous.progressPx)
        }
        return "Compose test clock cadence, not rendered FPS: sampledFrames=${samples.size}, " +
            "startupFrames=$CadenceStartupFrames, rowHeightPx=$rowHeightPx, songAreaDeltas=${deltas.size}, " +
            "stops=${cadenceStops(deltas)}, reverseFrames=${deltas.filter { it.progressPx < 0f }.map { it.frame }}, " +
            "progressPx=${deltas.minOfOrNull { it.progressPx }}..${deltas.maxOfOrNull { it.progressPx }}, " +
            "meanProgressPx=${deltas.map { it.progressPx }.average()}, " +
            "testClockSpeedPxPerSecond=${speeds.minOrNull()}..${speeds.maxOrNull()}, " +
            "maxAdjacentProgressChangePx=${changes.maxOrNull()}"
    }

    private fun saveDragFrameSamples(target: EdgeTarget, samples: List<DragFrameSample>, rowHeightPx: Float) {
        File(context.cacheDir, "playlist-drag-frames-$target.txt").writeText(
            cadenceSummary(samples, rowHeightPx) + "\n" + samples.joinToString("\n")
        )
    }

    private fun currentOrder(): List<Long> =
        repository.playlists.value.single { it.id == ownedPlaylistId }.songs.map { it.id }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private enum class EdgeTarget {
        InsideList,
        ListEdge,
        TopBar,
        PastBottomBoundary,
        FarPastBottomBoundary
    }

    private data class DragFrameSample(
        val frame: Int,
        val testClockTimeMs: Long,
        val rowNodeCount: Int,
        val rowPlaced: Boolean,
        val actualBounds: Rect?,
        val visibleBounds: Rect?,
        val scrollPosition: Float
    )

    private data class ScrollFrameSample(
        val phase: String,
        val testClockTimeMs: Long,
        val scrollPosition: Float,
        val songScrollPixels: Float
    )

    private data class CadenceDelta(
        val frame: Int,
        val elapsedTestClockMs: Long,
        val progressPx: Float
    )

    private data class CadenceStop(val firstFrame: Int, val lastFrame: Int, val stoppedFrames: Int)

    private companion object {
        const val FixtureRoute = "reorder-integration"
        const val SongCount = 120
        const val FirstVisibleSongIndex = 57
        const val DraggedSongIndex = 59
        const val FixedHeaderCount = 2
        const val LazyItemSemanticsStep = 500f
        const val CadenceStartupFrames = 5
        const val ScrollTolerancePx = 1f
        const val UiTimeoutMs = 5_000L
        const val RepositoryTimeoutMs = 10_000L
    }
}
