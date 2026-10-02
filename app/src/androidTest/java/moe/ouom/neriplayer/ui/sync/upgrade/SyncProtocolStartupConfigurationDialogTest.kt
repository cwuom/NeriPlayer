package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsWebDavDialogs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncProtocolStartupConfigurationDialogTest {
    @get:Rule val composeRule = createComposeRule()
    private val models = mutableListOf<SyncProtocolUpgradeViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var showConfig by mutableStateOf(false)
    private var showClear by mutableStateOf(false)

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
    fun gitHubConfigurationWaitsForDurableStartupRegistration() {
        assertWaitingForm(github = true, clearing = false, CoreCommonR.string.settings_github_token_label)
    }

    @Test
    fun webDavConfigurationWaitsForDurableStartupRegistration() {
        assertWaitingForm(github = false, clearing = false, CoreCommonR.string.webdav_server_url_label)
    }

    @Test
    fun gitHubClearConfigurationWaitsForDurableStartupRegistration() {
        assertWaitingForm(github = true, clearing = true, CoreCommonR.string.sync_clear_config_desc)
    }

    @Test
    fun webDavClearConfigurationWaitsForDurableStartupRegistration() {
        assertWaitingForm(github = false, clearing = true, CoreCommonR.string.webdav_clear_config_desc)
    }

    @Test
    fun failedMainStageRegistrationRetriesTheOriginalWriteBeforeOpeningConfiguration() {
        val persisted = gate()
        var attempts = 0
        val model = model {
            if (++attempts == 1) throw IOException("registration write failed")
            persisted.await()
        }
        showDialogs(model, github = true, config = true)
        composeRule.onNodeWithText(text(CoreCommonR.string.settings_github_token_label)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_failed)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, attempts)
            assertFalse(model.uiState.value.startupRegistrationComplete)
        }

        composeRule.onNodeWithText(text(CoreCommonR.string.action_retry)).performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_status_loading)).assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.settings_github_token_label)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(2, attempts)
            persisted.complete(Unit)
        }

        waitForText(CoreCommonR.string.settings_github_token_label)
        composeRule.runOnIdle { assertTrue(model.uiState.value.startupRegistrationComplete) }
    }

    @Test
    fun hiddenConfigurationFlagsNeverAddARegistrationDialog() {
        val persisted = gate()
        val model = model { persisted.await() }
        showDialogs(model, github = true, config = false, clear = false, bothProviders = true)

        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_status_loading)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_failed)).assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(model.uiState.value.startupRegistrationComplete) }
    }

    @Test
    fun cancellingTheRegistrationDialogDoesNotOpenConfigurationAfterTheWriteCompletes() {
        val persisted = gate()
        val model = model { persisted.await() }
        showDialogs(model, github = false, config = true)
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_status_loading)).assertIsDisplayed()
        composeRule.onNodeWithText(text(CoreCommonR.string.action_cancel)).performClick()
        composeRule.runOnIdle {
            assertFalse(showConfig)
            assertFalse(showClear)
            assertFalse(model.uiState.value.startupRegistrationComplete)
            persisted.complete(Unit)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(CoreCommonR.string.webdav_server_url_label)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_status_loading)).assertDoesNotExist()
    }

    private fun assertWaitingForm(github: Boolean, clearing: Boolean, @StringRes formText: Int) {
        val persisted = gate()
        val model = model { persisted.await() }
        showDialogs(model, github = github, config = !clearing, clear = clearing)
        composeRule.onNodeWithText(text(formText)).assertDoesNotExist()
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_status_loading)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertFalse(model.uiState.value.startupRegistrationComplete)
            persisted.complete(Unit)
        }

        waitForText(formText)
        composeRule.onNodeWithText(text(CoreCommonR.string.sync_upgrade_status_loading)).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(model.uiState.value.startupRegistrationComplete) }
    }

    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(gates::add)

    private fun model(initialize: suspend () -> Unit): SyncProtocolUpgradeViewModel {
        lateinit var model: SyncProtocolUpgradeViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = SyncProtocolUpgradeViewModel(
                pendingFlow = flowOf(emptyList()),
                saveConfirmation = { _, _ -> error("configuration must not grant remote permission") },
                initializeStartupTargets = initialize
            ).also(models::add)
        }
        return model
    }

    private fun showDialogs(
        model: SyncProtocolUpgradeViewModel,
        github: Boolean,
        config: Boolean,
        clear: Boolean = false,
        bothProviders: Boolean = false
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            showConfig = config
            showClear = clear
        }
        composeRule.setContent {
            val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
            remember(owner, model) {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(model))
                }
                ViewModelProvider(owner, factory)[SyncProtocolUpgradeViewModel::class.java]
            }
            MaterialTheme {
                if (github || bothProviders) GitHubDialogs()
                if (!github || bothProviders) WebDavDialogs()
            }
        }
    }

    @Composable
    private fun GitHubDialogs() {
        SettingsGitHubDialogs(
            showConfig, { showConfig = it }, showClear, { showClear = it }
        )
    }

    @Composable
    private fun WebDavDialogs() {
        SettingsWebDavDialogs(
            showConfig, { showConfig = it }, showClear, { showClear = it }
        )
    }

    private fun waitForText(@StringRes resourceId: Int) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text(resourceId)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun text(@StringRes resourceId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resourceId)
}
