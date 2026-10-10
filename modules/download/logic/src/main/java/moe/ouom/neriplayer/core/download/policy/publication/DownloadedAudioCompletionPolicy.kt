package moe.ouom.neriplayer.core.download.policy.publication

import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata

fun isAcceptedDownloadedAudioEmbeddingState(
    state: DownloadedAudioEmbeddingState?
): Boolean {
    return state == DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED ||
        state == DownloadedAudioEmbeddingState.USER_DISABLED ||
        state == DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
}

fun isFinalizedDownloadedAudioEntry(
    rootEntriesComplete: Boolean,
    isPendingAudioWrite: Boolean,
    metadata: DownloadedAudioMetadata?
): Boolean {
    return rootEntriesComplete &&
        !isPendingAudioWrite &&
        metadata?.audioPublicationPending != true &&
        isFinalizedDownloadedMetadata(metadata)
}

fun resolvePersistedDownloadedAudioEmbeddingState(
    downloadFinalized: Boolean,
    requestedState: DownloadedAudioEmbeddingState?,
    existingState: DownloadedAudioEmbeddingState?
): DownloadedAudioEmbeddingState? {
    if (!downloadFinalized) {
        return requestedState?.takeIf(::isUnfinalizedDownloadedAudioEmbeddingState)
            ?: existingState?.takeIf(::isUnfinalizedDownloadedAudioEmbeddingState)
    }
    return requestedState ?: existingState ?: DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
}

private fun isUnfinalizedDownloadedAudioEmbeddingState(
    state: DownloadedAudioEmbeddingState
): Boolean {
    return state == DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER ||
        state == DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
}

fun isUnfinalizedDownloadedMetadata(
    metadata: DownloadedAudioMetadata?
): Boolean {
    return !isFinalizedDownloadedMetadata(metadata)
}

fun isFinalizedDownloadedMetadata(
    metadata: DownloadedAudioMetadata?
): Boolean {
    // 写不了内嵌标签的容器只能靠 sidecar 完成；该状态本身不是完成证据，必须有显式 downloadFinalized
    return metadata?.downloadFinalized == true &&
        (isAcceptedDownloadedAudioEmbeddingState(metadata.metadataEmbeddingState) ||
            metadata.metadataEmbeddingState == DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER)
}
