package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSettingsPredictiveBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var activePage by mutableStateOf<SettingsPage?>(SettingsPage.StorageCacheDetails)

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun setHost(previewEnabled: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides previewEnabled) {
                PhoneSettingsPageTransitionHost(
                    activePage = activePage,
                    isolateAdvancedGlassTransitions = false,
                    backEnabled = canNavigateBackFromSettingsPage(activePage, splitLayout = false),
                    onBack = { activePage = activePage?.backTargetPage() }
                ) { page -> Text(page?.name ?: HOME) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    private fun swipeBack(cancel: Boolean = false) {
        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.5f)) }
        composeRule.runOnIdle {
            if (cancel) dispatcher.dispatchOnBackCancelled() else dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun sceneExists(page: SettingsPage?): Boolean =
        composeRule.onAllNodesWithTag(settingsPageTransitionSceneTag(page)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `back gestures walk nested pages up to the settings home`() {
        setHost()

        swipeBack()
        assertEquals(SettingsPage.Storage, activePage)
        composeRule.onNodeWithText(SettingsPage.Storage.name).assertExists()

        swipeBack()
        assertEquals(null, activePage)
        composeRule.onNodeWithText(HOME).assertExists()
        composeRule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun `cancelled back gesture stays on the current page`() {
        setHost()

        swipeBack(cancel = true)

        assertEquals(SettingsPage.StorageCacheDetails, activePage)
        composeRule.onNodeWithText(SettingsPage.StorageCacheDetails.name).assertExists()
    }

    @Test
    fun `disabled preview keeps the parent scene absent through gesture cancellation`() {
        setHost(previewEnabled = false)

        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.6f)) }
        composeRule.waitForIdle()

        assertEquals(SettingsPage.StorageCacheDetails, activePage)
        assertTrue(sceneExists(SettingsPage.StorageCacheDetails))
        assertFalse(sceneExists(SettingsPage.Storage))

        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()

        assertEquals(SettingsPage.StorageCacheDetails, activePage)
        assertTrue(sceneExists(SettingsPage.StorageCacheDetails))
        assertFalse(sceneExists(SettingsPage.Storage))
    }

    @Test
    fun `disabled preview waits for release before the ordinary parent transition`() {
        setHost(previewEnabled = false)
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        composeRule.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(0.6f)) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        assertEquals(SettingsPage.StorageCacheDetails, activePage)
        assertTrue(sceneExists(SettingsPage.StorageCacheDetails))
        assertFalse(sceneExists(SettingsPage.Storage))

        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(64L)
        composeRule.waitForIdle()

        assertEquals(SettingsPage.Storage, activePage)
        assertTrue("普通返回动画中应保留退出页", sceneExists(SettingsPage.StorageCacheDetails))
        assertTrue("松手后才应加入上一级场景", sceneExists(SettingsPage.Storage))

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(settingsPageTransitionSceneTag(SettingsPage.Storage)).assertExists()
        assertFalse(sceneExists(SettingsPage.StorageCacheDetails))
    }

    private companion object {
        const val HOME = "settings-home"
    }
}
