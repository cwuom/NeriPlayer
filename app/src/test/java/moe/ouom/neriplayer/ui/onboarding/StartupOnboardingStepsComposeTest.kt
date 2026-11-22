package moe.ouom.neriplayer.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.locale.getDisplayName
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2600dp")
class StartupOnboardingStepsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val device = StartupDeviceSpec(
        displayName = "Pixel Test",
        modelCode = "PX-T",
        socLabel = "Tensor G4",
        socClass = StartupSocClass.Flagship,
        sdkInt = 35,
        ramGb = 12,
        cpuCores = 8,
        maxRefreshRate = 120,
        lowRamDevice = false,
        powerSaveMode = false,
        dimensityDevice = false,
        dimensityBlurCompatibility = false
    )

    // 识别动画和徽记都是无限动画, 用手动时钟推进
    private fun setStep(content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { Column { content() } }
        settle(2_000L)
    }

    // 时钟暂停时先让状态变更落地, 再推进帧
    private fun settle(millis: Long = 600L) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    @Test
    fun `language step reports the tapped language`() {
        val picked = mutableListOf<LanguageManager.Language>()
        setStep {
            StartupLanguageStepBody(
                selectedLanguage = LanguageManager.Language.SYSTEM,
                onSelectLanguage = { picked += it }
            )
        }

        composeRule.onNodeWithText(LanguageManager.Language.ENGLISH.getDisplayName(context)).performClick()
        settle()

        assertEquals(listOf(LanguageManager.Language.ENGLISH), picked)
    }

    @Test
    fun `motion step recommends after the scan and applies the tapped preset`() {
        val presets = mutableListOf<StartupMotionPreset>()
        val recommendation = recommendStartupMotion(device)
        setStep {
            StartupMotionStepBody(
                spec = device,
                recommendation = recommendation,
                selectedPreset = recommendation.preset,
                config = startupMotionConfigFor(recommendation.preset, device),
                uiStyle = UiStyle.Clean,
                scanPlayed = false,
                onScanPlayed = {},
                onSelectPreset = { presets += it },
                onConfigChange = {}
            )
        }

        composeRule.onNodeWithText("Pixel Test").assertExists()
        composeRule.onNodeWithText(
            string(
                AppR.string.onboarding_motion_recommend_title,
                string(AppR.string.onboarding_motion_preset_immersive)
            )
        ).assertExists()
        composeRule.onNodeWithText(string(AppR.string.onboarding_motion_preset_light)).performClick()
        settle()

        assertEquals(listOf(StartupMotionPreset.Light), presets)
    }

    @Test
    fun `clean only effects stay disabled under material you`() {
        setStep {
            StartupMotionStepBody(
                spec = device,
                recommendation = recommendStartupMotion(device),
                selectedPreset = StartupMotionPreset.Balanced,
                config = startupMotionConfigFor(StartupMotionPreset.Balanced, device),
                uiStyle = UiStyle.Material,
                scanPlayed = true,
                onScanPlayed = {},
                onSelectPreset = {},
                onConfigChange = {}
            )
        }

        composeRule.onNodeWithText(string(AppR.string.onboarding_motion_fine_tune)).performClick()
        settle()

        composeRule.onNode(
            isToggleable() and hasText(string(CoreCommonR.string.settings_liquid_glass), substring = true),
            useUnmergedTree = false
        ).assertIsNotEnabled().assertIsOff()
    }

    @Test
    fun `enhanced blur shows off on devices without advanced blur even if stored on`() {
        val android12 = device.copy(sdkInt = 31)
        // 迁移或备份恢复来的设置可能没按设备能力归一化
        val stored = startupMotionConfigFor(StartupMotionPreset.Immersive, device)
        setStep {
            StartupMotionStepBody(
                spec = android12,
                recommendation = recommendStartupMotion(android12),
                selectedPreset = null,
                config = stored,
                uiStyle = UiStyle.Clean,
                scanPlayed = true,
                onScanPlayed = {},
                onSelectPreset = {},
                onConfigChange = {}
            )
        }

        composeRule.onNodeWithText(string(AppR.string.onboarding_motion_fine_tune)).performClick()
        settle()

        composeRule.onNode(
            isToggleable() and hasText(string(CoreCommonR.string.settings_enhanced_advanced_blur), substring = true),
            useUnmergedTree = false
        ).assertIsNotEnabled().assertIsOff()
    }

    @Test
    fun `full privacy notice stays hidden until the entry card is opened`() {
        val firstSection = string(AppR.string.disclaimer_section1_title)
        setStep {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                StartupPrivacyStepBody(accepted = false, policyOpen = open, onOpenPolicy = { open = true })
                StartupPrivacyPolicyOverlay(
                    visible = open,
                    onClose = { open = false },
                    agreeLabel = string(AppR.string.disclaimer_agree_countdown),
                    agreeEnabled = true,
                    agreeProgress = null,
                    onAgree = {}
                )
            }
        }

        composeRule.onNodeWithText(firstSection).assertDoesNotExist()
        composeRule.onNodeWithText(string(AppR.string.disclaimer_show_details)).performClick()
        settle()

        composeRule.onNodeWithText(firstSection).assertExists()
        composeRule.onNodeWithText(string(AppR.string.disclaimer_agree_countdown)).assertIsEnabled()
    }

    @Test
    fun `agree action stays locked while the reading countdown runs`() {
        var agreed = false
        setStep {
            StartupActionBar(
                showBack = true,
                backEnabled = true,
                onBack = {},
                primaryLabel = string(AppR.string.disclaimer_read_countdown, 3),
                primaryEnabled = false,
                onPrimary = { agreed = true },
                primaryProgress = resolveStartupPrivacyCountdownProgress(3)
            )
        }

        composeRule.onNodeWithText(string(AppR.string.disclaimer_read_countdown, 3)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_back)).assertIsEnabled()
        assertTrue(!agreed)
    }
}
