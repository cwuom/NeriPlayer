package moe.ouom.neriplayer.ui.screen.tab.settings.component.backup

import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import moe.ouom.neriplayer.ui.sync.upgrade.SyncProtocolUpgradeViewModel
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreUiState
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncUiState
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w720dp-h1600dp")
class SettingsBackupSyncPreferenceUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val configState = mutableStateOf(ConfigTransferUiState())
    private var readsUnavailable = false
    private var writesUnavailable = false
    private var storedDataSaver = false
    private var configurationOpens = 0

    @Test
    fun `unknown history offers retry without displaying the normal default or losing legacy migration`() = withStorage {
        readsUnavailable = true
        context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        render(cardIndex = 1)

        text(CoreCommonR.string.sync_secure_storage_unavailable).assertExists()
        text(CoreCommonR.string.settings_update_immediate).assertDoesNotExist()
        text(CoreCommonR.string.sync_history_frequency).assertIsNotEnabled()
        assertFalse(context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)
            .contains("play_history_update_mode"))

        composeRule.runOnIdle { readsUnavailable = false }
        text(CoreCommonR.string.action_retry).performClick()

        composeRule.onNodeWithText(context.resources.getQuantityString(AppR.plurals.settings_update_every_minutes, 30, 30)).assertExists()
        text(CoreCommonR.string.sync_secure_storage_unavailable).assertDoesNotExist()
    }

    @Test
    fun `unavailable preferences keep GitHub configuration recovery reachable`() = withStorage {
        readsUnavailable = true
        render(cardIndex = 2, configured = false)

        text(CoreCommonR.string.sync_secure_storage_unavailable).assertExists()
        text(CoreCommonR.string.action_retry).assertExists()
        text(CoreCommonR.string.sync_config).performScrollTo().performClick()

        assertEquals(1, configurationOpens)
    }

    @Test
    fun `failed reload and save retain the last value and the pending warning until a successful retry`() = withStorage {
        render(cardIndex = 2)
        dataSaverSwitch().assertIsOff().performScrollTo().performClick()
        text(CoreCommonR.string.sync_data_saver_warning_title).assertExists()

        composeRule.runOnIdle {
            readsUnavailable = true
            configState.value = ConfigTransferUiState(lastImportSuccess = true)
        }
        text(CoreCommonR.string.sync_data_saver_warning_title).assertExists()
        dataSaverSwitch().assertIsOff()

        composeRule.runOnIdle {
            readsUnavailable = false
            writesUnavailable = true
        }
        text(CoreCommonR.string.sync_data_saver_warning_confirm).performClick()

        assertFalse(storedDataSaver)
        text(CoreCommonR.string.sync_data_saver_warning_title).assertExists()
        assertTrue(composeRule.onAllNodesWithText(context.getString(CoreCommonR.string.sync_secure_storage_unavailable))
            .fetchSemanticsNodes().isNotEmpty())
        dataSaverSwitch().assertIsOff()

        composeRule.runOnIdle { writesUnavailable = false }
        text(CoreCommonR.string.sync_data_saver_warning_confirm).performClick()

        assertTrue(storedDataSaver)
        text(CoreCommonR.string.sync_data_saver_warning_title).assertDoesNotExist()
        dataSaverSwitch().assertIsOn()
        text(CoreCommonR.string.sync_secure_storage_unavailable).assertDoesNotExist()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `import refresh preserves a new pending warning created while its preference read is suspended`() {
        val io = StandardTestDispatcher()
        withStorage(io) {
            render(cardIndex = 2)
            composeRule.runOnIdle { io.scheduler.runCurrent() }
            dataSaverSwitch().assertIsOff()

            composeRule.runOnIdle { configState.value = ConfigTransferUiState(lastImportSuccess = true) }
            composeRule.waitForIdle()
            dataSaverSwitch().performScrollTo().performClick()
            text(CoreCommonR.string.sync_data_saver_warning_title).assertExists()

            composeRule.runOnIdle { io.scheduler.runCurrent() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { io.scheduler.runCurrent() }

            text(CoreCommonR.string.sync_data_saver_warning_title).assertExists()
            dataSaverSwitch().assertIsOff()
        }
    }

    private fun withStorage(io: CoroutineDispatcher = Dispatchers.Main.immediate, test: () -> Unit) {
        mockStatic(Dispatchers::class.java, CALLS_REAL_METHODS).use { dispatchers ->
            dispatchers.`when`<CoroutineDispatcher> { Dispatchers.IO }.thenReturn(io)
            mockConstruction(SecureTokenStorage::class.java) { store, _ ->
                doAnswer {
                    if (readsUnavailable) throw SyncStorageUnavailableException()
                    "BATCHED_30"
                }.`when`(store).getLegacyPlayHistoryUpdateModeName()
                doAnswer {
                    if (readsUnavailable) throw SyncStorageUnavailableException()
                    storedDataSaver
                }.`when`(store).isDataSaverMode()
                doAnswer {
                    if (writesUnavailable) throw SyncStorageUnavailableException()
                    storedDataSaver = it.getArgument(0)
                    null
                }.`when`(store).setDataSaverMode(org.mockito.ArgumentMatchers.anyBoolean())
            }.use { test() }
        }
    }

    private fun render(cardIndex: Int, configured: Boolean = true) {
        val github = mock(GitHubSyncViewModel::class.java)
        `when`(github.uiState).thenReturn(MutableStateFlow(GitHubSyncUiState(
            isConfigured = configured, repoOwner = "fixture", repoName = "backup"
        )))
        val upgrades = SyncProtocolUpgradeViewModel(flowOf(emptyList()), { _, _ -> })
        val repository = AutoSettingsRepository(context)
        composeRule.setContent {
            val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner)
            remember(owner) {
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(
                        if (modelClass == GitHubSyncViewModel::class.java) github else upgrades
                    ))
                }
                ViewModelProvider(owner, factory)[GitHubSyncViewModel::class.java]
                ViewModelProvider(owner, factory)[SyncProtocolUpgradeViewModel::class.java]
            }
            MaterialTheme {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsBackupRestoreSection(
                        expanded = true, arrowRotation = 0f, onExpandedChange = {}, showHeader = false,
                        currentPlaylistCount = 0, backupRestoreUiState = BackupRestoreUiState(), configTransferUiState = configState.value,
                        onExportClick = {}, onImportClick = {}, onExportConfigClick = {}, onImportConfigClick = {},
                        onClearExportStatus = {}, onClearImportStatus = {}, onClearConfigExportStatus = {}, onClearConfigImportStatus = {},
                        autoSettingsRepository = repository, scope = rememberCoroutineScope(),
                        showGitHubConfigDialog = false, showWebDavConfigDialog = false,
                        onOpenGitHubConfig = { configurationOpens++ }, onOpenClearGitHubConfig = {},
                        onOpenWebDavConfig = {}, onOpenClearWebDavConfig = {}, cardIndex = cardIndex
                    )
                }
            }
        }
    }

    private fun text(id: Int) = composeRule.onNodeWithText(context.getString(id))
    private fun dataSaverSwitch() = composeRule.onNode(isToggleable() and
        hasAnyAncestor(hasText(context.getString(CoreCommonR.string.sync_data_saver))))
}
