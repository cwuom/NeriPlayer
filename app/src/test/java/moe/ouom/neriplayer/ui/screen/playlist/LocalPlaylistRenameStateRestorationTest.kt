package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistRenameStateRestorationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rename dialog draft survives saved instance state restore`() {
        val tester = StateRestorationTester(composeRule)
        lateinit var renameState: PlaylistRenameUiState
        tester.setContent {
            renameState = rememberPlaylistRenameUiState(name = "Morning Mix", maxLength = MAX_NAME_LENGTH)
            RenameDraftField(renameState)
        }

        composeRule.runOnIdle { renameState.visible.value = true }
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).assertTextEquals("Morning Mix")
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        composeRule.onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Evening Mix")
        composeRule.runOnIdle { renameState.error.value = "Name already exists" }

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(RENAME_FIELD_TAG).assertTextEquals("Evening Mix")
        composeRule.runOnIdle {
            assertTrue(renameState.visible.value)
            assertEquals(TextFieldValue("Evening Mix", TextRange(11)), renameState.text.value)
            assertEquals("Name already exists", renameState.error.value)
        }
    }

    @Test
    fun `fresh rename state starts hidden with the limited playlist name`() {
        lateinit var renameState: PlaylistRenameUiState
        composeRule.setContent {
            renameState = rememberPlaylistRenameUiState(name = "Late Night Drive", maxLength = 10)
        }

        composeRule.runOnIdle {
            assertFalse(renameState.visible.value)
            assertEquals(TextFieldValue("Late Night", TextRange(10)), renameState.text.value)
            assertNull(renameState.error.value)
        }
    }

    @Composable
    private fun RenameDraftField(state: PlaylistRenameUiState) {
        val showRename by state.visible
        var renameText by state.text
        if (showRename) {
            BasicTextField(
                value = renameText.text,
                onValueChange = { renameText = playlistNameFieldValue(it, MAX_NAME_LENGTH) },
                modifier = Modifier.testTag(RENAME_FIELD_TAG)
            )
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 40
        const val RENAME_FIELD_TAG = "renameField"
    }
}
