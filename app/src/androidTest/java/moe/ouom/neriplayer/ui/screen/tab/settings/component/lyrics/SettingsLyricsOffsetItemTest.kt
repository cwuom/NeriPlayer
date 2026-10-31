package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsLyricsOffsetItemTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var currentOffset by mutableLongStateOf(1_000L)
    private var otherOffset by mutableLongStateOf(-750L)
    private val savedOffsets = mutableListOf<Long>()
    private val otherSavedOffsets = mutableListOf<Long>()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun materialValueOpensEditorAndSavesSignedZeroAndStepAlignedValues() =
        verifyManualEditing(UiStyle.Material)

    @Test
    fun cleanValueOpensEditorAndSavesSignedZeroAndStepAlignedValues() =
        verifyManualEditing(UiStyle.Clean)

    @Test
    fun materialCancelDiscardsTheDraft() = verifyCancel(UiStyle.Material)

    @Test
    fun cleanCancelDiscardsTheDraft() = verifyCancel(UiStyle.Clean)

    @Test
    fun materialRejectsInvalidAndOutOfRangeDrafts() = verifyInvalidDrafts(UiStyle.Material)

    @Test
    fun cleanRejectsInvalidAndOutOfRangeDrafts() = verifyInvalidDrafts(UiStyle.Clean)

    @Test
    fun materialResetOnlyChangesItsOwnSource() = verifyReset(UiStyle.Material)

    @Test
    fun cleanResetOnlyChangesItsOwnSource() = verifyReset(UiStyle.Clean)

    @Test
    fun materialExternalOffsetUpdateReachesTheValueAndNextEditor() =
        verifyExternalUpdate(UiStyle.Material)

    @Test
    fun cleanExternalOffsetUpdateReachesTheValueAndNextEditor() =
        verifyExternalUpdate(UiStyle.Clean)

    @Test
    fun materialSliderExposesFiftyMillisecondStepsAndSavesAccessibleProgress() =
        verifySliderAccessibility(UiStyle.Material)

    @Test
    fun cleanSliderExposesFiftyMillisecondStepsAndSavesAccessibleProgress() =
        verifySliderAccessibility(UiStyle.Clean)

    @Test
    fun materialSignToggleAndImeDoneSaveExactlyOnce() = verifySignToggleAndImeDone(UiStyle.Material)

    @Test
    fun cleanSignToggleAndImeDoneSaveExactlyOnce() = verifySignToggleAndImeDone(UiStyle.Clean)

    private fun verifySignToggleAndImeDone(style: UiStyle) {
        render(style)
        openEditor()
        composeRule.onNodeWithTag(INPUT_TAG).performTextReplacement("750")

        composeRule.onNodeWithText("±").assertIsEnabled().performClick()

        composeRule.onNodeWithTag(INPUT_TAG).assert(editableText("-750"))
        composeRule.runOnIdle {
            assertEquals(1_000L, currentOffset)
            assertEquals(emptyList<Long>(), savedOffsets)
        }
        composeRule.onNodeWithTag(INPUT_TAG).performImeAction()

        composeRule.onNodeWithTag(INPUT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("-750 ms").assertExists()
        composeRule.runOnIdle {
            assertEquals(-750L, currentOffset)
            assertEquals(listOf(-750L), savedOffsets)
        }
    }

    private fun verifySliderAccessibility(style: UiStyle) {
        render(style)
        val slider = composeRule.onNodeWithContentDescription("Primary lyric source")
        val initialRange = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(199, initialRange.steps)
        assertEquals(-5_000f..5_000f, initialRange.range)
        assertEquals(1_000f, initialRange.current, 0.01f)

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1_050f) }

        composeRule.onNodeWithText("+1050 ms").assertExists()
        val updatedRange = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(199, updatedRange.steps)
        assertEquals(-5_000f..5_000f, updatedRange.range)
        assertEquals(1_050f, updatedRange.current, 0.01f)
        composeRule.runOnIdle {
            assertEquals(1_050L, currentOffset)
            assertEquals(listOf(1_050L), savedOffsets)
        }
    }

    private fun verifyManualEditing(style: UiStyle) {
        render(style)
        val expectedSaved = mutableListOf<Long>()
        val cases = listOf(
            "+1250" to 1_250L,
            "-750" to -750L,
            "0" to 0L,
            "1264" to 1_250L,
            "-776" to -800L,
            "5000" to 5_000L,
            "-5000" to -5_000L
        )

        cases.forEach { (draft, expected) ->
            openEditor()
            composeRule.onNodeWithTag(INPUT_TAG).performTextReplacement(draft)
            composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm))
                .assertIsEnabled().performClick()
            composeRule.onNodeWithTag(INPUT_TAG).assertDoesNotExist()
            composeRule.onNodeWithText(displayOffset(expected)).assertExists()
            expectedSaved += expected
            composeRule.runOnIdle {
                assertEquals(expected, currentOffset)
                assertEquals(expectedSaved, savedOffsets)
            }
        }
    }

    private fun verifyCancel(style: UiStyle) {
        render(style)
        openEditor()
        composeRule.onNodeWithTag(INPUT_TAG).performTextReplacement("-750")

        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()

        composeRule.onNodeWithTag(INPUT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("+1000 ms").assertExists()
        composeRule.runOnIdle {
            assertEquals(1_000L, currentOffset)
            assertEquals(emptyList<Long>(), savedOffsets)
        }
        openEditor()
        composeRule.onNodeWithTag(INPUT_TAG).assert(editableText("1000"))
    }

    private fun verifyInvalidDrafts(style: UiStyle) {
        render(style)
        openEditor()

        listOf("", "+", "-", "abc", "12.5", "5001", "-5001", "9223372036854775808")
            .forEach { draft ->
                composeRule.onNodeWithTag(INPUT_TAG).performTextReplacement(draft)
                composeRule.onNodeWithText(string(CoreCommonR.string.action_confirm))
                    .assertIsNotEnabled()
                composeRule.onNodeWithTag(INPUT_TAG).assertExists()
                composeRule.runOnIdle {
                    assertEquals(1_000L, currentOffset)
                    assertEquals(emptyList<Long>(), savedOffsets)
                }
            }

        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        composeRule.onNodeWithText("+1000 ms").assertExists()
    }

    private fun verifyReset(style: UiStyle) {
        render(style, withOtherSource = true)
        val reset = hasContentDescription(string(CoreCommonR.string.settings_lyrics_offset_reset))

        composeRule.onNode(reset and hasAnyAncestor(hasTestTag(PRIMARY_ROW_TAG)))
            .assertIsEnabled().performClick()

        composeRule.onNodeWithText("0 ms").assertExists()
        composeRule.onNodeWithText("-750 ms").assertExists()
        composeRule.onNode(reset and hasAnyAncestor(hasTestTag(PRIMARY_ROW_TAG)))
            .assertIsNotEnabled()
        composeRule.onNode(reset and hasAnyAncestor(hasTestTag(OTHER_ROW_TAG)))
            .assertIsEnabled()
        composeRule.runOnIdle {
            assertEquals(listOf(0L), savedOffsets)
            assertEquals(0L, currentOffset)
            assertEquals(-750L, otherOffset)
            assertEquals(emptyList<Long>(), otherSavedOffsets)
        }
    }

    private fun verifyExternalUpdate(style: UiStyle) {
        render(style)
        composeRule.onNodeWithText("+1000 ms").assertExists()

        composeRule.runOnIdle { currentOffset = -1_750L }

        composeRule.onNodeWithText("-1750 ms").assertExists()
        composeRule.onNodeWithText("+1000 ms").assertDoesNotExist()
        openEditor()
        composeRule.onNodeWithTag(INPUT_TAG).assert(editableText("-1750"))
        composeRule.runOnIdle { assertEquals(emptyList<Long>(), savedOffsets) }
    }

    private fun openEditor() {
        val displayedValue = composeRule.runOnIdle { displayOffset(currentOffset) }
        composeRule.onNodeWithText(displayedValue).assertHasClickAction().performClick()
        composeRule.onNodeWithTag(INPUT_TAG).assertExists()
    }

    private fun render(style: UiStyle, withOtherSource: Boolean = false) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = style,
                animateColors = false
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.testTag(PRIMARY_ROW_TAG)) {
                        LyricsOffsetSliderListItem(
                            targetId = "primary-source",
                            title = "Primary lyric source",
                            description = "Primary source offset",
                            offsetMs = currentOffset,
                            defaultOffsetMs = 0L,
                            onOffsetChange = {
                                currentOffset = it
                                savedOffsets += it
                            }
                        )
                    }
                    if (withOtherSource) {
                        Column(Modifier.testTag(OTHER_ROW_TAG)) {
                            LyricsOffsetSliderListItem(
                                targetId = "other-source",
                                title = "Other lyric source",
                                description = "Other source offset",
                                offsetMs = otherOffset,
                                defaultOffsetMs = 250L,
                                onOffsetChange = {
                                    otherOffset = it
                                    otherSavedOffsets += it
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun editableText(value: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))

    private fun displayOffset(offset: Long): String =
        "${if (offset > 0) "+" else ""}$offset ms"
}

private const val INPUT_TAG = "lyric-offset-input"
private const val PRIMARY_ROW_TAG = "primary-lyric-offset-row"
private const val OTHER_ROW_TAG = "other-lyric-offset-row"
