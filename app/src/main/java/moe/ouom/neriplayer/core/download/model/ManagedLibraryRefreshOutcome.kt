package moe.ouom.neriplayer.core.download.model

sealed interface ManagedLibraryRefreshOutcome {
    data class Published(
        val rootKey: String?,
        val songCount: Int
    ) : ManagedLibraryRefreshOutcome

    data class Preserved(
        val reason: ManagedLibraryRefreshPreserveReason
    ) : ManagedLibraryRefreshOutcome

    data class Failed(
        val detail: String
    ) : ManagedLibraryRefreshOutcome
}

enum class ManagedLibraryRefreshPreserveReason {
    DOWNLOAD_CLEAR_IN_PROGRESS,
    EMPTY_ROOT_CONFIRMATION_PENDING,
    INCOMPLETE_ROOT_ENUMERATION,
    INCOMPLETE_METADATA_READ,
    SUSPICIOUS_EMPTY_RESULT,
    SUPERSEDED_BY_METADATA_CHANGE
}
