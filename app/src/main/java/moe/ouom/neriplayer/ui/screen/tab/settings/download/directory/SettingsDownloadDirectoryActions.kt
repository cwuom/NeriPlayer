package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.State
import kotlinx.coroutines.CoroutineScope
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.AndroidDownloadDirectoryPreparationGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.migration.DownloadDirectoryMigrationRecoveryController
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationActionPort
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.AndroidDownloadDirectoryApplyGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.AndroidDownloadDirectoryChangeGuardGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.AndroidDownloadDirectoryPendingGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.AndroidDownloadDirectoryResetGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.AndroidDownloadDirectorySelectionGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryApplyOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryChangeGuardOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPendingActionPort
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPendingOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationErrorPresenter
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryResetActionPort
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryResetOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectorySelectionOwner

internal class DownloadDirectoryActions(
    context: Context,
    resources: Resources,
    scope: CoroutineScope,
    private val directoryUri: String?,
    defaultSummary: String,
    private val localState: DownloadDirectoryLocalState,
    private val migrationRecovery: DownloadDirectoryMigrationRecoveryController,
    private val libraryProcessingState: State<ManagedLibraryProcessingState>,
    onDirectoryUriChange: (String?, String?) -> Unit,
    onInlineMessageChange: (String?) -> Unit,
    onShowMessage: (String) -> Unit
) {
    private val errorPresenter = DownloadDirectoryPreparationErrorPresenter(
        resources, onInlineMessageChange, onShowMessage
    )
    private val guardOwner = DownloadDirectoryChangeGuardOwner(
        gateway = AndroidDownloadDirectoryChangeGuardGateway(context),
        resources = resources,
        currentUri = directoryUri,
        isPreparing = { localState.isPreparing.value },
        isMigrating = { migrationRecovery.isMigrating },
        libraryProcessing = { libraryProcessingState.value },
        onInlineMessageChange = onInlineMessageChange,
        onShowMessage = onShowMessage
    )
    private val applyOwner = DownloadDirectoryApplyOwner(
        gateway = AndroidDownloadDirectoryApplyGateway(context),
        resources = resources,
        onDirectoryUriChange = onDirectoryUriChange,
        onInlineMessageChange = onInlineMessageChange,
        isPreparingState = localState.isPreparing,
        permissionLostState = localState.permissionLost
    )
    private val preparationOwner = DownloadDirectoryPreparationOwner(
        gateway = AndroidDownloadDirectoryPreparationGateway(context),
        actions = object : DownloadDirectoryPreparationActionPort {
            override fun isBlocked(): Boolean = guardOwner.isBlocked(allowWhilePreparing = true)

            override suspend fun apply(
                targetUri: String?,
                targetSummary: String,
                previousUri: String?,
                shouldReleasePreviousPermission: Boolean
            ) {
                applyOwner.apply(
                    targetUri,
                    targetSummary,
                    previousUri,
                    shouldReleasePreviousPermission
                )
            }

            override fun showPending(change: PendingDownloadDirectoryChange) {
                localState.pendingChange.value = change
            }
        },
        resources = resources,
        onInlineMessageChange = onInlineMessageChange,
        onShowMessage = onShowMessage
    )
    private val selectionOwner = DownloadDirectorySelectionOwner(
        gateway = AndroidDownloadDirectorySelectionGateway(context),
        scope = scope,
        isPreparingState = localState.isPreparing,
        preparationJobState = localState.preparationJob,
        permissionLostState = localState.permissionLost,
        isBlocked = { guardOwner.isBlocked() },
        prepare = { targetUri, targetSummary ->
            preparationOwner.prepare(directoryUri, targetUri, targetSummary, true)
        },
        onError = errorPresenter::show
    )
    private val resetOwner = DownloadDirectoryResetOwner(
        gateway = AndroidDownloadDirectoryResetGateway(context),
        actions = object : DownloadDirectoryResetActionPort {
            override fun isBlocked(): Boolean = guardOwner.isBlocked()

            override suspend fun prepareDefault(targetSummary: String) {
                preparationOwner.prepare(directoryUri, null, targetSummary, false)
            }

            override suspend fun applyDefault(targetSummary: String, previousUri: String?) {
                applyOwner.apply(null, targetSummary, previousUri, true)
            }
        },
        scope = scope,
        resources = resources,
        currentUri = directoryUri,
        defaultSummary = defaultSummary,
        isPreparingState = localState.isPreparing,
        preparationJobState = localState.preparationJob,
        onInlineMessageChange = onInlineMessageChange,
        onShowMessage = onShowMessage,
        onError = errorPresenter::show
    )
    private val pendingOwner = DownloadDirectoryPendingOwner(
        gateway = AndroidDownloadDirectoryPendingGateway(context),
        actions = object : DownloadDirectoryPendingActionPort {
            override fun isBlocked(change: PendingDownloadDirectoryChange): Boolean =
                guardOwner.isBlocked(change.targetUri, change.releaseTargetPermissionOnCancel)

            override suspend fun applyWithoutMigration(change: PendingDownloadDirectoryChange) {
                applyOwner.apply(
                    change.targetUri, change.targetSummary, change.previousUri,
                    change.shouldReleasePreviousPermission
                )
            }

            override fun beginMigration() = migrationRecovery.beginMigration()
            override fun recordActiveWorkId(workId: String) =
                migrationRecovery.recordActiveWorkId(workId)

            override fun failMigration() = migrationRecovery.failMigration()
            override fun showPreparationError(error: Exception) = errorPresenter.show(error)
        },
        scope = scope,
        resources = resources,
        pendingChangeState = localState.pendingChange,
        isPreparingState = localState.isPreparing,
        preparationJobState = localState.preparationJob,
        onInlineMessageChange = onInlineMessageChange
    )

    fun onPicked(uri: String?) = selectionOwner.onPicked(uri)

    fun controller(
        currentSummaryState: State<String>,
        hasActiveDownloadOperationsState: State<Boolean>,
        launchPicker: () -> Unit
    ): DownloadDirectorySettingsController {
        val switchOwner = DownloadDirectorySwitchOwner(
            localState.showSwitchWarning, { guardOwner.isBlocked() }, launchPicker
        )
        return DownloadDirectorySettingsController(
            currentSummaryState = currentSummaryState,
            permissionLostState = localState.permissionLost,
            hasActiveDownloadOperationsState = hasActiveDownloadOperationsState,
            showSwitchWarningState = localState.showSwitchWarning,
            pendingChangeState = localState.pendingChange,
            isPreparingState = localState.isPreparing,
            isMigratingState = migrationRecovery.isMigratingState,
            migrationProgressState = migrationRecovery.liveProgressState,
            persistedMigrationProgressState = migrationRecovery.persistedProgressState,
            libraryProcessingState = libraryProcessingState,
            onPickRequested = switchOwner::requestPick,
            onResetRequested = resetOwner::onResetRequested,
            onCancelPreparation = { localState.preparationJob.value?.cancel() },
            onDismissSwitchWarning = switchOwner::dismissWarning,
            onConfirmSwitchWarning = switchOwner::confirmWarning,
            onCancelPendingChange = pendingOwner::cancel,
            onSkipPendingChange = pendingOwner::skip,
            onConfirmPendingChange = pendingOwner::confirm
        )
    }
}
