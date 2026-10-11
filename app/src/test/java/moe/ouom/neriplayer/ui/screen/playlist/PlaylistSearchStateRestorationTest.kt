package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistSearchStateRestorationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `open search and typed query survive saved instance state restore`() {
        val tester = StateRestorationTester(composeRule)
        lateinit var searchState: PlaylistSearchUiState
        tester.setContent {
            searchState = rememberPlaylistSearchUiState()
            PlaylistSearchHost(searchState)
        }

        composeRule.runOnIdle { searchState.visible.value = true }
        composeRule.onNode(hasSetTextAction()).performTextInput("aurora")
        composeRule.waitUntil(timeoutMillis = 5_000) { searchState.query.value == "aurora" }

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assert(hasText("aurora"))
        composeRule.runOnIdle {
            assertTrue(searchState.visible.value)
            assertEquals("aurora", searchState.query.value)
        }
    }

    @Test
    fun `closed search with an empty query restores closed`() {
        val tester = StateRestorationTester(composeRule)
        lateinit var searchState: PlaylistSearchUiState
        tester.setContent {
            searchState = rememberPlaylistSearchUiState()
            PlaylistSearchHost(searchState)
        }

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.runOnIdle {
            assertFalse(searchState.visible.value)
            assertEquals("", searchState.query.value)
        }
    }

    @Composable
    private fun PlaylistSearchHost(state: PlaylistSearchUiState) {
        val showSearch by state.visible
        var searchQuery by state.query
        val searchInputState = rememberPlaylistSearchInputState(
            query = searchQuery,
            onQueryChange = { searchQuery = it }
        )
        if (showSearch) {
            PlaylistModernHeroSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search playlist",
                inputState = searchInputState
            )
        }
    }
}
