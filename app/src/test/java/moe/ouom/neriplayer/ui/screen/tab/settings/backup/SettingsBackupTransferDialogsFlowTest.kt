package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.config.AppConfigBackupCodec
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.data.model.config.AppConfigBackup
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsBackupTransferDialogsFlowTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ViewModelStore()
    private val config = ConfigTransferViewModel()
    private lateinit var controller: SettingsBackupTransferController
    private val documentNames = mutableListOf<String>()
    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore = store
    }
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            check(contract is ActivityResultContracts.CreateDocument)
            documentNames += input as String
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @After
    fun clearViewModels() {
        composeRule.runOnIdle { store.clear() }
    }

    @Test
    fun `clean settings opens the export picker only after confirming the current options`() =
        verifyExportSelection(UiStyle.Clean)

    @Test
    fun `material settings opens the export picker only after confirming the current options`() =
        verifyExportSelection(UiStyle.Material)

    @Test
    fun `encrypted import presents a cancellable password request through the settings host`() {
        val encrypted = AppConfigBackupCodec.encodeForExport(
            AppConfigBackup(),
            password = "Synthetic-config-password".toCharArray()
        )
        val uri = prepareImport(encrypted)
        render(UiStyle.Clean)

        composeRule.runOnIdle { config.importConfig(uri) }

        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_import_password_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        composeRule.runOnIdle {
            assertFalse(config.uiState.value.isImporting)
            assertFalse(config.uiState.value.requiresConfigPassword)
            assertNull(config.uiState.value.lastImportSuccess)
            assertEquals(emptyList<String>(), documentNames)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_import_password_title)).assertDoesNotExist()
    }

    @Test
    fun `compatibility import presents consent and cancellation leaves the account untouched`() {
        val youtube = mock(YouTubeAuthRepository::class.java)
        doReturn(true).`when`(youtube).isAuthStoragePersistent
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning
        val uri = prepareImport(
            """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"youTubeAuth":{"cookieHeader":"SID=synthetic-session"}}""",
            youtube
        )
        render(UiStyle.Material)

        composeRule.runOnIdle { config.importConfig(uri) }

        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        composeRule.runOnIdle {
            assertFalse(config.uiState.value.isImporting)
            assertFalse(config.uiState.value.requiresAuthStorageConfirmation)
            assertNull(config.uiState.value.lastImportSuccess)
        }
        verify(youtube, never()).saveAuth(any(YouTubeAuthBundle::class.java) ?: YouTubeAuthBundle(), anyBoolean())
        verify(youtube, never()).clear()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_title)).assertDoesNotExist()
    }

    private fun verifyExportSelection(style: UiStyle) {
        installManager(ConfigFileManager(context, ioDispatcher = Dispatchers.Main.immediate))
        render(style)
        composeRule.runOnIdle { controller.exportConfig() }
        composeRule.onNodeWithText(string(CoreCommonR.string.action_cancel)).performClick()
        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), documentNames)
            assertFalse(config.uiState.value.showExportOptions)
            controller.exportConfig()
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.settings_backup_action_export)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, documentNames.size)
            assertTrue(documentNames.single().matches(Regex("neriplayer_config_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.json")))
            assertFalse(config.uiState.value.showExportOptions)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_export_options_title)).assertDoesNotExist()
    }

    private fun render(style: UiStyle) {
        store.put(defaultKey(ConfigTransferViewModel::class.java), config)
        store.put(defaultKey(BackupRestoreViewModel::class.java), BackupRestoreViewModel())
        composeRule.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides storeOwner,
                LocalActivityResultRegistryOwner provides registryOwner
            ) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = style,
                    animateColors = false
                ) {
                    val current = rememberSettingsBackupTransferController(onBeforeLanguageRestart = {})
                    SideEffect { controller = current }
                }
            }
        }
    }

    private fun prepareImport(raw: String, youtube: YouTubeAuthRepository? = null): Uri {
        val source = folder.newFile("config.json").apply { writeText(raw) }
        val manager = ConfigFileManager(context, youTubeAuthRepo = youtube, ioDispatcher = Dispatchers.Main.immediate)
        installManager(manager)
        return Uri.fromFile(source)
    }

    private fun installManager(manager: ConfigFileManager) {
        for ((name, value) in listOf(
            "configFileManager" to manager,
            "strings" to ConfigTransferViewModel.ConfigTransferStrings.from(context)
        )) {
            ConfigTransferViewModel::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(config, value)
            }
        }
    }

    private fun defaultKey(type: Class<*>) = "androidx.lifecycle.ViewModelProvider.DefaultKey:${type.canonicalName}"

    private fun string(id: Int): String = context.getString(id)
}
