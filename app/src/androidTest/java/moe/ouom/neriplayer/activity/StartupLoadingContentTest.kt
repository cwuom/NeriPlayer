package moe.ouom.neriplayer.activity

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupLoadingContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStageShowsLightweightStartupProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                StartupLoadingContent(showProgress = true)
            }
        }

        composeRule.onNodeWithTag("startup_loading_surface").assertIsDisplayed()
        composeRule.onNodeWithTag("startup_loading_progress").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
    }

    @Test
    fun loadingStageDoesNotShowIndicatorImmediately() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                StartupLoadingContent(indicatorDelayMillis = 1_000L)
            }
        }

        composeRule.onNodeWithTag("startup_loading_surface").assertIsDisplayed()
        composeRule.onAllNodesWithTag("startup_loading_progress").assertCountEquals(0)
        // 留出一帧调度余量，避免测试时钟的下一帧越过 1000ms 边界
        composeRule.mainClock.advanceTimeBy(900L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodesWithTag("startup_loading_progress").assertCountEquals(0)
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("startup_loading_progress").assertIsDisplayed()
    }
}
