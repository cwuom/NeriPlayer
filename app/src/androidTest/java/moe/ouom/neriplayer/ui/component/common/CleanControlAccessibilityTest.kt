package moe.ouom.neriplayer.ui.component.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CleanControlAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        style: UiStyle = UiStyle.Clean,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides direction
            ) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = style,
                    animateColors = false
                ) {
                    Box(Modifier.width(320.dp)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun checkLargeLabels(labels: List<String>) {
        show(fontScale = 2f) {
            CleanSegmentedTabs(labels, selectedIndex = 0, onSelected = {})
        }
        labels.take(2).forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue("label must expose its layout", results.isNotEmpty())
            assertFalse("$label must fit vertically at 200% text", results.single().didOverflowHeight)
        }
    }

    @Test
    fun fixedTabsFitLargeText() = checkLargeLabels(listOf("甲", "乙"))

    @Test
    fun scrollingTabsFitLargeText() = checkLargeLabels(listOf("甲", "乙", "丙", "丁", "戊"))

    @Test
    fun scrollingTabsExposeSelectionOnTheClickableTab() {
        val labels = listOf("Presets", "Equalizer", "Tone", "Space", "Dynamics", "Speaker", "Speed", "Advanced")
        val selected = mutableStateOf(0)
        show {
            CleanSegmentedTabs(
                labels = labels,
                selectedIndex = selected.value,
                onSelected = { selected.value = it },
                modifier = Modifier.testTag("scrolling-tabs")
            )
        }

        labels.forEachIndexed { index, label ->
            composeRule.onNodeWithTag("scrolling-tabs").performScrollToNode(hasText(label))
            val tab = composeRule.onNode(hasText(label) and hasClickAction())
            tab.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, index == 0))
                .performClick()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            composeRule.runOnIdle {
                assertTrue("clicking $label must select its section", selected.value == index)
            }
        }
    }

    private fun checkSlider(direction: LayoutDirection) {
        var primary = Color.Unspecified
        show(direction = direction) {
            primary = MaterialTheme.colorScheme.primary
            MiuixSettingsSlider(
                value = 0.25f,
                onValueChange = {},
                steps = 3,
                modifier = Modifier.testTag("slider")
            )
        }
        val pixels = composeRule.onNodeWithTag("slider").captureToImage().toPixelMap()
        val left = pixels[(pixels.width * 0.15f).toInt(), pixels.height / 2]
        val right = pixels[(pixels.width * 0.85f).toInt(), pixels.height / 2]
        fun Color.isActive() = abs(red - primary.red) + abs(green - primary.green) +
            abs(blue - primary.blue) < 0.05f
        assertTrue("the active track must start on the layout's start edge",
            if (direction == LayoutDirection.Rtl) right.isActive() else left.isActive())
        assertFalse("the other edge must remain inactive",
            if (direction == LayoutDirection.Rtl) left.isActive() else right.isActive())
    }

    @Test
    fun sliderTrackStartsAtLeftInLtr() = checkSlider(LayoutDirection.Ltr)

    @Test
    fun sliderTrackStartsAtRightInRtl() = checkSlider(LayoutDirection.Rtl)

    private fun checkChoice(style: UiStyle, multiSelect: Boolean) {
        val enabled = mutableStateOf(true)
        show(style = style) {
            val selected = remember { mutableStateOf(false) }
            NeriChoiceChip(
                selected = selected.value,
                onClick = { selected.value = !selected.value },
                label = "选择",
                enabled = enabled.value,
                multiSelect = multiSelect
            )
        }
        val chip = composeRule.onNodeWithText("选择")
        chip.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Role, if (multiSelect) Role.Checkbox else Role.RadioButton
        ))
        fun checked(value: Boolean) = if (multiSelect) {
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(value))
        } else {
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, value)
        }
        chip.assert(checked(false)).performClick().assert(checked(true))
        chip.performClick().assert(checked(false))
        composeRule.runOnIdle { enabled.value = false }
        chip.assertIsNotEnabled().performClick().assert(checked(false))
    }

    @Test
    fun cleanSingleChoiceAnnouncesSelection() = checkChoice(UiStyle.Clean, multiSelect = false)

    @Test
    fun cleanMultipleChoiceAnnouncesCheckedState() = checkChoice(UiStyle.Clean, multiSelect = true)

    @Test
    fun materialSingleChoiceAnnouncesSelection() = checkChoice(UiStyle.Material, multiSelect = false)

    @Test
    fun materialMultipleChoiceAnnouncesCheckedState() = checkChoice(UiStyle.Material, multiSelect = true)
}
