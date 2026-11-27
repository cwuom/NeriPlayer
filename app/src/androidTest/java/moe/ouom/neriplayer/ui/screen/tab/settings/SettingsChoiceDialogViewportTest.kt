package moe.ouom.neriplayer.ui.screen.tab.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.SoftwareKeyboardRule
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.awaitAboveKeyboard
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.screen.tab.settings.component.playback.QualityOptionsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.content.ContentChoice
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsChoiceDialogViewportTest {
    @get:Rule val keyboard: TestRule = SoftwareKeyboardRule()
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireWindow() = assumeComposeHostAvailable()

    @Test fun cleanQualityListCanSelectTheLastRow() = verifyQuality(UiStyle.Clean)
    @Test fun materialQualityListCanSelectTheLastRow() = verifyQuality(UiStyle.Material)
    @Test fun cleanLanguageSearchCanSelectAResult() = verifyLanguage(UiStyle.Clean)
    @Test fun materialLanguageSearchCanSelectAResult() = verifyLanguage(UiStyle.Material)

    private fun verifyQuality(style: UiStyle) {
        val options = listOf(
            "standard" to R.string.quality_standard, "higher" to R.string.quality_high,
            "exhigh" to R.string.quality_very_high, "lossless" to R.string.quality_lossless,
            "hires" to R.string.quality_hires, "jyeffect" to R.string.quality_hd_surround,
            "sky" to R.string.quality_surround, "jymaster" to R.string.settings_audio_quality_jymaster
        ).map { (value, res) -> value to context.getString(res) }
        val selected = mutableStateOf(options.first().first)
        render(style) {
            QualityOptionsDialog(context.getString(R.string.quality_default), selected.value, options, {}) { selected.value = it }
        }
        compose.onNodeWithText(options.first().second).assertIsSelected()
        val close = compose.onNodeWithText(context.getString(R.string.action_close))
        close.assertIsDisplayed()
        val last = compose.onNodeWithText(options.last().second)
        last.performScrollTo().assertIsDisplayed().performNativeClick()
        compose.runOnIdle { assertEquals(options.last().first, selected.value) }
        last.assertIsSelected()
        close.assertIsDisplayed()
    }

    private fun verifyLanguage(style: UiStyle) {
        val choices = listOf(
            "en" to "English", "de" to "Deutsch", "fr" to "Français",
            "ko" to "한국어", "ja" to "日本語", "es-MX" to "Español de México"
        )
        val selected = mutableStateOf("en")
        val title = context.getString(moe.ouom.neriplayer.R.string.youtube_content_language)
        render(style) {
            ContentChoice(Modifier, title, selected.value, choices) { selected.value = it }
        }
        compose.onNode(hasClickAction() and hasText(title)).performClick()
        val list = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
        val search = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasScrollToIndexAction()))
        search.performNativeClick()
        compose.awaitAboveKeyboard(search)
        search.performTextReplacement("ES-mx")
        val result = choices.last().second
        list.performScrollToKey(choices.last().first)
        val resultNode = compose.onNode(hasText(result) and hasAnyAncestor(isDialog()))
        compose.awaitAboveKeyboard(resultNode)
        resultNode.assertIsDisplayed().performNativeClick()
        compose.waitUntil(5_000) { selected.value == "es-MX" }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText(result).assertIsDisplayed()
    }

    private fun render(style: UiStyle, content: @Composable () -> Unit) {
        compose.setContent {
            NeriTheme(
                followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = style, animateColors = false, content = content
            )
        }
    }
}
