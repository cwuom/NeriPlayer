package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.model.DownloadedAudioMetadata

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
    return metadata?.downloadFinalized == true &&
        isAcceptedDownloadedAudioEmbeddingState(metadata.metadataEmbeddingState)
}
