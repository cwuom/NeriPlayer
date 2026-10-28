package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BILI_SUBTITLE_LANGUAGE_AUTO
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBiliCcSubtitleGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val repository = SettingsRepository(application)
    private var savedPreferences: Preferences? = null
    private var visible by mutableStateOf(true)

    @Before
    fun prepareSettings() {
        savedPreferences = runBlocking { application.dataStore.data.first() }
        runBlocking {
            repository.setBiliCcSubtitleLyricsEnabled(true)
            repository.setBiliCcSubtitleLanguage(BILI_SUBTITLE_LANGUAGE_AUTO)
            repository.setBiliCcSubtitleAiOnlyEnabled(true)
        }
    }

    @After
    fun restoreSettings() {
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        savedPreferences?.let { preferences ->
            runBlocking { application.dataStore.updateData { preferences } }
        }
    }

    @Test
    fun cleanMasterSwitchLocksDependentControlsAndPreservesAiChoice() =
        verifyDependentControls(UiStyle.Clean)

    @Test
    fun materialMasterSwitchLocksDependentControlsAndPreservesAiChoice() =
        verifyDependentControls(UiStyle.Material)

    @Test
    fun cleanLanguageChoicePersistsAndUpdatesTheCollapsedSummary() =
        verifyLanguageSelection(UiStyle.Clean)

    @Test
    fun materialLanguageChoicePersistsAndUpdatesTheCollapsedSummary() =
        verifyLanguageSelection(UiStyle.Material)

    @Test
    fun expandedGroupAndPersistedLanguageSurviveSavedStateRestoration() {
        val restorer = StateRestorationTester(composeRule)
        restorer.setContent { GroupContent(UiStyle.Material) }
        groupHeader().performClick()
        chooseEnglish()

        restorer.emulateSavedInstanceStateRestore()

        languageRow().assertIsEnabled()
        waitForSummary(AppR.string.bili_cc_subtitle_lang_en)
        assertEquals("en-US", runBlocking { repository.biliCcSubtitleLanguageFlow.first() })
        groupHeader().performClick()
        languageRow().assertDoesNotExist()
    }

    @Test
    fun automaticEnglishTrackKeepsItsEnglishLanguageLabel() =
        verifyTrackLanguageLabel("ai-en", AppR.string.bili_cc_subtitle_lang_en)

    @Test
    fun automaticJapaneseTrackKeepsItsJapaneseLanguageLabel() =
        verifyTrackLanguageLabel("ai-ja", AppR.string.bili_cc_subtitle_lang_ja)

    @Test
    fun automaticChineseTrackKeepsItsAutomaticChineseLabel() =
        verifyTrackLanguageLabel("ai-zh", AppR.string.bili_cc_subtitle_lang_zh_ai)

    @Test
    fun hongKongChineseTrackUsesTraditionalChineseLabel() =
        verifyTrackLanguageLabel("zh-HK", AppR.string.bili_cc_subtitle_lang_zh_hant)

    @Test
    fun macauChineseTrackUsesTraditionalChineseLabel() =
        verifyTrackLanguageLabel("zh-MO", AppR.string.bili_cc_subtitle_lang_zh_hant)

    private fun verifyDependentControls(style: UiStyle) {
        composeRule.setContent { GroupContent(style) }
        groupHeader().performClick()
        masterSwitch().assertIsOn()
        aiSwitch().assertIsOn().assertIsEnabled().performScrollTo().performClick()
        waitForAiEnabled(false)

        masterSwitch().performScrollTo().performClick()
        waitForLyricsEnabled(false)
        languageRow().assertIsNotEnabled()
        aiRow().assertIsNotEnabled()
        aiSwitch().assertIsOff().assertIsNotEnabled()
        languageRow().performScrollTo().performTouchInput { click() }
        aiSwitch().performScrollTo().performTouchInput { click() }
        composeRule.onNodeWithText(
            application.getString(AppR.string.bili_cc_subtitle_language_dialog_title)
        ).assertDoesNotExist()
        assertEquals(false, runBlocking { repository.biliCcSubtitleAiOnlyEnabledFlow.first() })

        masterSwitch().performScrollTo().performClick()
        waitForLyricsEnabled(true)
        languageRow().assertIsEnabled()
        aiSwitch().assertIsOff().assertIsEnabled().performScrollTo().performClick()
        waitForAiEnabled(true)
        assertEquals(true, runBlocking { SettingsRepository(application).biliCcSubtitleAiOnlyEnabledFlow.first() })
    }

    private fun verifyLanguageSelection(style: UiStyle) {
        composeRule.setContent { GroupContent(style) }
        groupHeader().performClick()
        chooseEnglish()

        groupHeader().performClick()
        languageRow().assertDoesNotExist()
        waitForSummary(AppR.string.bili_cc_subtitle_lang_en)
        assertEquals("en-US", runBlocking { SettingsRepository(application).biliCcSubtitleLanguageFlow.first() })

        groupHeader().performClick()
        languageRow().performScrollTo().performClick()
        composeRule.onNodeWithText(application.getString(AppR.string.bili_cc_subtitle_auto))
            .performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { repository.biliCcSubtitleLanguageFlow.first() } == BILI_SUBTITLE_LANGUAGE_AUTO
        }
        waitForSummary(AppR.string.bili_cc_subtitle_auto)
    }

    private fun chooseEnglish() {
        languageRow().performScrollTo().performClick()
        composeRule.onNodeWithText(application.getString(AppR.string.bili_cc_subtitle_lang_en))
            .performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { repository.biliCcSubtitleLanguageFlow.first() } == "en-US"
        }
        composeRule.onNodeWithText(
            application.getString(AppR.string.bili_cc_subtitle_language_dialog_title)
        ).assertDoesNotExist()
        waitForSummary(AppR.string.bili_cc_subtitle_lang_en)
    }

    private fun verifyTrackLanguageLabel(language: String, labelRes: Int) {
        runBlocking { repository.setBiliCcSubtitleLanguage(language) }
        composeRule.setContent { GroupContent(UiStyle.Material) }

        waitForSummary(labelRes)
        assertEquals(language, runBlocking { repository.biliCcSubtitleLanguageFlow.first() })
    }

    @Composable
    private fun GroupContent(style: UiStyle) {
        NeriTheme(
            followSystemDark = false,
            forceDark = false,
            dynamicColor = false,
            seedColorHex = "885522",
            uiStyle = style,
            animateColors = false
        ) {
            if (visible) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsBiliCcSubtitleGroup(repository)
                }
            }
        }
    }

    private fun groupHeader() = composeRule.onNodeWithText(
        application.getString(AppR.string.settings_bili_cc_subtitle_group)
    )

    private fun masterRow() = composeRule.onNodeWithText(
        application.getString(CoreCommonR.string.settings_bili_cc_subtitle_lyrics)
    )

    private fun languageRow() = composeRule.onNodeWithText(
        application.getString(CoreCommonR.string.settings_bili_cc_subtitle_language)
    )

    private fun aiRow() = composeRule.onNodeWithText(
        application.getString(CoreCommonR.string.settings_bili_cc_subtitle_ai_only)
    )

    private fun masterSwitch() = masterRow().onChildren().filterToOne(isToggleable())

    private fun aiSwitch() = aiRow().onChildren().filterToOne(isToggleable())

    private fun waitForLyricsEnabled(enabled: Boolean) {
        val expected = if (enabled) ToggleableState.On else ToggleableState.Off
        composeRule.waitUntil(5_000) {
            masterSwitch().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expected
        }
        assertEquals(enabled, runBlocking { repository.biliCcSubtitleLyricsEnabledFlow.first() })
    }

    private fun waitForAiEnabled(enabled: Boolean) {
        val expected = if (enabled) ToggleableState.On else ToggleableState.Off
        composeRule.waitUntil(5_000) {
            aiSwitch().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expected
        }
        assertEquals(enabled, runBlocking { repository.biliCcSubtitleAiOnlyEnabledFlow.first() })
    }

    private fun waitForSummary(labelRes: Int) {
        val summary = application.getString(
            AppR.string.settings_bili_cc_subtitle_group_enabled,
            application.getString(labelRes)
        )
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
