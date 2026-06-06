package moe.ouom.neriplayer.activity

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisclaimerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun agreeButton_remainsDisabledUntilCountdownFinishes_thenCallsCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.disclaimer_title)
        val initialButtonLabel = context.getString(R.string.disclaimer_read_countdown, 1)
        val agreeButtonLabel = context.getString(R.string.disclaimer_agree_countdown)
        var agreed = false

        composeRule.setContent {
            MaterialTheme {
                DisclaimerScreen(
                    onAgree = { agreed = true },
                    initialCountdownSeconds = 1
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            hasText(title)
        }
        composeRule.onNodeWithText(initialButtonLabel).assertIsNotEnabled()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            hasText(agreeButtonLabel)
        }

        composeRule.onNodeWithText(agreeButtonLabel)
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertTrue("倒计时结束后点击同意应触发回调", agreed)
        }
    }

    private fun hasText(text: String): Boolean {
        return runCatching {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }
}
