package moe.ouom.neriplayer.ui.component.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeColorPickerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reportedHex = mutableListOf<String>()

    private val sliderMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun showPicker(initialHex: String) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "0061A4",
                uiStyle = UiStyle.Clean,
                animateColors = false
            ) {
                ThemeColorPicker(
                    initialHex = initialHex,
                    onColorChanged = { reportedHex += it }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun sliderRanges() = composeRule.onAllNodes(sliderMatcher)
        .fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.ProgressBarRangeInfo] }

    private fun setSlider(index: Int, value: Float) {
        composeRule.onAllNodes(sliderMatcher)[index]
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        composeRule.waitForIdle()
    }

    @Test
    fun `initial hex is normalized and reported on first composition`() {
        showPicker("0061a4")

        assertEquals(listOf("0061A4"), reportedHex)
        composeRule.onNodeWithText("#0061A4").assertIsDisplayed()
    }

    @Test
    fun `malformed initial hex falls back to the default seed`() {
        showPicker("zzzzzz")

        assertEquals(listOf("0061A4"), reportedHex)
        composeRule.onNodeWithText("#0061A4").assertIsDisplayed()
    }

    @Test
    fun `rgb tab exposes 0 to 255 sliders and reports the composed color`() {
        showPicker("0061A4")
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.color_picker_rgb)).performClick()
        composeRule.waitForIdle()

        val ranges = sliderRanges()
        assertEquals(3, ranges.size)
        ranges.forEach { assertEquals(0f..255f, it.range) }

        setSlider(0, 255f)
        setSlider(1, 0f)
        setSlider(2, 0f)

        assertEquals("FF0000", reportedHex.last())
        composeRule.onNodeWithText("#FF0000").assertIsDisplayed()
    }

    @Test
    fun `hsv sliders stay in hsv ranges and keep rgb in sync`() {
        showPicker("0061A4")

        val ranges = sliderRanges()
        assertEquals(3, ranges.size)
        assertEquals(0f..360f, ranges[0].range)
        assertEquals(0f..1f, ranges[1].range)
        assertEquals(0f..1f, ranges[2].range)

        setSlider(0, 120f)
        setSlider(1, 1f)
        setSlider(2, 1f)

        assertEquals("00FF00", reportedHex.last())
        composeRule.onNodeWithText("#00FF00").assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.color_picker_rgb)).performClick()
        composeRule.waitForIdle()
        val rgb = sliderRanges()
        assertEquals(0f, rgb[0].current, 0.5f)
        assertEquals(255f, rgb[1].current, 0.5f)
        assertEquals(0f, rgb[2].current, 0.5f)
    }

    @Test
    fun `external hex change re-syncs hsv and rgb state`() {
        var initialHex by mutableStateOf("0061A4")
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "0061A4",
                uiStyle = UiStyle.Clean,
                animateColors = false
            ) {
                ThemeColorPicker(
                    initialHex = initialHex,
                    onColorChanged = { reportedHex += it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.color_picker_rgb)).performClick()
        composeRule.waitForIdle()
        setSlider(0, 255f)
        setSlider(1, 0f)
        setSlider(2, 0f)
        assertEquals("FF0000", reportedHex.last())

        initialHex = "00FF00"
        composeRule.waitForIdle()
        assertEquals("00FF00", reportedHex.last())
        composeRule.onNodeWithText("#00FF00").assertIsDisplayed()
    }
}
