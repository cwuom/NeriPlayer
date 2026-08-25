package moe.ouom.neriplayer.core.download.policy

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem

internal data class PendingDownloadRecoveryCandidate(
    val song: SongItem,
    val workingFile: File?,
    val order: Int,
    val cancelled: Boolean,
    val operationId: String? = null,
    val requiresExplicitResume: Boolean = false
)

internal fun shouldRequireExplicitResume(
    userInitiated: Boolean,
    state: String?,
    hasPendingUidtJob: Boolean,
    stopRequestedByUser: Boolean = false,
    cancellationRequestedByUser: Boolean = false
): Boolean {
    if (!userInitiated || cancellationRequestedByUser) return false
    if (stopRequestedByUser) return true
    return state in setOf("RUNNING", "QUEUED", "RETRYABLE") &&
        !hasPendingUidtJob
}

internal fun mergePendingDownloadRecoveryCandidates(
    queuedDownloads: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
    resumableDownloads: List<ManagedDownloadStorage.PendingResumableDownload>,
    cancelledKeys: Set<String> = emptySet()
): List<PendingDownloadRecoveryCandidate> {
    val merged = linkedMapOf<String, PendingDownloadRecoveryCandidate>()

    queuedDownloads
        .sortedBy(ManagedDownloadStorage.PendingDownloadQueueEntry::order)
        .forEach { entry ->
            merged[entry.stableKey] = PendingDownloadRecoveryCandidate(
                song = entry.song,
                workingFile = null,
                order = entry.order,
                cancelled = entry.stableKey in cancelledKeys,
                operationId = entry.operationId
            )
        }

    resumableDownloads.forEachIndexed { index, pendingDownload ->
        val songKey = pendingDownload.song.stableKey()
        val existing = merged[songKey]
        merged[songKey] = PendingDownloadRecoveryCandidate(
            song = pendingDownload.song,
            workingFile = pendingDownload.workingFile,
            order = existing?.order ?: queuedDownloads.size + index,
            cancelled = existing?.cancelled == true || songKey in cancelledKeys,
            operationId = pendingDownload.operationId ?: existing?.operationId
        )
    }

    return merged.values
        .sortedBy(PendingDownloadRecoveryCandidate::order)
        .mapIndexed { index, candidate -> candidate.copy(order = index) }
}
