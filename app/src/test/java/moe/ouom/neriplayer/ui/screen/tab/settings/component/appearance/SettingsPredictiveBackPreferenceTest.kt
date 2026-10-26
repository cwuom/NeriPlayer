package moe.ouom.neriplayer.ui.screen.tab.settings.component.appearance

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.rememberAppStylePreferences
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp")
class SettingsPredictiveBackPreferenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pixel can enable preview and the app honors the saved choice`() {
        assertUserControlsPreview(manufacturer = "Google", brand = "google")
    }

    @Test
    fun `oneplus without readable rom properties can enable preview`() {
        assertUserControlsPreview(manufacturer = "OnePlus", brand = "OnePlus")
    }

    @Test
    fun `oppo without readable rom properties can enable preview`() {
        assertUserControlsPreview(manufacturer = "OPPO", brand = "OPPO")
    }

    @Test
    @Config(sdk = [28])
    fun `older android keeps the preference editable and persists the saved choice`() {
        assertUserControlsPreview(manufacturer = "Google", brand = "google")
    }

    private fun assertUserControlsPreview(manufacturer: String, brand: String) {
        ShadowBuild.setManufacturer(manufacturer)
        ShadowBuild.setBrand(brand)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(application)
        val autoRepository = AutoSettingsRepository(application)
        runBlocking {
            repository.setSetting(AutoSettingsSchema.motion.predictiveBackPreviewEnabled, false)
        }
        var runtimePreviewEnabled: Boolean? = null
        composeRule.setContent {
            val preferences = rememberAppStylePreferences(repository, UiStyle.Material)
            SideEffect { runtimePreviewEnabled = preferences.predictiveBackPreviewEnabled }
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = UiStyle.Material,
                animateColors = false
            ) {
                SettingsMotionSection(
                    expanded = true,
                    arrowRotation = 0f,
                    onExpandedChange = {},
                    showHeader = false,
                    autoSettingsRepository = autoRepository,
                    scope = rememberCoroutineScope(),
                    advancedBlurEnabled = false,
                    onAdvancedBlurEnabledChange = {},
                    enhancedAdvancedBlurEnabled = false,
                    onEnhancedAdvancedBlurEnabledChange = {},
                    enhancedAdvancedBlurRadiusDp = 40f,
                    onEnhancedAdvancedBlurRadiusDpChange = {},
                    advancedBlurQuality = AdvancedBlurQuality.Default,
                    onAdvancedBlurQualityChange = {},
                    nowPlayingAudioReactiveEnabled = false,
                    onNowPlayingAudioReactiveEnabledChange = {},
                    nowPlayingDynamicBackgroundEnabled = false,
                    onNowPlayingDynamicBackgroundEnabledChange = {},
                    nowPlayingCoverBlurBackgroundEnabled = false,
                    onNowPlayingCoverBlurBackgroundEnabledChange = {},
                    nowPlayingCoverBlurAmount = 0f,
                    onNowPlayingCoverBlurAmountChange = {},
                    nowPlayingCoverBlurDarken = 0f,
                    onNowPlayingCoverBlurDarkenChange = {},
                    lyricBlurEnabled = false,
                    onLyricBlurEnabledChange = {},
                    lyricBlurAmount = 1f,
                    onLyricBlurAmountChange = {},
                    cardIndex = 0
                )
            }
        }
        composeRule.waitUntil { runtimePreviewEnabled == false }
        val preview = composeRule.onNodeWithText(
            application.getString(CoreCommonR.string.settings_predictive_back_preview)
        )
        preview.assertIsEnabled().performClick()
        composeRule.waitUntil { runtimePreviewEnabled == true }
        assertTrue(runBlocking { SettingsRepository(application).predictiveBackPreviewEnabledFlow.first() })

        preview.performClick()
        composeRule.waitUntil { runtimePreviewEnabled == false }
        assertFalse(runBlocking { SettingsRepository(application).predictiveBackPreviewEnabledFlow.first() })
    }
}
