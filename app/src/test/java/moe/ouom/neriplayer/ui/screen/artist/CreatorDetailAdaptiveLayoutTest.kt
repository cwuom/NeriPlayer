package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class CreatorDetailAdaptiveLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setLayout(tabletDevice: Boolean) {
        composeRule.setContent {
            CreatorDetailAdaptiveLayout(
                tabletDevice = tabletDevice,
                miniPlayerHeight = 0.dp,
                profile = { Text("profile") },
                content = { split -> Text(if (split) "split works" else "stacked works") }
            )
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h840dp")
    fun `unfolded phone window splits profile and works`() {
        setLayout(tabletDevice = false)

        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        composeRule.onNodeWithText("profile").assertIsDisplayed()
        composeRule.onNodeWithText("split works").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun `folded phone window keeps the stacked layout`() {
        setLayout(tabletDevice = false)

        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertDoesNotExist()
        composeRule.onNodeWithText("profile").assertDoesNotExist()
        composeRule.onNodeWithText("stacked works").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w673dp-h841dp")
    fun `tablet keeps splitting from 600dp`() {
        setLayout(tabletDevice = true)

        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        composeRule.onNodeWithText("split works").assertIsDisplayed()
    }
}
