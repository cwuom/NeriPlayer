package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncProtocolUpgradeDialogTest {
    @get:Rule val composeRule = createComposeRule()
    private val models = mutableListOf<SyncProtocolUpgradeViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val target = "a".repeat(64)

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            models.forEach { it.viewModelScope.cancel() }
            gates.forEach { it.cancel() }
        }
    }

    @Test
    fun oldUserPromptPreservesLyricsByDefaultAndDeferringNeverWritesOrSyncs() {
        val calls = mutableListOf<String>()
        showStartupPrompt(
            sync = { _, _ -> calls += "sync"; Result.success(SyncResult(true, "")) }
        )

        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_lossless_hint))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_all_devices_updated)).assertIsOff()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_confirm)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_defer)).performClick()

        waitForDialogClosed()
        composeRule.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test
    fun deviceDeclarationStartsSyncAndDialogStaysBusyUntilCompletion() {
        val calls = mutableListOf<String>()
        val complete = CompletableDeferred<Unit>().also(gates::add)
        val startup = MutableStateFlow<Set<String>>(emptySet())
        val model = showStartupPrompt(
            startup = startup,
            sync = { id, mayApprove ->
                assertEquals(target, id)
                assertTrue(mayApprove)
                calls += "sync"
                startup.value = emptySet()
                complete.await()
                Result.success(SyncResult(true, ""))
            }
        )

        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_lossless_hint))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_all_devices_updated))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_confirm))
            .assertIsEnabled().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text(CoreCommonR.string.sync_upgrade_syncing))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_syncing))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_all_devices_updated)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_confirm)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_defer)).assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(listOf("sync"), calls)
            assertTrue(model.uiState.value.isSyncing)
            assertFalse(model.dismissConfirmation())
            complete.complete(Unit)
        }

        waitForDialogClosed()
        composeRule.runOnIdle {
            assertEquals(true, model.uiState.value.approved)
            assertFalse(model.uiState.value.isSyncing)
            assertEquals(listOf("sync"), calls)
        }
    }

    @Test
    fun confirmingDeviceDeclarationImmediatelySynchronizes() {
        val calls = mutableListOf<String>()
        val model = showStartupPrompt(
            sync = { id, mayApprove ->
                assertEquals(target, id)
                assertTrue(mayApprove)
                calls += "sync"
                Result.success(SyncResult(true, ""))
            }
        )
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_lossless_hint))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_all_devices_updated))
            .performScrollTo().performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_confirm))
            .assertIsEnabled().performClick()

        waitForDialogClosed()
        composeRule.runOnIdle {
            assertEquals(listOf("sync"), calls)
            assertEquals(true, model.uiState.value.approved)
        }
    }

    private fun showStartupPrompt(
        startup: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        sync: suspend (String, Boolean) -> Result<SyncResult>
    ): SyncProtocolUpgradeViewModel {
        lateinit var model: SyncProtocolUpgradeViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = SyncProtocolUpgradeViewModel(
                pendingFlow = flowOf(emptyList()),
                saveConfirmation = { _, _ -> error("Startup must not invent a remote challenge") },
                loadActiveTargets = { setOf(target) },
                startupTargetsFlow = startup,
                initializeStartupTargets = { startup.value = setOf(target) },
                performImmediateSync = sync
            ).also(models::add)
        }
        composeRule.setContent {
            MaterialTheme {
                StartupSyncUpgradePrompt(canShowDialog = true, viewModel = model, isResumed = true)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text(CoreCommonR.string.sync_upgrade_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        return model
    }

    private fun waitForDialogClosed() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text(CoreCommonR.string.sync_upgrade_title))
                .fetchSemanticsNodes().isEmpty()
        }
    }

    private fun text(@StringRes resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)
}
