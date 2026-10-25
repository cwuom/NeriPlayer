package moe.ouom.neriplayer.ui.screen

import android.app.Application
import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackQualityOption
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingQualityOptionsDialog
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.FillOptionsDialog
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricBehaviorContent
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricBehaviorSheetState
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w412dp-h900dp")
class NowPlayingOverlayDialogsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `clean light quality choices follow selection and close callbacks`() =
        assertQualityChoices(UiStyle.Clean, dark = false)

    @Test
    fun `clean dark quality choices follow selection and close callbacks`() =
        assertQualityChoices(UiStyle.Clean, dark = true)

    @Test
    fun `material quality choices follow selection and close callbacks`() =
        assertQualityChoices(UiStyle.Material, dark = false)

    @Test
    fun `clean light fill choices preserve all four values and disabled actions`() =
        assertFillChoices(UiStyle.Clean, dark = false)

    @Test
    fun `clean dark fill choices preserve all four values and disabled actions`() =
        assertFillChoices(UiStyle.Clean, dark = true)

    @Test
    fun `material fill choices preserve all four values and disabled actions`() =
        assertFillChoices(UiStyle.Material, dark = false)

    @Test
    fun `clean light lyric controls toggle secondary lyrics commit offset and finish`() =
        assertLyricBehavior(UiStyle.Clean, dark = false)

    @Test
    fun `clean dark lyric controls toggle secondary lyrics commit offset and finish`() =
        assertLyricBehavior(UiStyle.Clean, dark = true)

    @Test
    fun `material lyric controls toggle secondary lyrics commit offset and finish`() =
        assertLyricBehavior(UiStyle.Material, dark = false)

    private fun assertQualityChoices(style: UiStyle, dark: Boolean) {
        val options = listOf(
            PlaybackQualityOption("high", "High quality"),
            PlaybackQualityOption("lossless", "Lossless"),
            PlaybackQualityOption("master", "Hi-Res"),
        )
        var selectedKey by mutableStateOf<String?>("lossless")
        var visible by mutableStateOf(true)
        val selections = mutableListOf<PlaybackQualityOption>()
        var dismissals = 0
        setThemedContent(style, dark) {
            if (visible) {
                NowPlayingQualityOptionsDialog(
                    title = "Audio quality",
                    selectedKey = selectedKey,
                    options = options,
                    onDismiss = { dismissals++; visible = false },
                    onSelect = { selections += it; selectedKey = it.key },
                )
            }
        }

        composeRule.onNodeWithText("Audio quality").assertIsDisplayed()
        assertSelectedQuality("Lossless")
        composeRule.onNodeWithText("Hi-Res").performClick()
        assertSelectedQuality("Hi-Res")
        composeRule.runOnIdle { assertEquals(listOf(options[2]), selections) }

        composeRule.runOnIdle { selectedKey = null }
        composeRule.onAllNodesWithText(text(CoreCommonR.string.common_selected)).assertCountEquals(0)
        composeRule.onNodeWithText(text(CoreCommonR.string.action_close)).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
        composeRule.onNodeWithText("Audio quality").assertDoesNotExist()
    }

    private fun assertSelectedQuality(label: String) {
        val selected = text(CoreCommonR.string.common_selected)
        composeRule.onAllNodesWithText(selected).assertCountEquals(1)
        composeRule.onNode(hasText(label) and hasText(selected)).assertIsDisplayed()
    }

    private fun assertFillChoices(style: UiStyle, dark: Boolean) {
        var enabled by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var dismissals = 0
        val confirmations = mutableListOf<List<Boolean>>()
        val labels = listOf(
            CoreCommonR.string.music_auto_fill_cover,
            CoreCommonR.string.music_auto_fill_title,
            CoreCommonR.string.music_auto_fill_artist,
            CoreCommonR.string.music_auto_fill_lyrics,
        ).map(::text)
        setThemedContent(style, dark) {
            if (visible) {
                FillOptionsDialog(
                    songResult = SongSearchInfo(
                        "track", "Night song", "Test artist", "03:00", MusicPlatform.CLOUD_MUSIC,
                        "Test album", null,
                    ),
                    onDismiss = { dismissals++; visible = false },
                    onConfirm = { cover, title, artist, lyrics ->
                        confirmations += listOf(cover, title, artist, lyrics)
                    },
                    enabled = enabled,
                )
            }
        }

        composeRule.onNodeWithText("Night song").assertIsDisplayed()
        composeRule.onNodeWithText("Test artist").assertIsDisplayed()
        assertFillCheckboxes(listOf(true, true, true, true), enabled = true)
        composeRule.onNodeWithText(text(CoreCommonR.string.action_confirm)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(listOf(true, true, true, true)), confirmations) }

        labels.forEachIndexed { index, label ->
            composeRule.onNodeWithText(label).performClick()
            composeRule.onAllNodes(isToggleable())[index].assertIsOff()
        }
        composeRule.onNodeWithText(labels[0]).performClick()
        composeRule.onNodeWithText(labels[3]).performClick()
        assertFillCheckboxes(listOf(true, false, false, true), enabled = true)
        composeRule.onNodeWithText(text(CoreCommonR.string.action_confirm)).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(listOf(true, true, true, true), listOf(true, false, false, true)), confirmations)
            enabled = false
        }

        assertFillCheckboxes(listOf(true, false, false, true), enabled = false)
        labels.forEach { label -> composeRule.onNodeWithText(label).performTouchInput { click() } }
        composeRule.onNodeWithText(text(CoreCommonR.string.action_confirm))
            .assertIsNotEnabled().performTouchInput { click() }
        assertFillCheckboxes(listOf(true, false, false, true), enabled = false)
        composeRule.runOnIdle { assertEquals(2, confirmations.size) }
        composeRule.onNodeWithText(text(CoreCommonR.string.action_cancel)).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
        composeRule.onNodeWithText("Night song").assertDoesNotExist()
    }

    private fun assertFillCheckboxes(expected: List<Boolean>, enabled: Boolean) {
        val checkboxes = composeRule.onAllNodes(isToggleable()).assertCountEquals(4)
        expected.forEachIndexed { index, checked ->
            if (checked) checkboxes[index].assertIsOn() else checkboxes[index].assertIsOff()
            if (enabled) checkboxes[index].assertIsEnabled() else checkboxes[index].assertIsNotEnabled()
        }
    }

    private fun assertLyricBehavior(style: UiStyle, dark: Boolean) {
        val offset = LyricBehaviorSheetState(0L)
        var translation by mutableStateOf(true)
        var phonetic by mutableStateOf(false)
        var hasTranslation by mutableStateOf(true)
        var hasPhonetic by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var dismissals = 0
        val translationChanges = mutableListOf<Boolean>()
        val phoneticChanges = mutableListOf<Boolean>()
        val committedOffsets = mutableListOf<Long>()
        setThemedContent(style, dark) {
            if (visible) {
                LyricBehaviorContent(
                    offsetState = offset,
                    hasTranslationLyrics = hasTranslation,
                    hasPhoneticLyrics = hasPhonetic,
                    showLyricTranslation = translation,
                    phoneticSwitchEnabled = translation && hasPhonetic,
                    phoneticSwitchChecked = translation && hasPhonetic && phonetic,
                    onTranslationChange = { translationChanges += it; translation = it },
                    onPhoneticChange = { phoneticChanges += it; phonetic = it },
                    onOffsetCommit = { committedOffsets += offset.currentOffset },
                    onDismiss = { dismissals++; visible = false },
                )
            }
        }

        composeRule.onNodeWithText(text(CoreCommonR.string.lyrics_adjust_behavior)).assertIsDisplayed()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(2)[0].assertIsOn()
        composeRule.onAllNodes(isToggleable())[1].assertIsOff().performClick()
        composeRule.onAllNodes(isToggleable())[1].assertIsOn()
        composeRule.onNodeWithText(text(CoreCommonR.string.settings_show_lyric_translation)).performClick()
        composeRule.onAllNodes(isToggleable())[0].assertIsOff()
        composeRule.onAllNodes(isToggleable())[1].assertIsNotEnabled().assertIsOff()
            .performTouchInput { click() }
        composeRule.onNodeWithText(text(CoreCommonR.string.lyrics_translation_use_phonetic_requires_translation))
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(false), translationChanges)
            assertEquals(listOf(true), phoneticChanges)
        }

        composeRule.onNodeWithText(text(CoreCommonR.string.settings_show_lyric_translation)).performClick()
        composeRule.onAllNodes(isToggleable())[1].assertIsEnabled().assertIsOn()
        composeRule.runOnIdle { hasPhonetic = false }
        composeRule.onAllNodes(isToggleable())[1].assertIsNotEnabled().assertIsOff()
            .performTouchInput { click() }
        composeRule.onNodeWithText(text(CoreCommonR.string.lyrics_translation_use_phonetic_unavailable))
            .assertIsDisplayed()

        composeRule.runOnIdle { hasTranslation = false; hasPhonetic = true }
        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeRule.onNodeWithText(text(CoreCommonR.string.lyrics_secondary_mode_phonetic)).performClick()
        composeRule.onAllNodes(isToggleable())[0].assertIsOff()
        composeRule.runOnIdle {
            assertEquals(listOf(false, true, false), translationChanges)
            assertEquals(listOf(true), phoneticChanges)
        }

        composeRule.onNodeWithText("0 ms").assertIsDisplayed()
        val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(250f) }
        composeRule.onNodeWithText("+250 ms").assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(-150f) }
        composeRule.onNodeWithText("-150 ms").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(250L, -150L), committedOffsets) }
        composeRule.onNodeWithText(text(CoreCommonR.string.action_done)).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
        composeRule.onNodeWithText(text(CoreCommonR.string.lyrics_adjust_behavior)).assertDoesNotExist()
    }

    private fun setThemedContent(style: UiStyle, dark: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false,
            ) {
                Surface(content = content)
            }
        }
    }

    private fun text(id: Int): String = context.getString(id)
}
