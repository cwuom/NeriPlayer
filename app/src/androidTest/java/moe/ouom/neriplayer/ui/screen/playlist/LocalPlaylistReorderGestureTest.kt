package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.ReorderableLazyListState
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
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

    private lateinit var reorderState: ReorderableLazyListState
    private val songKeys = mutableStateListOf<String>()
    private val moveTargets = mutableListOf<Any?>()

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

    private fun verifyEdgeScroll(upward: Boolean) {
        showPlaylist()
        var initialIndex = 0
        var draggingKey = ""
        composeRule.runOnIdle {
            initialIndex = reorderState.listState.firstVisibleItemIndex
            draggingKey = reorderState.listState.layoutInfo.visibleItemsInfo
                .filter { it.key !in LOCAL_PLAYLIST_FIXED_ITEM_KEYS }[2].key as String
        }
        val list = composeRule.onNodeWithTag("playlist-list")
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val handleBounds = composeRule.onNodeWithTag("handle-$draggingKey")
            .fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - listBounds.topLeft
        val direction = if (upward) -1f else 1f
        val slopPosition = start + Offset(0f, handleBounds.height / 3f * direction)
        val edge = Offset(start.x, if (upward) 1f else listBounds.height - 1f)

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

            advanceDragFrames(90)
            var firstHoldIndex = 0
            composeRule.runOnIdle {
                firstHoldIndex = reorderState.listState.firstVisibleItemIndex
                assertTrue(
                    "edge hold did not scroll: before=$initialIndex, after=$firstHoldIndex",
                    if (upward) firstHoldIndex < initialIndex else firstHoldIndex > initialIndex
                )
            }
            assertDraggingSong(draggingKey)

            advanceDragFrames(90)
            composeRule.runOnIdle {
                val secondHoldIndex = reorderState.listState.firstVisibleItemIndex
                assertTrue(
                    "edge scrolling stopped while the pointer was held: " +
                        "first=$firstHoldIndex, second=$secondHoldIndex",
                    if (upward) {
                        secondHoldIndex < firstHoldIndex || !reorderState.listState.canScrollBackward
                    } else {
                        secondHoldIndex > firstHoldIndex || !reorderState.listState.canScrollForward
                    }
                )
                if (upward) {
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

    private fun showPlaylist() {
        songKeys.addAll(List(80) { "song-$it" })
        composeRule.setContent {
            MaterialTheme {
                reorderState = rememberReorderableLazyListState(
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
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(72.dp))
                    LazyColumn(
                        state = reorderState.listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .testTag("playlist-list")
                            .reorderable(reorderState)
                    ) {
                        item(key = LOCAL_PLAYLIST_HEADER_KEY) {
                            Box(Modifier.fillMaxWidth().height(122.dp))
                        }
                        item(key = LOCAL_PLAYLIST_ACTIONS_KEY) {
                            Box(Modifier.fillMaxWidth().height(64.dp))
                        }
                        items(songKeys, key = { it }) { key ->
                            ReorderableItem(state = reorderState, key = key) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(64.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(key, Modifier.weight(1f))
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("handle-$key")
                                            .detectReorder(reorderState)
                                    )
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
