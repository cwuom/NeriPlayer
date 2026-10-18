package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistSelectionMoreMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun secondaryActionsStayCollapsedAndInsertDispatchesOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var insertCount = 0
        composeRule.setContent {
            MaterialTheme {
                PlaylistSelectionActions(
                    allSelected = false,
                    onToggleSelectAll = {},
                    canExport = true,
                    onExport = {},
                    canSync = true,
                    onSync = {},
                    canInsert = true,
                    onInsert = { insertCount += 1 }
                )
            }
        }
        val insertText = context.getString(CoreCommonR.string.playlist_insert_action)
        composeRule.onNodeWithText(insertText).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_select_all))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_export_playlist))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_select_all))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.cd_export_playlist))
            .assertDoesNotExist()
        composeRule.onNodeWithText(insertText).performClick()
        composeRule.runOnIdle { assertEquals(1, insertCount) }
        composeRule.onNodeWithText(insertText).assertDoesNotExist()
    }

    @Test
    fun emptySelectionKeepsSelectAllAvailableAndDisablesBatchActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selectAllCount = 0
        composeRule.setContent {
            MaterialTheme {
                PlaylistSelectionActions(
                    allSelected = false,
                    onToggleSelectAll = { selectAllCount += 1 },
                    canExport = false,
                    onExport = {},
                    canInsert = false,
                    onInsert = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_select_all))
            .performClick()
        composeRule.runOnIdle { assertEquals(1, selectAllCount) }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_export_playlist))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.playlist_insert_action))
            .assertIsNotEnabled()
    }

    @Test
    fun primaryActionsDispatchWithoutOpeningAnEmptyOverflowMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var allSelected by mutableStateOf(false)
        var exportCount = 0
        composeRule.setContent {
            MaterialTheme {
                PlaylistSelectionActions(
                    allSelected = allSelected,
                    onToggleSelectAll = { allSelected = !allSelected },
                    canExport = allSelected,
                    onExport = { exportCount += 1 }
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_select_all))
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_export_playlist))
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_deselect_all))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, exportCount)
            assertEquals(false, allSelected)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun narrowLocalPlaylistToolbarKeepsPrimaryActionsVisibleAndSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "12 selected",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            HapticIconButton(onClick = {}) {
                                Icon(Icons.Filled.Close, contentDescription = "close")
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = {}) {
                                Icon(Icons.Outlined.Download, contentDescription = "download")
                            }
                            HapticIconButton(onClick = {}) {
                                Icon(Icons.Filled.Delete, contentDescription = "delete")
                            }
                            PlaylistSelectionActions(
                                allSelected = false,
                                onToggleSelectAll = {},
                                canExport = true,
                                onExport = {},
                                canSync = true,
                                onSync = {}
                            )
                        }
                    )
                }
            }
        }

        val descriptions = listOf(
            "close",
            "download",
            "delete",
            context.getString(CoreCommonR.string.action_select_all),
            context.getString(CoreCommonR.string.cd_export_playlist),
            context.getString(CoreCommonR.string.cd_more_actions)
        )
        val bounds = descriptions.map { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("toolbar actions overlap: $left, $right", left.right <= right.left)
        }
    }
}
