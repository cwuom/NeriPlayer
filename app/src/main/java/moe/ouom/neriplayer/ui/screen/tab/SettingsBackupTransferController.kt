package moe.ouom.neriplayer.ui.screen.tab

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsBackupRestoreSection
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreUiState
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferUiState
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferViewModel

private class BackupSafResultHandler(
    private val context: Context,
    private val playlists: BackupRestoreViewModel,
    private val config: ConfigTransferViewModel
) {
    fun onExportPlaylist(uri: Uri?) {
        if (uri == null) return
        playlists.initialize(context)
        playlists.exportPlaylists(uri)
    }

    fun onImportPlaylist(uri: Uri?) {
        if (uri == null) return
        playlists.initialize(context)
        playlists.importPlaylists(uri)
    }

    fun onExportConfig(uri: Uri?) {
        if (uri == null) return
        config.initialize(context)
        config.exportConfig(uri)
    }

    fun onImportConfig(uri: Uri?) {
        if (uri == null) return
        config.initialize(context)
        config.importConfig(uri)
    }
}

internal fun recreateSettingsActivity(context: Context) {
    (context as? Activity)?.recreate()
}

internal class BackupImportRecreateAction(
    private val required: Boolean,
    private val context: Context,
    private val onBeforeLanguageRestart: () -> Unit,
    private val onConsumeRequest: () -> Unit,
    private val onRecreateActivity: (Context) -> Unit
) {
    val effect: suspend CoroutineScope.() -> Unit = { applyIfRequired() }

    fun applyIfRequired() {
        if (!required) return
        onBeforeLanguageRestart()
        onConsumeRequest()
        onRecreateActivity(context)
    }
}

internal class SettingsBackupTransferController(
    val playlistState: BackupRestoreUiState,
    val configState: ConfigTransferUiState,
    private val context: Context,
    private val playlists: BackupRestoreViewModel,
    private val config: ConfigTransferViewModel,
    private val launchPlaylistExport: (String) -> Unit,
    private val launchPlaylistImport: () -> Unit,
    private val launchConfigExport: (String) -> Unit,
    private val launchConfigImport: () -> Unit
) {
    fun exportPlaylists() {
        if (playlistState.isExporting) return
        playlists.initialize(context)
        launchPlaylistExport(playlists.generateBackupFileName())
    }

    fun importPlaylists() {
        if (!playlistState.isImporting) launchPlaylistImport()
    }

    fun exportConfig() {
        if (configState.isExporting) return
        config.initialize(context)
        launchConfigExport(config.generateConfigFileName())
    }

    fun importConfig() {
        if (!configState.isImporting) launchConfigImport()
    }

    fun clearPlaylistExportStatus() = playlists.clearExportStatus()
    fun clearPlaylistImportStatus() = playlists.clearImportStatus()
    fun clearConfigExportStatus() = config.clearExportStatus()
    fun clearConfigImportStatus() = config.clearImportStatus()

    fun observePlaylistCount(context: Context) = playlists.observePlaylistCount(context)
    fun consumeImportRecreateRequest() = config.consumeImportRecreateRequest()
}

@Composable
internal fun rememberSettingsBackupTransferController(
    onBeforeLanguageRestart: () -> Unit
): SettingsBackupTransferController {
    val context = LocalContext.current
    val playlists: BackupRestoreViewModel = viewModel()
    val config: ConfigTransferViewModel = viewModel()
    val playlistState by playlists.uiState.collectAsStateWithLifecycleCompat()
    val configState by config.uiState.collectAsStateWithLifecycleCompat()
    val handler = BackupSafResultHandler(context, playlists, config)
    val exportPlaylist = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json"),
        onResult = handler::onExportPlaylist
    )
    val importPlaylist = rememberLauncherForActivityResult(
        contract = OpenDocument(),
        onResult = handler::onImportPlaylist
    )
    val exportConfig = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json"),
        onResult = handler::onExportConfig
    )
    val importConfig = rememberLauncherForActivityResult(
        contract = OpenDocument(),
        onResult = handler::onImportConfig
    )
    val controller = SettingsBackupTransferController(
        playlistState = playlistState,
        configState = configState,
        context = context,
        playlists = playlists,
        config = config,
        launchPlaylistExport = { exportPlaylist.launch(it) },
        launchPlaylistImport = { importPlaylist.launch(arrayOf("*/*")) },
        launchConfigExport = { exportConfig.launch(it) },
        launchConfigImport = { importConfig.launch(arrayOf("*/*")) }
    )
    val recreateAction = BackupImportRecreateAction(
        required = configState.importRequiresActivityRecreate,
        context = context,
        onBeforeLanguageRestart = onBeforeLanguageRestart,
        onConsumeRequest = controller::consumeImportRecreateRequest,
        onRecreateActivity = ::recreateSettingsActivity
    )
    LaunchedEffect(configState.importRequiresActivityRecreate, block = recreateAction.effect)
    return controller
}

@Composable
internal fun ObserveSettingsBackupPlaylistCount(
    page: SettingsPage?,
    context: Context,
    controller: SettingsBackupTransferController
) {
    LaunchedEffect(page, context) {
        if (page == SettingsPage.Backup) controller.observePlaylistCount(context)
    }
}

internal class SettingsBackupRemoteDialogPort(
    val showGitHubConfigDialog: Boolean,
    val showWebDavConfigDialog: Boolean,
    val onOpenGitHubConfig: () -> Unit,
    val onOpenClearGitHubConfig: () -> Unit,
    val onOpenWebDavConfig: () -> Unit,
    val onOpenClearWebDavConfig: () -> Unit
)

internal fun LazyListScope.settingsBackupPageItems(
    controller: SettingsBackupTransferController,
    repository: AutoSettingsRepository,
    scope: CoroutineScope,
    remoteDialogs: SettingsBackupRemoteDialogPort,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?
) {
    for (cardIndex in 0..4) {
        item(key = "${SettingsPage.Backup.name}:card:$cardIndex") {
            SettingsBackupRestoreSection(
                expanded = true,
                arrowRotation = 0f,
                onExpandedChange = {},
                showHeader = false,
                currentPlaylistCount = controller.playlistState.currentPlaylistCount,
                backupRestoreUiState = controller.playlistState,
                configTransferUiState = controller.configState,
                onExportClick = controller::exportPlaylists,
                onImportClick = controller::importPlaylists,
                onExportConfigClick = controller::exportConfig,
                onImportConfigClick = controller::importConfig,
                onClearExportStatus = controller::clearPlaylistExportStatus,
                onClearImportStatus = controller::clearPlaylistImportStatus,
                onClearConfigExportStatus = controller::clearConfigExportStatus,
                onClearConfigImportStatus = controller::clearConfigImportStatus,
                autoSettingsRepository = repository,
                scope = scope,
                showGitHubConfigDialog = remoteDialogs.showGitHubConfigDialog,
                showWebDavConfigDialog = remoteDialogs.showWebDavConfigDialog,
                onOpenGitHubConfig = remoteDialogs.onOpenGitHubConfig,
                onOpenClearGitHubConfig = remoteDialogs.onOpenClearGitHubConfig,
                onOpenWebDavConfig = remoteDialogs.onOpenWebDavConfig,
                onOpenClearWebDavConfig = remoteDialogs.onOpenClearWebDavConfig,
                cardIndex = cardIndex,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
        }
    }
}
