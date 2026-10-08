package moe.ouom.neriplayer.ui.dialog

import android.app.Application
import android.content.Context
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class StartupDebugBuildWarningDefaultsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `default prompt shows the debug dialog in a resumed debug build and confirms once`() {
        val pendingStates = mutableListOf<Boolean>()
        composeRule.setContent {
            val pending = startupDebugBuildWarningPrompt(canShowDialog = true)
            SideEffect { pendingStates += pending }
        }

        composeRule.onNodeWithText(context.getString(R.string.debug_build_warning_title))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.debug_build_warning_message))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.debug_build_warning_save_failed))
            .assertDoesNotExist()
        assertTrue(pendingStates.last())

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_confirm))
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { pendingStates.last() == false }

        composeRule.onNodeWithText(context.getString(R.string.debug_build_warning_title))
            .assertDoesNotExist()
        assertTrue(runBlocking { DebugBuildWarningRepository(context).isAcknowledged() })
    }

    @Test
    fun `warning is shown only after an unacknowledged read while startup dialogs are allowed`() {
        assertTrue(shouldShowDebugBuildWarning(acknowledged = false, canShowDialog = true, isResumed = true))
        assertEquals(
            listOf(false, false, false, false),
            listOf(
                shouldShowDebugBuildWarning(acknowledged = null, canShowDialog = true, isResumed = true),
                shouldShowDebugBuildWarning(acknowledged = true, canShowDialog = true, isResumed = true),
                shouldShowDebugBuildWarning(acknowledged = false, canShowDialog = false, isResumed = true),
                shouldShowDebugBuildWarning(acknowledged = false, canShowDialog = true, isResumed = false)
            )
        )
        assertFalse(shouldShowDebugBuildWarning(acknowledged = null, canShowDialog = false, isResumed = false))
    }
}
