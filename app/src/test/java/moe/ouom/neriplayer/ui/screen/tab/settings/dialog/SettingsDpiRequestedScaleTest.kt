package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.UiDensityScalePreference
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.component.UiScaleListItem
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-sw1500dp-w2400dp-h1500dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDpiRequestedScaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var carContext: Context
    private lateinit var repository: SettingsRepository
    private var savedPreferences: Preferences? = null
    private var savedCleanMotion = false
    private var contentVisible by mutableStateOf(true)
    private var dialogVisible by mutableStateOf(true)
    private var deviceMode by mutableIntStateOf(Configuration.UI_MODE_TYPE_CAR)
    private val appliedScales = mutableListOf<Float>()

    @Before
    fun prepareStoredRequest() {
        savedCleanMotion = NeriMotionStyle.clean
        savedPreferences = runBlocking { application.dataStore.data.first() }
        val configuration = Configuration(application.resources.configuration).apply {
            smallestScreenWidthDp = 1500
            uiMode = Configuration.UI_MODE_TYPE_CAR
        }
        carContext = application.createConfigurationContext(configuration)
        repository = SettingsRepository(carContext)
        assertEquals(Configuration.UI_MODE_TYPE_CAR,
            carContext.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        runBlocking { AutoSettingsRepository(application).setUiDensityScale(2f) }
        assertStoredRequest(2f)
    }

    @After
    fun restoreSettings() {
        try {
            try {
                composeRule.runOnIdle { contentVisible = false }
                composeRule.waitForIdle()
            } finally {
                savedPreferences?.let { preferences ->
                    runBlocking {
                        application.dataStore.updateData { preferences }
                        assertEquals(preferences, application.dataStore.data.first())
                    }
                }
            }
        } finally {
            NeriMotionStyle.clean = savedCleanMotion
        }
    }

    @Test
    fun untouchedCarApplyClosesWithoutReplacingTheTabletRequest() {
        render()
        composeRule.onNodeWithText("1.50x").assertIsDisplayed()

        composeRule.onNodeWithText(label(CoreCommonR.string.action_apply)).performClick()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertStoredRequest(2f)
        composeRule.runOnIdle { assertEquals(emptyList<Float>(), appliedScales) }
        assertNormalTabletScale("2.00")
    }

    @Test
    fun movingAwayAndBackToTheEffectiveCarValuePersistsTheExplicitSelection() {
        render()
        selectScale(0.6f)
        composeRule.onNodeWithText("0.60x").assertIsDisplayed()
        selectScale(1.5f)
        composeRule.onNodeWithText("1.50x").assertIsDisplayed()

        composeRule.onNodeWithText(label(CoreCommonR.string.action_apply)).performClick()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertStoredRequest(1.5f)
        composeRule.runOnIdle { assertEquals(listOf(1.5f), appliedScales) }
        assertNormalTabletScale("1.50")
    }

    @Test
    fun resetExplicitlyReplacesTheTabletRequestWithTheDefault() {
        render()

        composeRule.onNodeWithText(label(CoreCommonR.string.action_reset)).performClick()

        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertStoredRequest(1f)
        composeRule.runOnIdle { assertEquals(listOf(1f), appliedScales) }
        assertNormalTabletScale("1.00")
    }

    private fun render() {
        composeRule.setContent {
            if (contentVisible) {
                val storedRequest by repository.uiDensityScaleFlow.collectAsState(initial = 2f)
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = 1500
                    uiMode = deviceMode
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                        seedColorHex = "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                        val effectiveScale = UiDensityScalePreference.forDevice(1500, deviceMode)
                            .normalize(storedRequest)
                        UiScaleListItem(currentScale = effectiveScale, onClick = { dialogVisible = true })
                        SettingsPreferenceDialogs(
                            showDefaultStartDestinationDialog = false,
                            onShowDefaultStartDestinationDialogChange = {},
                            homeStartAvailable = true,
                            effectiveDefaultStartDestination = "home",
                            onDefaultStartDestinationChange = {},
                            showColorPickerDialog = false,
                            onShowColorPickerDialogChange = {},
                            seedColorHex = "6750A4",
                            themeColorPalette = emptyList(),
                            onSeedColorChange = {},
                            onAddColorToPalette = {},
                            onRemoveColorFromPalette = {},
                            showDpiDialog = dialogVisible,
                            onShowDpiDialogChange = { dialogVisible = it },
                            uiDensityScale = storedRequest,
                            onUiDensityScaleChange = { scale ->
                                runBlocking { repository.setUiDensityScale(scale) }
                                appliedScales += scale
                            }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun selectScale(value: Float) {
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    private fun assertStoredRequest(expected: Float) = runBlocking {
        assertEquals(expected, application.dataStore.data.first()[AutoSettingsKeys.UI_DENSITY_SCALE])
        assertEquals(expected, repository.uiDensityScaleFlow.first(), 0f)
    }

    private fun assertNormalTabletScale(expected: String) {
        composeRule.runOnIdle { deviceMode = Configuration.UI_MODE_TYPE_NORMAL }
        composeRule.onNodeWithText(application.getString(CoreCommonR.string.settings_ui_scale_current, expected))
            .assertIsDisplayed()
    }

    private fun label(resource: Int): String = application.getString(resource)
}
