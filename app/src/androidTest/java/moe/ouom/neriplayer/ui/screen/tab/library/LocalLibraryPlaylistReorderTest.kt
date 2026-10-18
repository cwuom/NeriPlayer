package moe.ouom.neriplayer.ui.screen.tab.library

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLibraryPlaylistReorderTest {
    @get:Rule
    val compose = createComposeRule()

    private val playlists = mutableStateOf(emptyList<LocalPlaylist>())
    private val savedOrders = mutableListOf<List<Long>>()
    private lateinit var listState: LazyListState

    @Before
    fun requireUnlockedEmulator() {
        assumeComposeHostAvailable()
    }

    @Test
    fun asynchronouslyLoadedPlaylistsReorderWithTheHeldRowAndSaveTheCurrentOrder() {
        showLoadedPlaylists()
        compose.onNodeWithText("Reorder second").performTouchInput { longClick() }
        compose.waitForIdle()
        val list = compose.onNode(hasScrollToIndexAction())
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val handle = playlistHandle("Reorder second")
        val heldBounds = handle.fetchSemanticsNode().boundsInRoot
        val nextBounds = playlistHandle("Reorder third").fetchSemanticsNode().boundsInRoot
        val start = heldBounds.center - listBounds.topLeft
        // 启动时会重新记录抓取点，后续位移必须明确越过下一行，不能刚好停在边界
        val target = Offset(start.x, nextBounds.center.y - listBounds.top + nextBounds.height * 2)
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(start + Offset(0f, heldBounds.height))
            }
            advanceFrames(2)
            list.performTouchInput { moveTo(target) }
            advanceFrames(12)
            compose.runOnIdle {
                assertEquals(
                    "the current repository snapshot must be reordered while the pointer is held",
                    listOf(11L, 33L, 22L, 44L),
                    listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }
                )
                assertTrue("the order must only be saved on release", savedOrders.isEmpty())
            }
            val movingHandle = handle.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "the held playlist must follow the pointer instead of jumping to its final slot",
                movingHandle.center.y > nextBounds.center.y
            )
            saveDragScreenshot("held")
            list.performTouchInput {
                up()
                pointerDown = false
            }
            advanceFrames(2)
            val releasingHandle = handle.fetchSemanticsNode().boundsInRoot
            saveDragScreenshot("releasing")
            advanceFrames(38)
            compose.runOnIdle {
                assertEquals(listOf(listOf(11L, 33L, 22L, 44L)), savedOrders)
            }
            val settledHandle = handle.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "release must animate the held playlist back into its reordered slot",
                settledHandle.center.y < movingHandle.center.y
            )
            assertTrue(
                "the release must have an intermediate frame instead of jumping to the final slot",
                releasingHandle.center.y > settledHandle.center.y + 0.5f &&
                    releasingHandle.center.y < movingHandle.center.y - 0.5f
            )
            saveDragScreenshot("settled")
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun systemBackWhileHoldingSavesTheOrderOnceAndStopsFurtherReordering() {
        showLoadedPlaylists()
        compose.onNodeWithText("Reorder second").performTouchInput { longClick() }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val list = compose.onNode(hasScrollToIndexAction())
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val heldBounds = playlistHandle("Reorder second").fetchSemanticsNode().boundsInRoot
        val nextBounds = playlistHandle("Reorder third").fetchSemanticsNode().boundsInRoot
        val start = heldBounds.center - listBounds.topLeft
        val target = Offset(start.x, nextBounds.center.y - listBounds.top + nextBounds.height * 2)
        compose.mainClock.autoAdvance = false
        var pointerDown = false
        try {
            list.performTouchInput {
                down(start)
                pointerDown = true
                moveTo(start + Offset(0f, heldBounds.height))
            }
            advanceFrames(2)
            list.performTouchInput { moveTo(target) }
            advanceFrames(12)
            compose.runOnIdle {
                assertEquals(
                    listOf(11L, 33L, 22L, 44L),
                    listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }
                )
                assertTrue(savedOrders.isEmpty())
            }

            Espresso.pressBack()
            advanceFrames(2)
            compose.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_exit_multi_select))
                .assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(
                    "exiting selection must finish and save the active drag",
                    listOf(listOf(11L, 33L, 22L, 44L)),
                    savedOrders
                )
            }
            list.performTouchInput { moveTo(target + Offset(0f, heldBounds.height * 2)) }
            advanceFrames(12)
            list.performTouchInput {
                up()
                pointerDown = false
            }
            advanceFrames(40)
            compose.runOnIdle {
                assertEquals(listOf(listOf(11L, 33L, 22L, 44L)), savedOrders)
                assertEquals(
                    listOf(11L, 33L, 22L, 44L),
                    listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }
                )
            }
        } finally {
            if (pointerDown) list.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun showLoadedPlaylists() {
        compose.setContent {
            MaterialTheme {
                FittedTestViewport(width = 440.dp, height = 900.dp) {
                    listState = rememberLazyListState()
                    LocalPlaylistList(
                        playlists = playlists.value,
                        listState = listState,
                        onCreate = {},
                        onClick = {},
                        onArtistClick = {},
                        onReorder = { savedOrders += it },
                        offlineMode = true
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            playlists.value = listOf(
                LocalPlaylist(id = 11L, name = "Reorder first"),
                LocalPlaylist(id = 22L, name = "Reorder second"),
                LocalPlaylist(id = 33L, name = "Reorder third"),
                LocalPlaylist(id = 44L, name = "Reorder fourth")
            )
        }
    }

    private fun saveDragScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "library-playlist-drag-$name.png").outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun playlistHandle(name: String) = compose.onNode(
        hasContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(CoreCommonR.string.common_drag_handle)
        ).and(
            hasAnyAncestor(hasClickAction().and(hasAnyDescendant(hasText(name))))
        ),
        useUnmergedTree = true
    )

    private fun advanceFrames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}
