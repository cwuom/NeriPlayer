package moe.ouom.neriplayer.ui.screen.tab

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker

internal interface DownloadDirectoryPendingGateway {
    fun releaseTargetGrant(uri: String?)
    suspend fun enqueueMigration(change: PendingDownloadDirectoryChange): String
}

internal class AndroidDownloadDirectoryPendingGateway(private val context: Context) :
    DownloadDirectoryPendingGateway {
    override fun releaseTargetGrant(uri: String?) {
        ManagedDownloadStorage.releasePersistedDirectoryPermission(context, uri)
    }

    override suspend fun enqueueMigration(change: PendingDownloadDirectoryChange): String =
        ManagedDownloadMigrationWorker.enqueueOrGetActiveWorkId(
            context = context,
            fromDirectoryUri = change.previousUri,
            toDirectoryUri = change.targetUri,
            targetLabel = change.targetSummary,
            releasePreviousPermission = change.shouldReleasePreviousPermission
        )
}

internal interface DownloadDirectoryPendingActionPort {
    fun isBlocked(change: PendingDownloadDirectoryChange): Boolean
    suspend fun applyWithoutMigration(change: PendingDownloadDirectoryChange)
    fun beginMigration()
    fun recordActiveWorkId(workId: String)
    fun failMigration()
    fun showPreparationError(error: Exception)
}

internal fun shouldReleaseCancelledTargetGrant(change: PendingDownloadDirectoryChange): Boolean =
    change.releaseTargetPermissionOnCancel && !change.targetUri.isNullOrBlank()

internal class DownloadDirectoryPendingOwner(
    private val gateway: DownloadDirectoryPendingGateway,
    private val actions: DownloadDirectoryPendingActionPort,
    private val scope: CoroutineScope,
    private val resources: Resources,
    private val pendingChangeState: MutableState<PendingDownloadDirectoryChange?>,
    private val isPreparingState: MutableState<Boolean>,
    private val preparationJobState: MutableState<Job?>,
    private val onInlineMessageChange: (String?) -> Unit
) {
    fun cancel(change: PendingDownloadDirectoryChange) {
        pendingChangeState.value = null
        if (shouldReleaseCancelledTargetGrant(change)) gateway.releaseTargetGrant(change.targetUri)
    }

    fun skip(change: PendingDownloadDirectoryChange) {
        if (actions.isBlocked(change)) {
            pendingChangeState.value = null
            return
        }
        pendingChangeState.value = null
        isPreparingState.value = true
        preparationJobState.value = scope.launch { applyWithoutMigration(change) }
    }

    private suspend fun applyWithoutMigration(change: PendingDownloadDirectoryChange) {
        try {
            actions.applyWithoutMigration(change)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            actions.showPreparationError(error)
        } finally {
            isPreparingState.value = false
            preparationJobState.value = null
        }
    }

    fun confirm(change: PendingDownloadDirectoryChange) {
        if (actions.isBlocked(change)) {
            pendingChangeState.value = null
            return
        }
        pendingChangeState.value = null
        actions.beginMigration()
        scope.launch { enqueueMigration(change) }
    }

    private suspend fun enqueueMigration(change: PendingDownloadDirectoryChange) {
        runCatching { gateway.enqueueMigration(change) }
            .onSuccess(actions::recordActiveWorkId)
            .onFailure { error ->
                actions.failMigration()
                onInlineMessageChange(resources.getString(
                    R.string.settings_download_directory_pick_failed,
                    error.message ?: ""
                ))
            }
    }
}
