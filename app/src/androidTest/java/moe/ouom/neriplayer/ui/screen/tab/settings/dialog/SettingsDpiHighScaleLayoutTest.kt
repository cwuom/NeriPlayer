package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.activity.AppUiDensityRoot
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDpiHighScaleLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var appliedScale: Float? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        originalOrientation = composeRule.activity.requestedOrientation
    }

    @After
    fun restoreHostOrientation() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
    }

    @Test
    fun cleanTabletBoundaryRestoresTwoTimesAtItsDynamicMaximumWithVisibleActions() {
        render(UiStyle.Clean, 600, Configuration.UI_MODE_TYPE_NORMAL, Configuration.ORIENTATION_LANDSCAPE)
        assertActionsAndApply(expectedMaximum = 1f)
    }

    @Test
    fun cleanTabletPortraitRestoresTwoTimesAtItsDynamicMaximumWithVisibleActions() {
        render(UiStyle.Clean, 800, Configuration.UI_MODE_TYPE_NORMAL, Configuration.ORIENTATION_PORTRAIT)
        assertActionsAndApply(expectedMaximum = 1.3f)
    }

    @Test
    fun materialCarRestoresTwoTimesAtOnePointFiveWithVisibleActions() {
        render(UiStyle.Material, 320, Configuration.UI_MODE_TYPE_CAR, Configuration.ORIENTATION_LANDSCAPE)
        assertActionsAndApply(expectedMaximum = 1.5f)
    }

    private fun assertActionsAndApply(expectedMaximum: Float) {
        val dialog = composeRule.onNode(isDialog()).getUnclippedBoundsInRoot()
        for (resource in listOf(CoreCommonR.string.action_apply, CoreCommonR.string.action_reset)) {
            val label = context.getString(resource)
            val action = composeRule.onNodeWithText(label)
            action.assertIsDisplayed()
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue("动作必须完整位于对话框内", bounds.left >= dialog.left && bounds.right <= dialog.right &&
                bounds.top >= dialog.top && bounds.bottom <= dialog.bottom)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertEquals(label, layout.layoutInput.text.text)
            val diagnostics = "text=$label size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                "bounds=$bounds dialog=$dialog"
            assertEquals("动作标签必须完整显示为一行: $diagnostics", 1, layout.lineCount)
            assertFalse("动作标签不能因行数限制而截断: $diagnostics", layout.multiParagraph.didExceedMaxLines)
            // 语义适配可能保留父级 paragraph 宽度，实际文字是否越界应看每行位置
            for (line in 0 until layout.lineCount) {
                val lineBounds = "left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} " +
                    "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)}"
                assertFalse("动作标签不能省略: $diagnostics $lineBounds", layout.isLineEllipsized(line))
                assertTrue("动作文字不能横向裁剪: $diagnostics $lineBounds",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("动作文字不能纵向裁剪: $diagnostics $lineBounds",
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
        val range = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(expectedMaximum, range.range.endInclusive, 0f)
        assertEquals(expectedMaximum, range.current, 0f)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_apply)).performClick()
        composeRule.runOnIdle { assertEquals(expectedMaximum, checkNotNull(appliedScale), 0f) }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_reset)).performClick()
        composeRule.runOnIdle { assertEquals(1f, checkNotNull(appliedScale), 0f) }
    }

    private fun render(style: UiStyle, smallestWidth: Int, uiMode: Int, orientation: Int) {
        val requested = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation == orientation
        }
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                smallestScreenWidthDp = smallestWidth
                this.uiMode = uiMode
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                AppUiDensityRoot(2f) {
                    NeriTheme(
                        followSystemDark = false,
                        forceDark = false,
                        dynamicColor = false,
                        seedColorHex = "885522",
                        uiStyle = style,
                        animateColors = false
                    ) {
                        DpiSettingDialog(2f, onDismiss = {}, onApply = { appliedScale = it })
                    }
                }
            }
        }
    }
}
