package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class XiaomiSuperIslandSettingsPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val autoSettingsRepository = AutoSettingsRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val requestedPermissions = mutableListOf<String>()
    private var savedPreferences: Preferences? = null
    private var pageVisible by mutableStateOf(true)
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            check(contract is ActivityResultContracts.RequestPermission)
            requestedPermissions += checkNotNull(input as? String)
            dispatchResult(requestCode, false)
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before
    fun prepareSettings() {
        savedPreferences = runBlocking { application.dataStore.data.first() }
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun restoreSettings() {
        composeRule.runOnIdle { pageVisible = false }
        composeRule.waitForIdle()
        savedPreferences?.let { preferences ->
            runBlocking { application.dataStore.updateData { preferences } }
        }
    }

    @Test
    fun rowEnableRequestsNotificationPermission() {
        showPage(enabled = false)

        masterRow().performClick()

        waitForEnabled(true)
        composeRule.runOnIdle {
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), requestedPermissions)
        }
    }

    @Test
    fun switchEnableRequestsNotificationPermission() {
        showPage(enabled = false)

        masterSwitch().performClick()

        waitForEnabled(true)
        composeRule.runOnIdle {
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), requestedPermissions)
        }
    }

    @Test
    fun disablingFromEitherEntryDoesNotRequestNotificationPermission() {
        showPage(enabled = true)
        masterSwitch().performClick()
        waitForEnabled(false)
        runBlocking { autoSettingsRepository.setXiaomiSuperIslandLyricEnabled(true) }
        waitForEnabled(true)

        masterRow().performClick()

        waitForEnabled(false)
        composeRule.runOnIdle { assertTrue(requestedPermissions.isEmpty()) }
    }

    @Test
    fun alreadyGrantedPermissionIsNotRequestedByEitherEntry() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showPage(enabled = false)
        masterSwitch().performClick()
        waitForEnabled(true)
        runBlocking { autoSettingsRepository.setXiaomiSuperIslandLyricEnabled(false) }
        waitForEnabled(false)

        masterRow().performClick()

        waitForEnabled(true)
        composeRule.runOnIdle { assertTrue(requestedPermissions.isEmpty()) }
    }

    private fun showPage(enabled: Boolean) {
        runBlocking { autoSettingsRepository.setXiaomiSuperIslandLyricEnabled(enabled) }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    if (pageVisible) {
                        XiaomiSuperIslandSettingsPage(autoSettingsRepository, settingsRepository)
                    }
                }
            }
        }
        waitForEnabled(enabled)
    }

    private fun masterRow() = composeRule.onNodeWithText(
        application.getString(CoreCommonR.string.settings_xiaomi_super_island_lyric_enabled)
    )

    private fun masterSwitch() = masterRow().onChildren().filterToOne(isToggleable())

    private fun waitForEnabled(enabled: Boolean) {
        val expected = if (enabled) ToggleableState.On else ToggleableState.Off
        composeRule.waitUntil(5_000) {
            masterSwitch().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expected
        }
        if (enabled) masterSwitch().assertIsOn() else masterSwitch().assertIsOff()
    }
}
