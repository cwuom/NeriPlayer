package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
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
                PlaylistSelectionMoreMenu(
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
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_select_all))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .performClick()
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
                PlaylistSelectionMoreMenu(
                    allSelected = false,
                    onToggleSelectAll = { selectAllCount += 1 },
                    canExport = false,
                    onExport = {},
                    canInsert = false,
                    onInsert = {}
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions))
            .performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.playlist_insert_action))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.cd_export_playlist))
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_select_all)).performClick()
        composeRule.runOnIdle { assertEquals(1, selectAllCount) }
    }
}
