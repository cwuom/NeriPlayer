package moe.ouom.neriplayer.core.download

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
    INCOMPLETE_ROOT_ENUMERATION,
    SUSPICIOUS_EMPTY_RESULT,
    SUPERSEDED_BY_METADATA_CHANGE
}
