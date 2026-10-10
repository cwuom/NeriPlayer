package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSettingsPredictiveBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var activePage by mutableStateOf<SettingsPage?>(SettingsPage.UsbExclusive)

    private val dispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun setHost() {
        composeRule.setContent {
            PhoneSettingsPageTransitionHost(
                activePage = activePage,
                isolateAdvancedGlassTransitions = false,
                backEnabled = canNavigateBackFromSettingsPage(activePage, splitLayout = false),
                onBack = { activePage = activePage?.backTargetPage() }
            ) { page -> Text(page?.name ?: HOME) }
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

    @Test
    fun `back gestures walk nested pages up to the settings home`() {
        setHost()

        swipeBack()
        assertEquals(SettingsPage.Playback, activePage)
        composeRule.onNodeWithText(SettingsPage.Playback.name).assertExists()

        swipeBack()
        assertEquals(null, activePage)
        composeRule.onNodeWithText(HOME).assertExists()
        composeRule.runOnIdle { assertFalse(dispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun `cancelled back gesture stays on the current page`() {
        setHost()

        swipeBack(cancel = true)

        assertEquals(SettingsPage.UsbExclusive, activePage)
        composeRule.onNodeWithText(SettingsPage.UsbExclusive.name).assertExists()
    }

    private companion object {
        const val HOME = "settings-home"
    }
}
