package moe.ouom.neriplayer.core.download

import java.util.Locale

/** 已下载音频元信息嵌入的可审计完成状态 */
internal enum class DownloadedAudioEmbeddingState {
    EMBEDDED_VERIFIED,
    USER_DISABLED,
    UNSUPPORTED_CONTAINER,
    LEGACY_UNVERIFIED;

    companion object {
        fun fromPersisted(value: String?): DownloadedAudioEmbeddingState? {
            val normalized = value?.trim()?.takeIf(String::isNotBlank)
                ?.uppercase(Locale.ROOT)
                ?: return null
            return entries.firstOrNull { state -> state.name == normalized }
        }
    }
}

internal fun isAcceptedDownloadedAudioEmbeddingState(
    state: DownloadedAudioEmbeddingState?
): Boolean {
    return state == DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED ||
        state == DownloadedAudioEmbeddingState.USER_DISABLED
}

internal fun isFinalizedDownloadedAudioEntry(
    rootEntriesComplete: Boolean,
    isPendingAudioWrite: Boolean,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): Boolean {
    return rootEntriesComplete &&
        !isPendingAudioWrite &&
        isFinalizedDownloadedMetadata(metadata)
}

internal fun resolvePersistedDownloadedAudioEmbeddingState(
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
