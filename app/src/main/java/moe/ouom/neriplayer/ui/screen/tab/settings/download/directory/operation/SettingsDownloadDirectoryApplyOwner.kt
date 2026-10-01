package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryRefreshOutcome

internal interface DownloadDirectoryApplyGateway {
    suspend fun tryBeginExclusive(): String?
    fun currentBusyReason(): ManagedLibraryProcessingReason?
    fun configure(uri: String?, label: String?)
    suspend fun refresh(): ManagedLibraryRefreshOutcome
    suspend fun complete(operationId: String)
    suspend fun waitingForRetry(operationId: String)
    fun releasePreviousPermission(uri: String?)
}

internal class AndroidDownloadDirectoryApplyGateway(private val context: Context) : DownloadDirectoryApplyGateway {
    override suspend fun tryBeginExclusive(): String? =
        ManagedLibraryProcessingCoordinator.tryBeginExclusive(
            context = context,
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )

    override fun currentBusyReason(): ManagedLibraryProcessingReason? =
        ManagedLibraryProcessingCoordinator.state.value.reason

    override fun configure(uri: String?, label: String?) {
        ManagedDownloadStorage.updateConfiguredTreeUri(uri)
        ManagedDownloadStorage.updateCustomDirectoryLabel(label)
    }

    override suspend fun refresh(): ManagedLibraryRefreshOutcome =
        GlobalDownloadManager.scanLocalFilesAwait(context = context, forceRefresh = true)

    override suspend fun complete(operationId: String) {
        ManagedLibraryProcessingCoordinator.complete(context, operationId)
    }

    override suspend fun waitingForRetry(operationId: String) {
        ManagedLibraryProcessingCoordinator.waitingForRetry(context, operationId)
    }

    override fun releasePreviousPermission(uri: String?) {
        ManagedDownloadStorage.releasePersistedDirectoryPermission(context, uri)
    }
}

internal fun appliedDownloadDirectoryMessageId(targetUri: String?): Int =
    if (targetUri.isNullOrBlank()) CoreCommonR.string.settings_download_directory_reset_done
    else CoreCommonR.string.settings_download_directory_selected

internal class DownloadDirectoryApplyOwner(
    private val gateway: DownloadDirectoryApplyGateway,
    private val resources: Resources,
    private val onDirectoryUriChange: (String?, String?) -> Unit,
    private val onInlineMessageChange: (String?) -> Unit,
    private val isPreparingState: MutableState<Boolean>,
    private val permissionLostState: MutableState<Boolean>
) {
    suspend fun apply(
        targetUri: String?,
        targetSummary: String,
        previousUri: String?,
        shouldReleasePreviousPermission: Boolean
    ) {
        isPreparingState.value = false
        val operationId = gateway.tryBeginExclusive()
            ?: throw ManagedLibraryProcessingBusyException(gateway.currentBusyReason())
        try {
            val targetLabel = targetSummary.takeIf { !targetUri.isNullOrBlank() }
            gateway.configure(targetUri, targetLabel)
            onDirectoryUriChange(targetUri, targetLabel)
            permissionLostState.value = false
            finishRefresh(
                operationId = operationId,
                outcome = gateway.refresh(),
                targetUri = targetUri,
                previousUri = previousUri,
                shouldReleasePreviousPermission = shouldReleasePreviousPermission
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { runCatching { gateway.waitingForRetry(operationId) } }
            throw error
        } catch (error: Exception) {
            runCatching { gateway.waitingForRetry(operationId) }
            throw error
        }
    }

    private suspend fun finishRefresh(
        operationId: String,
        outcome: ManagedLibraryRefreshOutcome,
        targetUri: String?,
        previousUri: String?,
        shouldReleasePreviousPermission: Boolean
    ) {
        if (outcome is ManagedLibraryRefreshOutcome.Published) {
            gateway.complete(operationId)
            if (shouldReleasePreviousPermission) gateway.releasePreviousPermission(previousUri)
            onInlineMessageChange(resources.getString(appliedDownloadDirectoryMessageId(targetUri)))
        } else {
            gateway.waitingForRetry(operationId)
            onInlineMessageChange(resources.getString(CoreCommonR.string.managed_library_processing_retry))
        }
    }
}
