package moe.ouom.neriplayer.ui.component.overlay

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
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
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDialogControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialActionsFitLargeLabelsAndOptionsExposeSelection() = checkControls(UiStyle.Material)

    @Test fun cleanActionsFitLargeLabelsAndOptionsExposeSelection() = checkControls(UiStyle.Clean)

    private fun checkControls(style: UiStyle) {
        val action = "Apply\nsettings"
        var confirmed = false
        compose.setContent {
            NeriTheme(false, false, false, "885522", uiStyle = style, animateColors = false) {
                var useSocks by remember { mutableStateOf(false) }
                MiuixSettingsDialog(
                    onDismissRequest = {},
                    title = { Text("Proxy") },
                    text = {
                        Column {
                            MiuixSettingsChoiceRow("HTTP", !useSocks, { useSocks = false })
                            MiuixSettingsChoiceRow("SOCKS5", useSocks, { useSocks = true })
                        }
                    },
                    confirmButton = {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                            MiuixSettingsTextButton(onClick = { confirmed = true }, text = { Text(action) })
                        }
                    }
                )
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onNodeWithText("HTTP").assert(radio).assertIsSelected()
        compose.onNodeWithText("SOCKS5").assert(radio).assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("HTTP").assertIsNotSelected()

        val layouts = mutableListOf<TextLayoutResult>()
        val textNode = compose.onNodeWithText(action, useUnmergedTree = true)
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val label = layouts.single()
        val text = textNode.fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText(action).fetchSemanticsNode().boundsInRoot
        val details = "text=$text, button=$button, measured=${label.size}, " +
            "paragraph=${label.multiParagraph.width}x${label.multiParagraph.height}"
        assertEquals(1.8f, label.layoutInput.density.fontScale, 0f)
        assertEquals(details, 2, label.lineCount)
        assertFalse(details, label.multiParagraph.didExceedMaxLines)
        // 按钮文字可以比段落宽度窄，检查每行实际占用的区域
        action.lines().forEachIndexed { line, expected ->
            assertFalse(details, label.isLineEllipsized(line))
            assertEquals(details, expected, action.substring(label.getLineStart(line), label.getLineEnd(line, visibleEnd = true)))
            assertTrue(details, label.getLineLeft(line) >= -1f && label.getLineRight(line) <= text.width + 1f)
            assertTrue(details, label.getLineTop(line) >= -1f && label.getLineBottom(line) <= text.height + 1f)
        }
        assertTrue(details, text.left >= button.left - 1f && text.right <= button.right + 1f)
        assertTrue(details, text.top >= button.top - 1f && text.bottom <= button.bottom + 1f)
        compose.onNodeWithText(action).performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }
}
