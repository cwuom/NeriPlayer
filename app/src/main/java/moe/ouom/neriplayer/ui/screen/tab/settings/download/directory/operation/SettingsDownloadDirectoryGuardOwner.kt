package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation

import android.content.Context
import android.content.res.Resources
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState

internal enum class DirectoryChangeBlockReason {
    NONE,
    PREPARATION_OR_MIGRATION,
    LIBRARY_PROCESSING,
    ACTIVE_DOWNLOADS
}

internal fun directoryChangeBlockReason(
    isPreparing: Boolean,
    allowWhilePreparing: Boolean,
    isMigrating: Boolean,
    libraryProcessing: ManagedLibraryProcessingState,
    hasActiveDownloads: () -> Boolean
): DirectoryChangeBlockReason = when {
    libraryProcessing != ManagedLibraryProcessingState.Idle ->
        DirectoryChangeBlockReason.LIBRARY_PROCESSING
    (isPreparing && !allowWhilePreparing) || isMigrating ->
        DirectoryChangeBlockReason.PREPARATION_OR_MIGRATION
    hasActiveDownloads() -> DirectoryChangeBlockReason.ACTIVE_DOWNLOADS
    else -> DirectoryChangeBlockReason.NONE
}

internal fun shouldReleaseBlockedDirectoryGrant(
    currentUri: String?,
    targetUri: String?,
    releaseRequested: Boolean
): Boolean = releaseRequested && !targetUri.isNullOrBlank() &&
    !ManagedDownloadStorage.areEquivalentDirectoryUris(currentUri, targetUri)

internal fun directoryChangeBlockMessageId(reason: DirectoryChangeBlockReason): Int? = when (reason) {
    DirectoryChangeBlockReason.LIBRARY_PROCESSING -> CoreCommonR.string.managed_library_processing_subtitle
    DirectoryChangeBlockReason.ACTIVE_DOWNLOADS ->
        CoreCommonR.string.settings_download_directory_change_blocked_active_download
    else -> null
}

internal interface DownloadDirectoryChangeGuardGateway {
    fun hasActiveDownloads(): Boolean
    fun releaseTargetGrant(uri: String?)
}

internal class AndroidDownloadDirectoryChangeGuardGateway(
    private val context: Context
) : DownloadDirectoryChangeGuardGateway {
    override fun hasActiveDownloads(): Boolean = GlobalDownloadManager.hasActiveDownloadOperations()

    override fun releaseTargetGrant(uri: String?) {
        ManagedDownloadStorage.releasePersistedDirectoryPermission(context, uri)
    }
}

internal class DownloadDirectoryChangeGuardOwner(
    private val gateway: DownloadDirectoryChangeGuardGateway,
    private val resources: Resources,
    private val currentUri: String?,
    private val isPreparing: () -> Boolean,
    private val isMigrating: () -> Boolean,
    private val libraryProcessing: () -> ManagedLibraryProcessingState,
    private val onInlineMessageChange: (String?) -> Unit,
    private val onShowMessage: (String) -> Unit
) {
    fun isBlocked(
        targetUri: String? = null,
        releaseTargetPermissionOnBlock: Boolean = false,
        allowWhilePreparing: Boolean = false
    ): Boolean {
        val reason = directoryChangeBlockReason(
            isPreparing = isPreparing(),
            allowWhilePreparing = allowWhilePreparing,
            isMigrating = isMigrating(),
            libraryProcessing = libraryProcessing(),
            hasActiveDownloads = gateway::hasActiveDownloads
        )
        if (reason == DirectoryChangeBlockReason.NONE) return false
        if (shouldReleaseBlockedDirectoryGrant(currentUri, targetUri, releaseTargetPermissionOnBlock)) {
            gateway.releaseTargetGrant(targetUri)
        }
        val messageId = directoryChangeBlockMessageId(reason)
        if (messageId != null) {
            val message = resources.getString(messageId)
            onInlineMessageChange(message)
            onShowMessage(message)
        }
        return true
    }
}

internal fun downloadDirectoryPreparationErrorMessage(resources: Resources, error: Exception): String {
    if (error is ManagedLibraryProcessingBusyException) {
        return resources.getString(CoreCommonR.string.managed_library_processing_subtitle)
    }
    val detail = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
    return resources.getString(CoreCommonR.string.settings_download_directory_pick_failed, detail)
}

internal class DownloadDirectoryPreparationErrorPresenter(
    private val resources: Resources,
    private val onInlineMessageChange: (String?) -> Unit,
    private val onShowMessage: (String) -> Unit
) {
    fun show(error: Exception) {
        val message = downloadDirectoryPreparationErrorMessage(resources, error)
        onInlineMessageChange(message)
        onShowMessage(message)
    }
}
