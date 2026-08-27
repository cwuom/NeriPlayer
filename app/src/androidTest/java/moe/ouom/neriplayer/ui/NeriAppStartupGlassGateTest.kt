package moe.ouom.neriplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import moe.ouom.neriplayer.R
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
        composeRule.onNodeWithText("frame mounted content").assertDoesNotExist()

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("frame mounted content").assertIsDisplayed()
    }

    @Test
    fun appContentMountGateFailsOpenWhenFrameCallbackNeverArrives() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                AppContentMountGate(
                    timeoutMillis = 1_000L,
                    awaitFirstFrame = { awaitCancellation() }
                ) {
                    Text("mounted content")
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("mounted content").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(1_001L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("mounted content").assertIsDisplayed()
    }

    @Test
    fun waitingGateShowsVisibleIdentityAndProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                StartupGlassGate(
                    isDark = false,
                    baseBlurEnabled = true,
                    backgroundEffectReady = false,
                    contentEffectReady = false,
                    timeoutMillis = 10_000L
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithTag(STARTUP_GLASS_GATE_PROGRESS_TAG).assertIsDisplayed()
    }

    @Test
    fun gateReleasesWhenAnEffectBecomesReady() {
        val effectReady = mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                StartupGlassGate(
                    isDark = false,
                    baseBlurEnabled = true,
                    backgroundEffectReady = effectReady.value,
                    contentEffectReady = false,
                    timeoutMillis = 10_000L
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(STARTUP_GLASS_GATE_OVERLAY_TAG).assertIsDisplayed()

        composeRule.runOnIdle { effectReady.value = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(STARTUP_GLASS_GATE_OVERLAY_TAG).assertDoesNotExist()
    }

    @Test
    fun gateFailsOpenAfterItsDeadline() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                StartupGlassGate(
                    isDark = false,
                    baseBlurEnabled = true,
                    backgroundEffectReady = false,
                    contentEffectReady = false,
                    timeoutMillis = 1_000L
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(STARTUP_GLASS_GATE_OVERLAY_TAG).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_001L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(STARTUP_GLASS_GATE_OVERLAY_TAG).assertDoesNotExist()
    }
}
