package moe.ouom.neriplayer.ui.screen.tab

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState

internal data class PendingDownloadDirectoryChange(
    val previousUri: String?,
    val targetUri: String?,
    val targetSummary: String,
    val releaseTargetPermissionOnCancel: Boolean,
    val targetNonEmpty: Boolean = false
) {
    val shouldReleasePreviousPermission: Boolean
        get() = !previousUri.isNullOrBlank() &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(previousUri, targetUri)

    val shouldShowTargetConflictWarning: Boolean
        get() = targetNonEmpty && !targetUri.isNullOrBlank()
}

/**
 * 共享处理横幅已经接管目录操作时，设置页不再弹出第二个模态窗口
 */
internal data class DownloadDirectoryProcessingPresentation(
    val showPreparation: Boolean,
    val showMigration: Boolean,
    val usesSharedProcessing: Boolean
)

internal fun resolveDownloadDirectoryProcessingPresentation(
    isPreparing: Boolean,
    isMigrating: Boolean,
    processingState: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): DownloadDirectoryProcessingPresentation {
    val usesSharedProcessing = processingState != ManagedLibraryProcessingState.Idle
    val hasMigrationWork = isMigrating || migrationProgress != null
    return DownloadDirectoryProcessingPresentation(
        showPreparation = isPreparing && !usesSharedProcessing && !hasMigrationWork,
        showMigration = hasMigrationWork && !usesSharedProcessing,
        usesSharedProcessing = usesSharedProcessing
    )
}

internal fun isDownloadDirectoryChangeEnabled(
    hasActiveDownloads: Boolean,
    isPreparing: Boolean,
    isMigrating: Boolean,
    processingState: ManagedLibraryProcessingState
): Boolean = !hasActiveDownloads && !isPreparing && !isMigrating &&
    processingState == ManagedLibraryProcessingState.Idle

internal class DownloadDirectorySettingsController(
    private val currentSummaryState: State<String>,
    private val permissionLostState: State<Boolean>,
    private val hasActiveDownloadOperationsState: State<Boolean>,
    private val showSwitchWarningState: State<Boolean>,
    private val pendingChangeState: State<PendingDownloadDirectoryChange?>,
    private val isPreparingState: State<Boolean>,
    private val isMigratingState: State<Boolean>,
    private val migrationProgressState: State<ManagedDownloadStorage.MigrationProgress?>,
    private val persistedMigrationProgressState: State<ManagedDownloadStorage.MigrationProgress?>,
    private val libraryProcessingState: State<ManagedLibraryProcessingState>,
    override val onPickRequested: () -> Unit,
    override val onResetRequested: () -> Unit,
    val onCancelPreparation: () -> Unit,
    val onDismissSwitchWarning: () -> Unit,
    val onConfirmSwitchWarning: () -> Unit,
    val onCancelPendingChange: (PendingDownloadDirectoryChange) -> Unit,
    val onSkipPendingChange: (PendingDownloadDirectoryChange) -> Unit,
    val onConfirmPendingChange: (PendingDownloadDirectoryChange) -> Unit
) : DownloadDirectoryStoragePort {
    val onDismissPendingChange: (PendingDownloadDirectoryChange) -> Unit
        get() = onCancelPendingChange

    override val currentSummary: String
        get() = currentSummaryState.value

    override val permissionLost: Boolean
        get() = permissionLostState.value

    override val changeEnabled: Boolean
        get() = isDownloadDirectoryChangeEnabled(
            hasActiveDownloadOperationsState.value,
            isPreparingState.value,
            isMigratingState.value,
            libraryProcessingState.value
        )

    override val hasActiveDownloadOperations: Boolean
        get() = hasActiveDownloadOperationsState.value

    val showSwitchWarning: Boolean
        get() = showSwitchWarningState.value

    val pendingChange: PendingDownloadDirectoryChange?
        get() = pendingChangeState.value

    val isPreparing: Boolean
        get() = isPreparingState.value

    val isMigrating: Boolean
        get() = isMigratingState.value

    val migrationProgress: ManagedDownloadStorage.MigrationProgress?
        get() = migrationProgressState.value ?: persistedMigrationProgressState.value

    val libraryProcessing: ManagedLibraryProcessingState
        get() = libraryProcessingState.value

    val processingPresentation: DownloadDirectoryProcessingPresentation
        get() = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = isPreparing,
            isMigrating = isMigrating,
            processingState = libraryProcessing,
            migrationProgress = migrationProgress
        )
}

private data class DownloadDirectorySummaryKey(val directoryUri: String?, val defaultSummary: String)

@Composable
private fun rememberDownloadDirectorySummaryState(
    directoryUri: String?,
    defaultSummary: String
): MutableState<String> = remember(DownloadDirectorySummaryKey(directoryUri, defaultSummary)) {
    mutableStateOf(defaultSummary)
}

@Composable
private fun rememberDownloadDirectoryPickerContract(): ActivityResultContracts.OpenDocumentTree =
    remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent {
                return super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
    }


@Composable
internal fun rememberDownloadDirectorySettingsController(
    downloadDirectoryUri: String?,
    onDownloadDirectoryUriChange: (String?, String?) -> Unit,
    onInlineMessageChange: (String?) -> Unit,
    onShowMessage: (String) -> Unit
): DownloadDirectorySettingsController {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val defaultDirectorySummary = resources.getString(
        R.string.settings_download_directory_default_label
    )
    val localState = rememberDownloadDirectoryLocalState()
    val libraryProcessingState = ManagedLibraryProcessingCoordinator.state.collectAsState()
    val hasActiveDownloadOperationsState =
        GlobalDownloadManager.activeDownloadOperationsFlow.collectAsState()
    val currentSummaryState = rememberDownloadDirectorySummaryState(
        downloadDirectoryUri, defaultDirectorySummary
    )

    val migrationRecovery = rememberDownloadDirectoryMigrationRecoveryController(
        context = context,
        resources = resources,
        libraryProcessingState = libraryProcessingState,
        onInlineMessageChange = onInlineMessageChange
    )
    val actions = DownloadDirectoryActions(
        context = context,
        resources = resources,
        scope = scope,
        directoryUri = downloadDirectoryUri,
        defaultSummary = defaultDirectorySummary,
        localState = localState,
        migrationRecovery = migrationRecovery,
        libraryProcessingState = libraryProcessingState,
        onDirectoryUriChange = onDownloadDirectoryUriChange,
        onInlineMessageChange = onInlineMessageChange,
        onShowMessage = onShowMessage
    )
    SyncDownloadDirectoryPermission(context, downloadDirectoryUri, localState.permissionLost)

    val directoryContract = rememberDownloadDirectoryPickerContract()
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = directoryContract
    ) { uri ->
        actions.onPicked(uri?.toString())
    }

    SyncDownloadDirectorySummary(context, downloadDirectoryUri, defaultDirectorySummary, currentSummaryState)
    return actions.controller(
        currentSummaryState = currentSummaryState,
        hasActiveDownloadOperationsState = hasActiveDownloadOperationsState,
        launchPicker = { directoryLauncher.launch(null) }
    )
}
