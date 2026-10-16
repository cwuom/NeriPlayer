package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.activity.AppUiDensityRoot
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDpiDeviceScaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var appliedScale: Float? = null

    @Test
    fun tabletDialogExposesAndAppliesTwoTimesWithoutLosingTheSetting() {
        render(smallestWidth = 600, uiMode = Configuration.UI_MODE_TYPE_NORMAL, currentScale = 1f)
        assertRangeAndApply(maximum = 2f, steps = 27)
    }

    @Test
    fun carModeExposesTwoTimesEvenAtPhoneWidthAndAtNight() {
        render(320, Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES, 1f)
        assertRangeAndApply(maximum = 2f, steps = 27)
    }

    @Test
    fun phoneDialogClampsRestoredTabletValuesAndKeepsTheOldMaximum() {
        render(599, Configuration.UI_MODE_TYPE_NORMAL, 2f)
        composeRule.onNodeWithText("1.20x").assertIsDisplayed()
        assertRangeAndApply(maximum = 1.2f, steps = 11)
    }

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h480dp-land")
    fun cleanTwoTimesDialogKeepsActionsInsideItsShortLandscapeWindow() {
        render(600, Configuration.UI_MODE_TYPE_NORMAL, 2f, appScale = 2f, style = UiStyle.Clean)
        assertActionsInsideDialog()
        assertRangeAndApply(maximum = 2f, steps = 27)
    }

    @Test
    @Config(qualifiers = "sw320dp-w800dp-h480dp-land")
    fun materialTwoTimesCarDialogKeepsActionsInsideItsShortLandscapeWindow() {
        render(320, Configuration.UI_MODE_TYPE_CAR, 2f, appScale = 2f)
        assertActionsInsideDialog()
        assertRangeAndApply(maximum = 2f, steps = 27)
    }

    private fun assertActionsInsideDialog() {
        val dialogBounds = composeRule.onNode(isDialog()).getUnclippedBoundsInRoot()
        val application = ApplicationProvider.getApplicationContext<Application>()
        for (resource in listOf(CoreCommonR.string.action_apply, CoreCommonR.string.action_reset)) {
            val label = application.getString(resource)
            val action = composeRule.onNodeWithText(label)
            action.assertIsDisplayed()
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue("按钮必须完整位于对话框宽度内", bounds.left >= dialogBounds.left && bounds.right <= dialogBounds.right)
            assertTrue("底部动作不能被高密度正文挤出窗口", bounds.top >= dialogBounds.top && bounds.bottom <= dialogBounds.bottom)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            val diagnostics = layouts.joinToString { layout ->
                "text=${layout.layoutInput.text.text}, size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
                    "paragraphHeight=${layout.multiParagraph.height}, constraints=${layout.layoutInput.constraints}, " +
                    "lines=${(0 until layout.lineCount).map { line ->
                        "${layout.getLineLeft(line)}..${layout.getLineRight(line)} bottom=${layout.getLineBottom(line)}"
                    }}"
            }
            val layout = layouts.single()
            assertEquals(label, layout.layoutInput.text.text)
            assertEquals("动作标签必须完整显示为一行: $diagnostics", 1, layout.lineCount)
            assertFalse("动作标签不能因行数限制而截断: $diagnostics", layout.multiParagraph.didExceedMaxLines)
            // 语义适配可能保留父级 paragraph 宽度，实际文字是否越界应看每行位置
            for (line in 0 until layout.lineCount) {
                assertFalse("动作标签不能省略: $diagnostics", layout.isLineEllipsized(line))
                assertTrue("按钮文本不能横向裁剪: bounds=$bounds dialog=$dialogBounds $diagnostics",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("按钮文本不能纵向裁剪: bounds=$bounds dialog=$dialogBounds $diagnostics",
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
    }

    private fun assertRangeAndApply(maximum: Float, steps: Int) {
        val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.6f..maximum, range.range)
        assertEquals(steps, range.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(maximum) }
        val application = ApplicationProvider.getApplicationContext<Application>()
        composeRule.onNodeWithText(application.getString(CoreCommonR.string.action_apply)).performClick()
        composeRule.runOnIdle { assertEquals(maximum, checkNotNull(appliedScale), 0.001f) }
        composeRule.onNodeWithText(application.getString(CoreCommonR.string.action_reset)).performClick()
        composeRule.runOnIdle { assertEquals(1f, checkNotNull(appliedScale), 0f) }
    }

    private fun render(
        smallestWidth: Int,
        uiMode: Int,
        currentScale: Float,
        appScale: Float = 1f,
        style: UiStyle = UiStyle.Material
    ) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                smallestScreenWidthDp = smallestWidth
                this.uiMode = uiMode
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                AppUiDensityRoot(appScale) {
                    NeriTheme(
                        followSystemDark = false,
                        forceDark = false,
                        dynamicColor = false,
                        seedColorHex = "885522",
                        uiStyle = style,
                        animateColors = false
                    ) {
                        DpiSettingDialog(currentScale, onDismiss = {}, onApply = { appliedScale = it })
                    }
                }
            }
        }
    }
}
