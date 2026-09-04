package moe.ouom.neriplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriAppStartupGlassGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appContentMountGateMountsImmediately() {
        composeRule.setContent {
            MaterialTheme {
                AppContentMountGate {
                    Text("frame mounted content")
                }
            }
        }

        composeRule.onNodeWithText("frame mounted content").assertIsDisplayed()
    }
}
