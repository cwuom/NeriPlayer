package moe.ouom.neriplayer.ui.sync.upgrade

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SyncProtocolUpgradeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))
    private val pending = SyncProtocolUpgradeUiState(
        approved = false,
        challenge = challenge,
        dialogRequested = true
    )
    private val events = mutableListOf<String>()

    @Test
    fun `dialog confirms only after all devices are updated and reports failures and progress`() {
        var state by mutableStateOf(pending)
        composeRule.setContent {
            MaterialTheme {
                SyncProtocolUpgradeDialog(
                    state = state,
                    onAllDevicesUpdatedChange = { events += "allDevices:$it" },
                    onConfirm = { events += "confirm" },
                    onDefer = { events += "defer" }
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_message)).assertExists()
        button(CoreCommonR.string.sync_upgrade_confirm).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_all_devices_updated)).performClick()
        button(CoreCommonR.string.sync_upgrade_defer).performClick()

        state = pending.copy(allDevicesUpdated = true)
        button(CoreCommonR.string.sync_upgrade_confirm).assertIsEnabled().performClick()

        state = pending.copy(
            hasError = true,
            errorDetail = SyncProtocolUpgradeError(CoreCommonR.string.sync_upgrade_failed, httpStatus = 409)
        )
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_failed)).assertExists()
        composeRule.onNodeWithText("HTTP 409").assertExists()

        state = pending.copy(hasError = true, errorDetail = SyncProtocolUpgradeError(CoreCommonR.string.sync_upgrade_required))
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_required)).assertExists()
        composeRule.onNodeWithText("HTTP 409").assertDoesNotExist()

        state = pending.copy(hasError = true)
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_attempt_failed)).assertExists()

        state = pending.copy(allDevicesUpdated = true, isSaving = true, isSyncing = true)
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_syncing)).assertExists()
        button(CoreCommonR.string.sync_upgrade_confirm).assertIsNotEnabled()
        button(CoreCommonR.string.sync_upgrade_defer).assertIsNotEnabled()

        state = state.copy(isSyncing = false)
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_saving)).assertExists()

        assertEquals(listOf("allDevices:true", "defer", "confirm"), events)
    }

    @Test
    fun `warning appears only for a declined challenge and opens the upgrade while idle`() {
        var state by mutableStateOf(SyncProtocolUpgradeUiState(challenge = challenge))
        var darkTheme by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                SyncProtocolUpgradeWarning(state = state, onConfirm = { events += "warning" })
            }
        }

        val title = string(CoreCommonR.string.sync_upgrade_warning_title)
        composeRule.onNodeWithText(title).assertDoesNotExist()
        state = SyncProtocolUpgradeUiState(approved = false)
        composeRule.onNodeWithText(title).assertDoesNotExist()

        state = SyncProtocolUpgradeUiState(approved = false, challenge = challenge)
        composeRule.onNodeWithText(string(CoreCommonR.string.sync_upgrade_warning_message)).assertExists()
        composeRule.onNodeWithText(title).performClick()

        darkTheme = true
        state = state.copy(isSaving = true)
        composeRule.onNode(hasClickAction() and hasText(title, substring = true), useUnmergedTree = false)
            .assertIsNotEnabled()

        assertEquals(listOf("warning"), events)
    }

    private fun button(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = context.getString(id)
}
