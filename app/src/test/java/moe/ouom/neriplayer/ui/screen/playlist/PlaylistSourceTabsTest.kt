package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistSourceTabsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `material pane uses the hero segmented tabs`() = checkPane(UiStyle.Material)

    @Test
    fun `clean pane uses the hero segmented tabs`() = checkPane(UiStyle.Clean)

    @Test
    fun `tabs outside the pane keep the regular style`() {
        composeRule.setContent {
            Theme(UiStyle.Material) {
                PlaylistSourceTabs(labels = Labels, selectedIndex = 0, onSelected = {})
            }
        }
        composeRule.onNodeWithTag(PlaylistPaneSourceTabsTag).assertDoesNotExist()
        composeRule.onNodeWithText(Labels[1]).assertExists()
    }

    private fun checkPane(style: UiStyle) {
        var selected by mutableIntStateOf(0)
        composeRule.setContent {
            Theme(style) {
                CompositionLocalProvider(LocalPlaylistHeroInPane provides true) {
                    PlaylistSourceTabs(labels = Labels, selectedIndex = selected, onSelected = { selected = it })
                }
            }
        }
        composeRule.onNodeWithTag(PlaylistPaneSourceTabsTag).assertExists()
        composeRule.onNodeWithText(Labels[1]).performClick()
        composeRule.waitForIdle()
        assertEquals(1, selected)
        composeRule.onNodeWithText(Labels[1]).assertIsSelected()
    }

    @Composable
    private fun Theme(style: UiStyle, content: @Composable () -> Unit) {
        NeriTheme(
            followSystemDark = false,
            forceDark = false,
            dynamicColor = false,
            seedColorHex = "885522",
            uiStyle = style,
            animateColors = false,
            content = content
        )
    }

    private companion object {
        val Labels = listOf("Manually Added", "Downloaded")
    }
}
