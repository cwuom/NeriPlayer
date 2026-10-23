package moe.ouom.neriplayer.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongEditableTextField
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingSongEditTextFieldComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var hostView: View

    @Test
    fun cleanLightFieldKeepsItsFillAndEditingActions() = verifyField(UiStyle.Clean, false)

    @Test
    fun cleanDarkFieldKeepsItsFillAndEditingActions() = verifyField(UiStyle.Clean, true)

    @Test
    fun materialLightFieldKeepsItsOutlineAndEditingActions() = verifyField(UiStyle.Material, false)

    @Test
    fun materialDarkFieldKeepsItsOutlineAndEditingActions() = verifyField(UiStyle.Material, true)

    private fun verifyField(style: UiStyle, dark: Boolean) {
        val draft = mutableStateOf("Original song")
        val enabled = mutableStateOf(true)
        var restores = 0
        var clearFocus: () -> Unit = {}
        composeRule.setContent {
            NeriTheme(false, dark, false, "6750A4", uiStyle = style, animateColors = false) {
                val view = LocalView.current
                val focusManager = LocalFocusManager.current
                SideEffect {
                    hostView = view
                    clearFocus = { focusManager.clearFocus(force = true) }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(24.dp)) {
                        EditSongEditableTextField(
                            value = draft.value,
                            onValueChange = { draft.value = it },
                            label = "Song title",
                            restoreDescription = "Restore song title",
                            enabled = enabled.value,
                            onRestore = { draft.value = "Original song"; restores++ },
                            modifier = Modifier.testTag("song-edit-field"),
                            placeholder = { Text("Enter song title") }
                        )
                    }
                }
            }
        }

        val field = composeRule.onNodeWithTag("song-edit-field")
        val restore = composeRule.onNodeWithContentDescription("Restore song title")
        field.assertIsDisplayed().assertIsEnabled().assertTextContains("Original song")
        restore.assertIsEnabled()
        composeRule.onNodeWithText("Enter song title").assertDoesNotExist()
        assertFieldAppearance(style)

        field.performClick().assertIsFocused().performTextReplacement("Edited song")
        composeRule.runOnIdle { assertEquals("Edited song", draft.value) }
        assertFieldAppearance(style)
        restore.performClick()
        composeRule.runOnIdle {
            assertEquals("Original song", draft.value)
            assertEquals(1, restores)
        }
        field.assertTextContains("Original song")

        field.performTextReplacement("")
        composeRule.onNodeWithText("Enter song title").assertIsDisplayed()
        composeRule.onNodeWithText("Song title", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            clearFocus()
            enabled.value = false
        }
        field.assertIsNotEnabled()
        restore.assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals("", draft.value)
            assertEquals(1, restores)
        }
    }

    private fun assertFieldAppearance(style: UiStyle) {
        val label = composeRule.onNodeWithText("Song title", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val field = composeRule.onNodeWithTag("song-edit-field").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
            try {
                hostView.draw(Canvas(bitmap))
                val labelY = (label.bottom - 2f).toInt()
                val besideLabel = bitmap.getPixel((label.right + 2f).toInt(), labelY)
                val insideField = bitmap.getPixel((label.right + 14f).toInt(), labelY)
                assertTrue("标签旁应保留连续底色", colorDistance(besideLabel, insideField) < 0.025f)
                val body = bitmap.getPixel((field.right - 60f).toInt(), field.center.y.toInt())
                val background = bitmap.getPixel(8, 8)
                if (style == UiStyle.Clean) {
                    assertTrue("Clean 输入框应保留填充", colorDistance(body, background) > 0.025f)
                } else {
                    assertTrue("Material 输入框应保留透明内容区", colorDistance(body, background) < 0.015f)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun colorDistance(first: Int, second: Int): Float = max(
        max(abs(AndroidColor.red(first) - AndroidColor.red(second)),
            abs(AndroidColor.green(first) - AndroidColor.green(second))),
        abs(AndroidColor.blue(first) - AndroidColor.blue(second))
    ) / 255f
}
