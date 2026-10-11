package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
    private lateinit var navigation: SettingsNavigationState

    private fun render(initialSplit: Boolean) {
        splitLayout = initialSplit
        composeRule.setContent {
            navigation = rememberSettingsNavigationState(
                context = LocalContext.current,
                listState = rememberLazyListState(),
                dynamicColor = false,
                mobileDataFollowDefaultAudioQuality = false,
                backgroundImageUri = null,
                splitLayout = splitLayout
            )
        }
        composeRule.waitForIdle()
    }

    private fun resize(split: Boolean) {
        composeRule.runOnIdle { splitLayout = split }
        composeRule.waitForIdle()
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
}
