package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DpiSettingDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appliedScales = mutableListOf<Float>()

    private fun string(id: Int): String = context.getString(id)

    private fun showDialog(style: UiStyle = UiStyle.Clean, fontScale: Float = 1f) {
        composeRule.setContent {
            var visible by remember { mutableStateOf(true) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = style,
                    animateColors = false
                ) {
                    if (visible) {
                        DpiSettingDialog(
                            currentScale = 0.8f,
                            onDismiss = { visible = false },
                            onApply = {
                                appliedScales += it
                                visible = false
                            }
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun assertButtonsAligned() {
        val reset = composeRule.onNodeWithText(string(CoreCommonR.string.action_reset)).getBoundsInRoot()
        val apply = composeRule.onNodeWithText(string(CoreCommonR.string.action_apply)).getBoundsInRoot()

        assertEquals(
            "button heights",
            apply.bottom.value - apply.top.value,
            reset.bottom.value - reset.top.value,
            0.5f
        )
        assertEquals("button tops", apply.top.value, reset.top.value, 0.5f)
        assertEquals("button bottoms", apply.bottom.value, reset.bottom.value, 0.5f)
        assertTrue("buttons must not overlap", reset.right <= apply.left)
    }

    @Test
    fun `clean reset and apply have matching bounds`() {
        showDialog()

        assertButtonsAligned()
    }

    @Test
    fun `clean reset and apply stay aligned with larger text`() {
        showDialog(fontScale = 1.3f)

        assertButtonsAligned()
    }

    @Test
    fun `reset immediately applies the default scale and closes the dialog`() {
        showDialog()

        composeRule.onNodeWithText(string(CoreCommonR.string.action_reset)).performClick()

        composeRule.runOnIdle { assertEquals(listOf(1f), appliedScales) }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_ui_scale)).assertDoesNotExist()
    }

    @Test
    fun `material reset also applies the default scale`() {
        showDialog(style = UiStyle.Material)

        composeRule.onNodeWithText(string(CoreCommonR.string.action_reset)).performClick()

        composeRule.runOnIdle { assertEquals(listOf(1f), appliedScales) }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_ui_scale)).assertDoesNotExist()
    }

    @Test
    fun `apply saves the selected scale`() {
        showDialog()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) {
                it(1.15f)
            }
        composeRule.onNodeWithText(string(CoreCommonR.string.action_apply)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, appliedScales.size)
            assertEquals(1.15f, appliedScales.single(), 0.001f)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_ui_scale)).assertDoesNotExist()
    }

    @Test
    fun `back discards the selected scale`() {
        showDialog()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performSemanticsAction(SemanticsActions.SetProgress) {
                it(1.15f)
            }

        composeRule.runOnIdle {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog() as? ComponentDialog)
            dialog.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.runOnIdle { assertTrue(appliedScales.isEmpty()) }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_ui_scale)).assertDoesNotExist()
    }
}
