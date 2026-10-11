package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletSettingsPredictiveBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var activePage by mutableStateOf<SettingsPage?>(SettingsPage.StorageCacheDetails)

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun setHost() {
        composeRule.setContent {
            SettingsPageTransitionHost(
                activePage = activePage,
                isolateAdvancedGlassTransitions = false,
                backEnabled = activePage?.backTargetPage() != null,
                onBack = { activePage = activePage?.backTargetPage() }
            ) { page -> Text(page?.name ?: "home") }
        }
        composeRule.waitForIdle()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    private fun swipeBack(cancel: Boolean) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.6f)) }
        composeRule.runOnIdle {
            if (cancel) dispatcher.dispatchOnBackCancelled() else dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun sceneExists(page: SettingsPage?): Boolean =
        composeRule.onAllNodesWithTag(settingsPageTransitionSceneTag(page)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `released gesture settles on the parent page without bouncing back`() {
        setHost()

        swipeBack(cancel = false)

        assertEquals(SettingsPage.Storage, activePage)
        composeRule.onNodeWithTag(settingsPageTransitionSceneTag(SettingsPage.Storage)).assertExists()
        assertFalse(sceneExists(SettingsPage.StorageCacheDetails))
        composeRule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun `cancelled gesture keeps the nested page`() {
        setHost()

        swipeBack(cancel = true)

        assertEquals(SettingsPage.StorageCacheDetails, activePage)
        composeRule.onNodeWithTag(settingsPageTransitionSceneTag(SettingsPage.StorageCacheDetails)).assertExists()
        assertFalse(sceneExists(SettingsPage.Storage))
    }

    @Test
    fun `navigating forward while idle still switches pages`() {
        activePage = SettingsPage.Storage
        setHost()

        composeRule.runOnIdle { activePage = SettingsPage.StorageCacheDetails }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(settingsPageTransitionSceneTag(SettingsPage.StorageCacheDetails)).assertExists()
        assertFalse(sceneExists(SettingsPage.Storage))
    }
}
