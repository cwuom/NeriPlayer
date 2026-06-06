package moe.ouom.neriplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Ignore("仅用于本地排查 Compose instrumentation 宿主问题")
@RunWith(AndroidJUnit4::class)
class ComposeRuleSanityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun composeRule_rendersPlainTextContent() {
        composeRule.setContent {
            MaterialTheme {
                Text("sanity-ready")
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("sanity-ready").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithText("sanity-ready").fetchSemanticsNodes().isNotEmpty()
        )
    }
}
