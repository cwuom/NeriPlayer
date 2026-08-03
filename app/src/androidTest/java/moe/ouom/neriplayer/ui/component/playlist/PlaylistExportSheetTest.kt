package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistExportSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun firstTargetPlaylistClickShowsConfirmationBeforeExporting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targetPlaylist = LocalPlaylist(id = 42L, name = "目标歌单")
        var exportedPlaylistId: Long? = null

        composeRule.setContent {
            MaterialTheme {
                Box {
                    PlaylistExportSheet(
                        title = "导出到本地歌单",
                        playlists = listOf(targetPlaylist),
                        selectedCount = 1,
                        onDismissRequest = {},
                        onCreateAndExport = {},
                        onExportToPlaylist = { playlist ->
                            exportedPlaylistId = playlist.id
                        }
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(targetPlaylist.name)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(targetPlaylist.name).performTouchInput {
            down(center)
            up()
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.playlist_batch_export_confirm_title)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(targetPlaylist.name)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertNull(exportedPlaylistId)
        }

        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(targetPlaylist.name)
                .fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(
                    context.getString(R.string.playlist_batch_export_confirm_title)
                ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(targetPlaylist.name).performTouchInput {
            down(center)
            up()
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.playlist_batch_export_confirm_title)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(R.string.playlist_batch_export_confirm_button)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(targetPlaylist.id, exportedPlaylistId)
        }
    }

    @Test
    fun bottomEdgePlaylistClickShowsConfirmationOnFirstTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val playlists = (1L..20L).map { id ->
            LocalPlaylist(id = id, name = "歌单 $id")
        }
        val targetPlaylist = playlists.last()

        composeRule.setContent {
            MaterialTheme {
                Box {
                    PlaylistExportSheet(
                        title = "导出到本地歌单",
                        playlists = playlists,
                        selectedCount = 1,
                        onDismissRequest = {},
                        onCreateAndExport = {},
                        onExportToPlaylist = {}
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(playlists.first().name)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollToIndexAction())
            .performScrollToIndex(playlists.lastIndex)
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeWithVelocity(
                start = center,
                end = center.copy(y = 0f),
                endVelocity = 2_000f
            )
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(targetPlaylist.name)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(targetPlaylist.name).performTouchInput {
            down(center)
            up()
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.playlist_batch_export_confirm_title)
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun playlistRowClickSurvivesScrollStopTapConsumption() {
        var clickCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    }
                ) {
                    Text(
                        text = "触底歌单",
                        modifier = Modifier.playlistExportRowClick(
                            enabled = true,
                            onClick = { clickCount++ }
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithText("触底歌单").performTouchInput {
            down(center)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(1, clickCount)
        }
    }
}
