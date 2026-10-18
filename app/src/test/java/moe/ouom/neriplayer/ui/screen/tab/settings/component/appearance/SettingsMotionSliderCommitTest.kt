package moe.ouom.neriplayer.ui.screen.tab.settings.component.appearance

import android.app.Application
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMotionSliderCommitTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun releasedSliderKeepsItsPreviewUntilTheSavedValueArrives() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = AutoSettingsRepository(application)
        val savedAmount = mutableFloatStateOf(1f)
        var committedAmount: Float? = null
        composeRule.setContent {
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
                    autoSettingsRepository = repository,
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
                    lyricBlurEnabled = true,
                    onLyricBlurEnabledChange = {},
                    lyricBlurAmount = savedAmount.floatValue,
                    onLyricBlurAmountChange = { committedAmount = it },
                    cardIndex = 3
                )
            }
        }
        composeRule.waitForIdle()

        val slider = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        )
        slider.performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.75f, centerY), delayMillis = 300L)
        }
        composeRule.waitForIdle()
        val previewAmount = slider.fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("the gesture must move the slider away from its saved value", previewAmount > 4f)

        slider.performTouchInput { up() }
        composeRule.waitForIdle()

        assertNotNull("releasing the pointer must commit the preview", committedAmount)
        assertEquals(previewAmount, checkNotNull(committedAmount), 0.01f)
        assertEquals(
            "the slider must not jump to the old value while persistence is pending",
            previewAmount,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,
            0.01f
        )

        composeRule.runOnIdle { savedAmount.floatValue = checkNotNull(committedAmount) }
        composeRule.waitForIdle()
        assertEquals(
            previewAmount,
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,
            0.01f
        )
    }
}
