package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory
import moe.ouom.neriplayer.ui.screen.playlist.reorder.LocalPlaylistReorderState
import moe.ouom.neriplayer.ui.screen.playlist.reorder.LocalPlaylistReorderableItem
import moe.ouom.neriplayer.ui.screen.playlist.reorder.localPlaylistDetectReorder
import moe.ouom.neriplayer.ui.screen.playlist.reorder.localPlaylistReorderable
import moe.ouom.neriplayer.ui.screen.playlist.reorder.rememberLocalPlaylistReorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistReorderGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var reorderState: LocalPlaylistReorderState
    private val songKeys = mutableStateListOf<String>()
    private val moveTargets = mutableListOf<Any?>()
    private var nearEdgeDistancePx = 0f
    private var scrollThresholdPx = 0f

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun holdingHandleAtTopScrollsUpAndKeepsSongAboveFixedItems() {
        verifyEdgeScroll(upward = true)
    }

    @Test
    fun holdingHandleAtBottomContinuesScrollingDownWithSameSong() {
        verifyEdgeScroll(upward = false)
    }

    @Test
    fun holdingHandleNearVisibleTopStartsScrollingAfterLongPress() {
        verifyEdgeScroll(upward = true, insideEdge = true)
    }

    @Test
    fun holdingHandlePastTheBottomBoundaryStartsScrollingDownAfterLongPress() {
        verifyEdgeScroll(upward = false, insideEdge = true)
    }

    @Test
    fun holdingTheSameHandleCanReverseFromTopToBottomWithoutRestartingTheDrag() {
        verifyEdgeScroll(upward = true, insideEdge = true, reverseAfterFirstHold = true)
    }

    @Test
    fun releasingThenImmediatelyDraggingTheSameHandleTowardTheOppositeEdgeStillWorks() {
        showPlaylist()
        var draggingKey = ""
        var originalKeys = emptyList<String>()
        composeRule.runOnIdle {
            originalKeys = songKeys.toList()
            draggingKey = reorderState.listState.layoutInfo.visibleItemsInfo
                .filter { it.key !in LOCAL_PLAYLIST_FIXED_ITEM_KEYS }[2].key as String
        }
        composeRule.onNodeWithTag("row-$draggingKey").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val list = composeRule.onNodeWithTag("playlist-list")
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            repeat(2) { attempt ->
                val upward = attempt == 0
                val before = currentScrollPosition()
                var moveCountBefore = 0
                composeRule.runOnIdle { moveCountBefore = moveTargets.size }
                val listBounds = list.fetchSemanticsNode().boundsInRoot
                val handleBounds = composeRule.onNodeWithTag("handle-$draggingKey", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val start = handleBounds.center - listBounds.topLeft
                val direction = if (upward) -1f else 1f
                val edge = Offset(
                    start.x,
                    if (upward) nearEdgeDistancePx
                    else listBounds.height + nearEdgeDistancePx
                )
                list.performTouchInput {
                    down(start)
                    pointerDown = true
                    moveTo(start + Offset(0f, handleBounds.height / 3f * direction))
                }
                advanceDragFrames(2)
                assertDraggingSong(draggingKey)
                list.performTouchInput { moveTo(edge) }
                advanceDragFrames(90)
                val after = currentScrollPosition()
                assertTrue(
                    "drag $attempt did not respond toward the opposite edge: $before -> $after",
                    if (upward) {
                        after.first < before.first ||
                            after.first == before.first && after.second < before.second
                    } else {
                        after.first > before.first ||
                            after.first == before.first && after.second > before.second
                    }
                )
                assertDraggingSong(draggingKey)
                composeRule.runOnIdle {
                    assertTrue("drag $attempt never reordered a song", moveTargets.size > moveCountBefore)
                    assertFalse(moveTargets.any { it in LOCAL_PLAYLIST_FIXED_ITEM_KEYS })
                }
                list.performTouchInput {
                    up()
                    pointerDown = false
                }
                advanceDragFrames(2)
                composeRule.runOnIdle {
                    assertNull("drag $attempt retained its held key after release", reorderState.draggingItemKey)
                    assertNull(reorderState.draggingItemIndex)
                }
                assertCompleteSongs(originalKeys)
            }
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun fixedDisplayOrderDoesNotRepeatTheSameMoveAfterLayoutWaitAndPointerJitter() {
        showPlaylist(fixedDisplayOrder = true)
        var draggingKey = ""
        var targetKey = ""
        var originalKeys = emptyList<String>()
        composeRule.runOnIdle {
            originalKeys = songKeys.toList()
            val visibleSongs = reorderState.listState.layoutInfo.visibleItemsInfo
                .filter { it.key !in LOCAL_PLAYLIST_FIXED_ITEM_KEYS }
            draggingKey = visibleSongs[2].key as String
            targetKey = visibleSongs[3].key as String
        }
        val expectedOrder = originalKeys.toMutableList().apply {
            val from = indexOf(draggingKey)
            val to = indexOf(targetKey)
            add(to, removeAt(from))
        }
        composeRule.onNodeWithTag("row-$draggingKey").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val list = composeRule.onNodeWithTag("playlist-list")
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val handleBounds = composeRule.onNodeWithTag("handle-$draggingKey", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val targetBounds = composeRule.onNodeWithTag("row-$targetKey").fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - listBounds.topLeft
        val target = Offset(start.x, targetBounds.center.y - listBounds.top + targetBounds.height * 0.3f)
        val initialPosition = currentScrollPosition()
        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(start + Offset(0f, handleBounds.height / 3f))
            }
            advanceDragFrames(2)
            assertDraggingSong(draggingKey)
            list.performTouchInput { moveTo(target) }
            composeRule.runOnIdle {
                val layout = reorderState.listState.layoutInfo
                val heldItem = layout.visibleItemsInfo.first { it.key == draggingKey }
                val targetItem = layout.visibleItemsInfo.first { it.key == targetKey }
                val draggedBottom = heldItem.offset + reorderState.draggingItemTop + heldItem.size
                val targetBottom = targetItem.offset + targetItem.size
                assertTrue(
                    "the fixture did not cross the target bottom: $draggedBottom <= $targetBottom",
                    draggedBottom > targetBottom
                )
                assertEquals(
                    "the fixed-order crossing entered an edge scrolling band",
                    0f,
                    localPlaylistReorderScrollFraction(
                        pointerY = target.y,
                        visibleStart = layout.beforeContentPadding.toFloat(),
                        visibleEnd = (layout.viewportSize.height - layout.afterContentPadding).toFloat(),
                        threshold = scrollThresholdPx
                    ),
                    0f
                )
            }
            advanceDragFrames(35)
            assertDraggingSong(draggingKey)
            composeRule.runOnIdle {
                assertEquals("one target crossing must produce one move", listOf(targetKey), moveTargets)
                assertEquals("the first move did not update the source order", expectedOrder, songKeys.toList())
            }
            assertEquals("the fixture entered an edge scrolling band", initialPosition, currentScrollPosition())

            repeat(6) { sample ->
                val jitter = if (sample % 2 == 0) 1f else -1f
                list.performTouchInput { moveTo(target + Offset(jitter, jitter)) }
                advanceDragFrames(35)
                assertDraggingSong(draggingKey)
                composeRule.runOnIdle {
                    assertEquals(
                        "pointer jitter $sample repeated a move against the same displayed target",
                        listOf(targetKey),
                        moveTargets
                    )
                    assertEquals(
                        "pointer jitter $sample reversed the source order without a new target",
                        expectedOrder,
                        songKeys.toList()
                    )
                }
                assertEquals(initialPosition, currentScrollPosition())
            }
            list.performTouchInput {
                up()
                pointerDown = false
            }
            advanceDragFrames(2)
            composeRule.runOnIdle {
                assertNull(reorderState.draggingItemKey)
                assertNull(reorderState.draggingItemIndex)
                assertEquals(expectedOrder, songKeys.toList())
            }
            assertCompleteSongs(originalKeys)
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun verifyEdgeScroll(
        upward: Boolean,
        insideEdge: Boolean = false,
        reverseAfterFirstHold: Boolean = false
    ) {
        showPlaylist()
        var initialIndex = 0
        var initialOffset = 0
        var draggingKey = ""
        composeRule.runOnIdle {
            initialIndex = reorderState.listState.firstVisibleItemIndex
            initialOffset = reorderState.listState.firstVisibleItemScrollOffset
            draggingKey = reorderState.listState.layoutInfo.visibleItemsInfo
                .filter { it.key !in LOCAL_PLAYLIST_FIXED_ITEM_KEYS }[2].key as String
        }
        composeRule.onNodeWithTag("row-$draggingKey").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val list = composeRule.onNodeWithTag("playlist-list")
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val handleBounds = composeRule.onNodeWithTag("handle-$draggingKey", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - listBounds.topLeft
        val direction = if (upward) -1f else 1f
        val slopPosition = start + Offset(0f, handleBounds.height / 3f * direction)
        val edge = Offset(
            start.x,
            when {
                insideEdge && upward -> nearEdgeDistancePx
                insideEdge -> listBounds.height + nearEdgeDistancePx
                upward -> 1f
                else -> listBounds.height + scrollThresholdPx
            }
        )

        composeRule.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(slopPosition)
            }
            advanceDragFrames(2)
            assertDraggingSong(draggingKey)
            list.performTouchInput { moveTo(edge) }
            if (insideEdge) {
                composeRule.runOnIdle {
                    val item = reorderState.listState.layoutInfo.visibleItemsInfo
                        .first { it.key == draggingKey }
                    val draggedTop = item.offset + reorderState.draggingItemTop
                    assertTrue(
                        "near-edge fixture already crossed the viewport boundary",
                        draggedTop >= reorderState.listState.layoutInfo.viewportStartOffset &&
                            draggedTop + item.size <= reorderState.listState.layoutInfo.viewportEndOffset
                    )
                }
            }

            advanceDragFrames(90)
            var firstHoldIndex = 0
            var firstHoldOffset = 0
            composeRule.runOnIdle {
                firstHoldIndex = reorderState.listState.firstVisibleItemIndex
                firstHoldOffset = reorderState.listState.firstVisibleItemScrollOffset
                assertTrue(
                    "edge hold did not scroll: before=$initialIndex:$initialOffset, " +
                        "after=$firstHoldIndex:$firstHoldOffset",
                    if (upward) {
                        firstHoldIndex < initialIndex ||
                            firstHoldIndex == initialIndex && firstHoldOffset < initialOffset
                    } else {
                        firstHoldIndex > initialIndex ||
                            firstHoldIndex == initialIndex && firstHoldOffset > initialOffset
                    }
                )
            }
            assertDraggingSong(draggingKey)

            if (reverseAfterFirstHold) {
                list.performTouchInput {
                    moveTo(Offset(start.x, listBounds.height + nearEdgeDistancePx))
                }
                advanceDragFrames(2)
                assertDraggingSong(draggingKey)
            }
            advanceDragFrames(90)
            composeRule.runOnIdle {
                val secondHoldIndex = reorderState.listState.firstVisibleItemIndex
                val secondHoldOffset = reorderState.listState.firstVisibleItemScrollOffset
                assertTrue(
                    "edge scrolling stopped while the pointer was held: " +
                        "first=$firstHoldIndex:$firstHoldOffset, " +
                        "second=$secondHoldIndex:$secondHoldOffset",
                    if (upward && !reverseAfterFirstHold) {
                        secondHoldIndex < firstHoldIndex ||
                            secondHoldIndex == firstHoldIndex && secondHoldOffset < firstHoldOffset ||
                            !reorderState.listState.canScrollBackward
                    } else {
                        secondHoldIndex > firstHoldIndex ||
                            secondHoldIndex == firstHoldIndex && secondHoldOffset > firstHoldOffset ||
                            !reorderState.listState.canScrollForward
                    }
                )
                if (upward && !insideEdge && !reverseAfterFirstHold) {
                    assertFalse(
                        "upward drag did not reach the fixed playlist header",
                        reorderState.listState.canScrollBackward
                    )
                }
                assertTrue("drag never moved a song", moveTargets.isNotEmpty())
                assertFalse(
                    "a fixed playlist item became a reorder target: $moveTargets",
                    moveTargets.any { it in LOCAL_PLAYLIST_FIXED_ITEM_KEYS }
                )
            }
            assertDraggingSong(draggingKey)
            list.performTouchInput {
                up()
                pointerDown = false
            }
            advanceDragFrames(2)
            composeRule.runOnIdle {
                assertNull(reorderState.draggingItemIndex)
                assertEquals(80, songKeys.distinct().size)
            }
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun advanceDragFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun currentScrollPosition(): Pair<Int, Int> {
        var position = 0 to 0
        composeRule.runOnIdle {
            position = reorderState.listState.firstVisibleItemIndex to
                reorderState.listState.firstVisibleItemScrollOffset
        }
        return position
    }

    private fun assertCompleteSongs(originalKeys: List<String>) {
        composeRule.runOnIdle {
            assertEquals(originalKeys.size, songKeys.size)
            assertEquals(originalKeys.toSet(), songKeys.toSet())
            assertEquals(originalKeys.size, songKeys.distinct().size)
        }
    }

    private fun assertDraggingSong(key: String) {
        composeRule.runOnIdle {
            assertEquals(key, reorderState.draggingItemKey)
            assertEquals(
                "drag index no longer points at the held song",
                key,
                reorderState.listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == reorderState.draggingItemIndex }?.key
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showPlaylist(fixedDisplayOrder: Boolean = false) {
        val initialSongKeys = List(80) { "song-$it" }
        songKeys.addAll(initialSongKeys)
        composeRule.setContent {
            MaterialTheme {
                var selectionMode by remember { mutableStateOf(false) }
                with(LocalDensity.current) {
                    nearEdgeDistancePx = 44.dp.toPx()
                    scrollThresholdPx = 48.dp.toPx()
                }
                reorderState = rememberLocalPlaylistReorderState(
                    onMove = { from, to ->
                        moveTargets += to.key
                        val fromIndex = songKeys.indexOfFirst { it == from.key }
                        val toIndex = songKeys.indexOfFirst { it == to.key }
                        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                            songKeys.add(toIndex, songKeys.removeAt(fromIndex))
                        }
                    },
                    canDragOver = localPlaylistCanDragOver { true }
                )
                LaunchedEffect(Unit) { reorderState.listState.scrollToItem(12) }
                CompositionLocalProvider(
                    LocalOverscrollFactory provides AdvancedGlassOverscrollFactory
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(if (selectionMode) "Selected songs" else "Playlist")
                                },
                                windowInsets = WindowInsets.statusBars
                            )
                        }
                    ) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = reorderState.listState,
                                    contentPadding = PaddingValues(bottom = 96.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("playlist-list")
                                        .localPlaylistReorderable(reorderState)
                                ) {
                                    item(key = LOCAL_PLAYLIST_HEADER_KEY) {
                                        Box(Modifier.fillMaxWidth().height(122.dp))
                                    }
                                    item(key = LOCAL_PLAYLIST_ACTIONS_KEY) {
                                        Box(Modifier.fillMaxWidth().height(64.dp))
                                    }
                                    items(if (fixedDisplayOrder) initialSongKeys else songKeys, key = { it }) { key ->
                                        LocalPlaylistReorderableItem(state = reorderState, key = key) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(80.dp)
                                                    .testTag("row-$key")
                                                    .combinedClickable(
                                                        onClick = {},
                                                        onLongClick = { selectionMode = true }
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(key, Modifier.weight(1f))
                                                if (selectionMode) Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .testTag("handle-$key")
                                                        .localPlaylistDetectReorder(reorderState, key)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
