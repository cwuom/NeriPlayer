package moe.ouom.neriplayer.ui.haptic

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class HapticButtonsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    @Test
    fun `default wrappers forward each click once`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    HapticIconButton(onClick = { events += "icon" }) { Text("icon") }
                    HapticFilledIconButton(onClick = { events += "filled" }) { Text("filled") }
                    HapticButton(onClick = { events += "button" }) { Text("button") }
                    HapticTextButton(onClick = { events += "text" }) { Text("text") }
                    HapticOutlinedButton(onClick = { events += "outlined" }) { Text("outlined") }
                    HapticFloatingActionButton(onClick = { events += "fab" }) { Text("fab") }
                }
            }
        }

        val labels = listOf("icon", "filled", "button", "text", "outlined", "fab")
        labels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsEnabled().performClick()
        }

        assertEquals(labels, events)
    }

    @Test
    fun `explicit arguments reach the material buttons and disabled wrappers ignore clicks`() {
        val shape = RoundedCornerShape(4.dp)
        val padding = PaddingValues(2.dp)
        composeRule.setContent {
            MaterialTheme {
                Column {
                    HapticIconButton(
                        onClick = { events += "icon" },
                        modifier = Modifier.testTag("icon"),
                        enabled = false
                    ) { Text("icon") }
                    HapticFilledIconButton(
                        onClick = { events += "filled" },
                        modifier = Modifier.testTag("filled"),
                        enabled = true,
                        shape = shape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors()
                    ) { Text("filled") }
                    HapticButton(
                        onClick = { events += "button" },
                        modifier = Modifier.testTag("button"),
                        enabled = false,
                        shape = shape,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        contentPadding = padding
                    ) { Text("button") }
                    HapticTextButton(
                        onClick = { events += "text" },
                        modifier = Modifier.testTag("text"),
                        enabled = true,
                        colors = ButtonDefaults.textButtonColors(),
                        contentPadding = padding
                    ) { Text("text") }
                    HapticOutlinedButton(
                        onClick = { events += "outlined" },
                        modifier = Modifier.testTag("outlined"),
                        enabled = false,
                        shape = shape,
                        contentPadding = padding
                    ) { Text("outlined") }
                    HapticFloatingActionButton(
                        onClick = { events += "fab" },
                        modifier = Modifier.testTag("fab"),
                        hapticEffect = HapticFeedbackEffect.Heavy
                    ) { Text("fab") }
                }
            }
        }

        listOf("icon", "button", "outlined").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsNotEnabled().performClick()
        }
        listOf("filled", "text", "fab").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsEnabled().performClick()
        }

        assertEquals(listOf("filled", "text", "fab"), events)
    }
}
