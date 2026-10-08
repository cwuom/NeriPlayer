package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySecondaryTabsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `text tabs mark the selected category and report clicked indexes`() {
        val selections = mutableListOf<Int>()
        composeRule.setContent {
            LibrarySecondaryTabs(
                labels = listOf("Playlists", "Artists", "Hot"),
                selectedIndex = 1,
                onSelected = { selections += it }
            )
        }

        composeRule.onNodeWithText("Artists").assertIsSelected()
        composeRule.onNodeWithText("Playlists").assertIsNotSelected()
        composeRule.onNodeWithText("Hot").performClick()
        composeRule.onNodeWithText("Playlists").performClick()

        assertEquals(listOf(2, 0), selections)
    }
}
