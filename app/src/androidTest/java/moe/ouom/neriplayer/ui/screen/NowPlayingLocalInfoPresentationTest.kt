package moe.ouom.neriplayer.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongEditableTextField
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricBehaviorContent
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricBehaviorSheetState
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class NowPlayingLocalInfoPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test fun cleanLightSongLabelsKeepTheFieldFillContinuous() = verifySongLabels(UiStyle.Clean, false)
    @Test fun cleanDarkSongLabelsKeepTheFieldFillContinuous() = verifySongLabels(UiStyle.Clean, true)
    @Test fun materialLightSongLabelsKeepTheFieldFillContinuous() = verifySongLabels(UiStyle.Material, false)
    @Test fun materialDarkSongLabelsKeepTheFieldFillContinuous() = verifySongLabels(UiStyle.Material, true)

    @Test fun cleanLightLyricTogglesShareOneCardAndKeepTheirActions() = verifyLyricCard(UiStyle.Clean, false)
    @Test fun cleanDarkLyricTogglesShareOneCardAndKeepTheirActions() = verifyLyricCard(UiStyle.Clean, true)
    @Test fun materialLightLyricTogglesShareOneCardAndKeepTheirActions() = verifyLyricCard(UiStyle.Material, false)
    @Test fun materialDarkLyricTogglesShareOneCardAndKeepTheirActions() = verifyLyricCard(UiStyle.Material, true)

    private fun verifySongLabels(style: UiStyle, dark: Boolean) {
        val draft = mutableStateOf("Synthetic song")
        val enabled = mutableStateOf(true)
        var restores = 0
        var clearFocus: () -> Unit = {}
        composeRule.setContent {
            NeriTheme(false, dark, false, "6750A4", uiStyle = style, animateColors = false) {
                FittedTestViewport(360.dp, 640.dp, layoutOnlyTextInput = true) {
                    val focusManager = LocalFocusManager.current
                    SideEffect { clearFocus = { focusManager.clearFocus(force = true) } }
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(24.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            EditSongEditableTextField(
                                value = draft.value,
                                onValueChange = { draft.value = it },
                                label = "Song title",
                                restoreDescription = "Restore song title",
                                enabled = enabled.value,
                                onRestore = { restores++ },
                                modifier = Modifier.testTag("song-field")
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        capture("song-label-${style.name}-$dark-unfocused")
        assertLabelHasNoBackgroundNotch(style, dark)
        val field = composeRule.onNodeWithTag("song-field")
        field.performClick().performTextReplacement("Edited synthetic song")
        composeRule.waitForIdle()
        capture("song-label-${style.name}-$dark-focused")
        assertLabelHasNoBackgroundNotch(style, dark)
        composeRule.runOnIdle { assertEquals("Edited synthetic song", draft.value) }
        composeRule.onNodeWithContentDescription("Restore song title").performClick()
        composeRule.runOnIdle {
            clearFocus()
            enabled.value = false
            assertEquals(1, restores)
        }
        field.assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Restore song title").assertIsNotEnabled()
    }

    private fun assertLabelHasNoBackgroundNotch(style: UiStyle, dark: Boolean) {
        val label = composeRule.onNodeWithText("Song title", useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val field = composeRule.onNodeWithTag("song-field").fetchSemanticsNode().boundsInRoot
        val image = composeRule.onRoot().captureToImage().toPixelMap()
        val scale = field.width / 312f
        val y = (label.bottom - 2f * scale).toInt()
        val besideLabel = image[(label.right + 2f * scale).toInt(), y]
        val insideField = image[(label.right + 14f * scale).toInt(), y]
        assertTrue("标签旁不能挖掉输入框底色: style=$style dark=$dark label=$label colors=$besideLabel/$insideField",
            colorDistance(besideLabel, insideField) < 0.025f)
    }

    private fun verifyLyricCard(style: UiStyle, dark: Boolean) {
        val translation = mutableStateOf(true)
        val phonetic = mutableStateOf(false)
        val available = mutableStateOf(true)
        var translationChanges = 0
        var phoneticChanges = 0
        composeRule.setContent {
            NeriTheme(false, dark, false, "6750A4", uiStyle = style, animateColors = false) {
                FittedTestViewport(360.dp, 640.dp) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            LyricBehaviorContent(
                                offsetState = LyricBehaviorSheetState(0),
                                hasTranslationLyrics = true,
                                hasPhoneticLyrics = available.value,
                                showLyricTranslation = translation.value,
                                phoneticSwitchEnabled = translation.value && available.value,
                                phoneticSwitchChecked = translation.value && available.value && phonetic.value,
                                onTranslationChange = { translation.value = it; translationChanges++ },
                                onPhoneticChange = { phonetic.value = it; phoneticChanges++ },
                                onOffsetCommit = {},
                                onDismiss = {}
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        capture("lyric-card-${style.name}-$dark")
        val translationTitle = resource(CoreCommonR.string.settings_show_lyric_translation)
        val phoneticTitle = resource(CoreCommonR.string.lyrics_translation_use_phonetic)
        val first = composeRule.onNodeWithText(translationTitle).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithText(phoneticTitle).fetchSemanticsNode().boundsInRoot
        assertEquals("两行应连续排列", first.bottom, second.top, 1f)
        assertContinuousCardAtSeam(first, second, style, dark)

        composeRule.onNodeWithText(phoneticTitle).performClick()
        toggle(phoneticTitle).assertIsOn()
        composeRule.onNodeWithText(translationTitle).performClick()
        toggle(translationTitle).assertIsOff()
        toggle(phoneticTitle).assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithText(resource(CoreCommonR.string.lyrics_translation_use_phonetic_requires_translation))
            .assertIsDisplayed()
        composeRule.onNodeWithText(phoneticTitle).performClick()
        composeRule.runOnIdle { assertEquals(1, phoneticChanges) }
        toggle(translationTitle).performClick().assertIsOn()
        toggle(phoneticTitle).assertIsEnabled().performClick().assertIsOff()
        composeRule.runOnIdle {
            assertEquals(2, translationChanges)
            assertEquals(2, phoneticChanges)
            available.value = false
        }
        toggle(phoneticTitle).assertIsNotEnabled()
        composeRule.onNodeWithText(resource(CoreCommonR.string.lyrics_translation_use_phonetic_unavailable))
            .assertIsDisplayed()
    }

    private fun toggle(title: String): SemanticsNodeInteraction {
        val rowId = composeRule.onNodeWithText(title).fetchSemanticsNode().id
        return composeRule.onNode(
            isToggleable() and hasAnyAncestor(SemanticsMatcher("歌词设置行 $title") { it.id == rowId }),
            useUnmergedTree = true
        )
    }

    private fun assertContinuousCardAtSeam(first: Rect, second: Rect, style: UiStyle, dark: Boolean) {
        val image = composeRule.onRoot().captureToImage().toPixelMap()
        val scale = first.width / if (style == UiStyle.Clean) 320f else 312f
        for (y in listOf(first.bottom - 2f * scale, second.top + 2f * scale)) {
            val edge = image[(first.left + 2f * scale).toInt(), y.toInt()]
            val center = image[first.center.x.toInt(), y.toInt()]
            assertTrue("两行接缝边缘不能出现各自的圆角缺口: style=$style dark=$dark colors=$edge/$center",
                colorDistance(edge, center) < 0.015f)
        }
    }

    private fun colorDistance(first: Color, second: Color): Float =
        max(max(abs(first.red - second.red), abs(first.green - second.green)), abs(first.blue - second.blue))

    private fun resource(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() } ?: return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "$prefix-$stage.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
