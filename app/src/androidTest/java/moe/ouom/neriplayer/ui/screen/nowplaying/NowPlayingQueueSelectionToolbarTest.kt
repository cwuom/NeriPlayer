package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingQueueSelectionToolbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun removeButtonDeletesOnlyTheSelectedQueueOccurrence() {
        val song = song(1)
        val other = song(2)
        val queue = mutableListOf(song, other, song)
        val entries = buildNowPlayingQueueEntries(queue)
        var selectedKeys by mutableStateOf(setOf(entries[2].key))
        composeRule.setContent {
            MaterialTheme {
                NowPlayingQueueSelectionToolbar(
                    selectedCount = selectedKeys.size,
                    allSelected = false,
                    canExport = selectedKeys.isNotEmpty(),
                    canRemove = selectedKeys.isNotEmpty(),
                    onSelectAll = {},
                    onRemove = {
                        removeSelectedNowPlayingQueueEntries(entries, selectedKeys) { indices ->
                            indices.sortedDescending().forEach(queue::removeAt)
                        }
                        selectedKeys = emptySet()
                    },
                    onInvertSelection = {},
                    onExport = {},
                    onExitSelection = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.nowplaying_queue_remove))
            .assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf(song, other), queue) }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.nowplaying_queue_remove))
            .assertIsNotEnabled()
    }

    @Test
    fun emptySelectionKeepsSelectAllAvailableAndRemovalDisabled() {
        var selectAllCalls = 0
        var removeCalls = 0
        composeRule.setContent {
            MaterialTheme {
                NowPlayingQueueSelectionToolbar(
                    selectedCount = 0,
                    allSelected = false,
                    canExport = false,
                    canRemove = false,
                    onSelectAll = { selectAllCalls++ },
                    onRemove = { removeCalls++ },
                    onInvertSelection = {},
                    onExport = {},
                    onExitSelection = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.nowplaying_queue_remove))
            .assertIsNotEnabled().performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_select_all))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectAllCalls)
            assertEquals(0, removeCalls)
        }
    }

    @Test
    fun narrowToolbarKeepsRemovalBesideSelectAllWithoutOverlappingOtherActions() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    NowPlayingQueueSelectionToolbar(
                        selectedCount = 123,
                        allSelected = false,
                        canExport = true,
                        canRemove = true,
                        onSelectAll = {},
                        onRemove = {},
                        onInvertSelection = {},
                        onExport = {},
                        onExitSelection = {}
                    )
                }
            }
        }

        val selectAll = composeRule.onNodeWithContentDescription(
            context.getString(CoreCommonR.string.action_select_all)
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val remove = composeRule.onNodeWithContentDescription(
            context.getString(CoreCommonR.string.nowplaying_queue_remove)
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val export = composeRule.onNodeWithContentDescription(
            context.getString(CoreCommonR.string.cd_export_playlist)
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

        assertTrue("select all and removal overlap", selectAll.right <= remove.left)
        assertTrue("removal and export overlap", remove.right <= export.left)
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = id,
        durationMs = 1_000L,
        coverUrl = null
    )
}
