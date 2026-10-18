package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.app.Application
import android.app.NotificationManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XiaomiSuperIslandPermissionResumeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager = application.getSystemService(NotificationManager::class.java)
    private val autoSettingsRepository = AutoSettingsRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val pageOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private var pageVisible by mutableStateOf(true)
    private var originalNotificationsEnabled = false

    @Before
    fun rememberNotificationStatus() {
        originalNotificationsEnabled = notificationManager.areNotificationsEnabled()
    }

    @After
    fun restoreFixture() {
        composeRule.runOnIdle {
            pageVisible = false
            pageOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
        composeRule.waitForIdle()
        shadowOf(notificationManager).setNotificationsEnabled(originalNotificationsEnabled)
    }

    @Test
    fun returningFromNotificationSettingsRefreshesTheGrantedStatus() {
        render(notificationsEnabled = false)
        assertStatus(granted = false)

        resumeAfterSettingsChange(notificationsEnabled = true)

        assertStatus(granted = true)
    }

    @Test
    fun returningFromNotificationSettingsRefreshesTheRevokedStatus() {
        render(notificationsEnabled = true)
        assertStatus(granted = true)

        resumeAfterSettingsChange(notificationsEnabled = false)

        assertStatus(granted = false)
    }

    private fun render(notificationsEnabled: Boolean) {
        shadowOf(notificationManager).setNotificationsEnabled(notificationsEnabled)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    if (pageVisible) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            XiaomiSuperIslandSettingsPage(autoSettingsRepository, settingsRepository)
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { pageOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
    }

    private fun resumeAfterSettingsChange(notificationsEnabled: Boolean) {
        composeRule.runOnIdle {
            pageOwner.lifecycle.currentState = Lifecycle.State.CREATED
            shadowOf(notificationManager).setNotificationsEnabled(notificationsEnabled)
            pageOwner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun assertStatus(granted: Boolean) {
        val visible = if (granted) {
            CoreCommonR.string.settings_xiaomi_super_island_notification_status_granted
        } else {
            CoreCommonR.string.settings_xiaomi_super_island_notification_status_missing
        }
        val hidden = if (granted) {
            CoreCommonR.string.settings_xiaomi_super_island_notification_status_missing
        } else {
            CoreCommonR.string.settings_xiaomi_super_island_notification_status_granted
        }
        composeRule.onNodeWithText(application.getString(visible))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(application.getString(hidden)).assertDoesNotExist()
    }
}
