package moe.ouom.neriplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriAppStartupGlassGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appContentMountGateMountsAfterFrameCallback() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                AppContentMountGate {
                    Text("frame mounted content")
                }
            }
        }

        composeRule.onAllNodesWithText("frame mounted content").assertCountEquals(0)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("frame mounted content").assertIsDisplayed()
    }

    @Test
    fun appContentMountGateFallsBackWhenFrameCallbackStalls() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                AppContentMountGate(
                    timeoutMillis = 1_000L,
                    awaitFirstFrame = { awaitCancellation() }
                ) {
                    Text("fallback mounted content")
                }
            }
        }

        composeRule.onAllNodesWithText("fallback mounted content").assertCountEquals(0)
        composeRule.mainClock.advanceTimeBy(1_001L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("fallback mounted content").assertIsDisplayed()
    }
}
