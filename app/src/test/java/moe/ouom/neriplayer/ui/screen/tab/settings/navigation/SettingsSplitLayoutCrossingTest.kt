package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSplitLayoutCrossingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var splitLayout by mutableStateOf(false)
    private var international by mutableStateOf(false)
    private lateinit var navigation: SettingsNavigationState

    private fun render(initialSplit: Boolean) {
        splitLayout = initialSplit
        composeRule.setContent {
            navigation = rememberSettingsNavigationState(
                listState = rememberLazyListState(),
                dynamicColor = false,
                mobileDataFollowDefaultAudioQuality = false,
                backgroundImageUri = null,
                splitLayout = splitLayout,
                internationalEnabled = international
            )
        }
        composeRule.waitForIdle()
    }

    private fun resize(split: Boolean) {
        composeRule.runOnIdle { splitLayout = split }
        composeRule.waitForIdle()
    }

    @Test
    fun `home shortcut handles Back only while its target is still open`() {
        render(initialSplit = true)
        var consumed = 0
        var returns = 0
        composeRule.runOnIdle {
            navigation.consumeHomeShortcut(true, SettingsPage.Content) { consumed++ }
            assertEquals(SettingsPage.Language, navigation.activePage)
            assertEquals(true, navigation.canNavigateBack)
            assertEquals(true, navigation.showSplitDetailBackButton)
            navigation.consumeHomeShortcut(true, null) { consumed++ }
            navigation.navigateBackFromPage { returns++ }
            assertEquals(1, returns)

            navigation.consumeHomeShortcut(true, SettingsPage.Language) { consumed++ }
            navigation.activePage = SettingsPage.Storage
            navigation.navigateBackFromPage { returns++ }
            navigation.consumeHomeShortcut(true, SettingsPage.Language) { consumed++ }
            navigation.consumeHomeShortcut(false, SettingsPage.General) { consumed++ }
            navigation.navigateBackFromPage { returns++ }
            assertEquals(3, consumed)
            assertEquals(1, returns)
        }
    }

    @Test
    fun `restored content opens language in either mode and window layout`() {
        render(initialSplit = true)
        composeRule.runOnIdle { navigation.activePageState.value = SettingsPage.Content }
        composeRule.runOnIdle {
            assertEquals(SettingsPage.Language, navigation.activePage)
            international = true
        }
        composeRule.runOnIdle {
            assertEquals(SettingsPage.Language, navigation.activePage)
            navigation.activePage = SettingsPage.Content
            assertEquals(SettingsPage.Language, navigation.activePageState.value)
            international = false
        }
        composeRule.runOnIdle {
            assertEquals(SettingsPage.Language, navigation.activePage)
            assertEquals(SettingsPage.Language, navigation.activePageState.value)
        }
        resize(split = false)
        composeRule.runOnIdle { assertEquals(SettingsPage.Language, navigation.activePage) }
    }

    @Test
    fun `auto selected general page does not leak into the single pane layout`() {
        render(initialSplit = true)
        assertEquals(SettingsPage.General, navigation.activePage)

        resize(split = false)

        assertNull(navigation.activePage)
    }

    @Test
    fun `entering split with no open page shows general`() {
        render(initialSplit = false)
        assertNull(navigation.activePage)

        resize(split = true)

        assertEquals(SettingsPage.General, navigation.activePage)
    }

    @Test
    fun `an open detail page survives crossing the split threshold both ways`() {
        render(initialSplit = false)
        composeRule.runOnIdle { navigation.activePage = SettingsPage.Storage }

        resize(split = true)
        assertEquals(SettingsPage.Storage, navigation.activePage)

        resize(split = false)
        assertEquals(SettingsPage.Storage, navigation.activePage)
    }

    @Test
    fun `a page picked in split view stays open after narrowing`() {
        render(initialSplit = true)
        composeRule.runOnIdle { navigation.activePage = SettingsPage.About }

        resize(split = false)

        assertEquals(SettingsPage.About, navigation.activePage)
    }

    @Test
    fun `reselect returns a nested page straight to the settings home`() {
        render(initialSplit = false)
        composeRule.runOnIdle {
            navigation.activePage = SettingsPage.StorageCacheDetails

            assertEquals(true, navigation.returnToRoot())
            assertNull(navigation.activePage)
            assertEquals(false, navigation.returnToRoot())
        }
    }

    @Test
    fun `reselect in split layout falls back to the default detail page`() {
        render(initialSplit = true)
        composeRule.runOnIdle {
            assertEquals(false, navigation.returnToRoot())

            navigation.activePage = SettingsPage.Storage
            assertEquals(true, navigation.returnToRoot())
            assertEquals(SettingsPage.General, navigation.activePage)
        }
    }
}
