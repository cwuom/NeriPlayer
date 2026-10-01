package moe.ouom.neriplayer.data.model.download

enum class ManagedLibraryProcessingReason {
    LEGACY_DATABASE_UPGRADE,
    DIRECTORY_CHANGE
}

enum class ManagedLibraryProcessingPhase {
    UPGRADING_DATABASE,
    REBUILDING_INDEX,
    WAITING_FOR_RETRY
}

sealed interface ManagedLibraryProcessingState {
    val operationId: String?
    val reason: ManagedLibraryProcessingReason?
    val phase: ManagedLibraryProcessingPhase?
    val processed: Int?
    val total: Int?
    val currentItem: String?

    data object Idle : ManagedLibraryProcessingState {
        override val operationId: String? = null
        override val reason: ManagedLibraryProcessingReason? = null
        override val phase: ManagedLibraryProcessingPhase? = null
        override val processed: Int? = null
        override val total: Int? = null
        override val currentItem: String? = null
    }

    data class Running(
        override val operationId: String,
        override val reason: ManagedLibraryProcessingReason,
        override val phase: ManagedLibraryProcessingPhase,
        override val processed: Int? = null,
        override val total: Int? = null,
        override val currentItem: String? = null
    ) : ManagedLibraryProcessingState

    data class WaitingForRetry(
        override val operationId: String,
        override val reason: ManagedLibraryProcessingReason,
        override val phase: ManagedLibraryProcessingPhase =
            ManagedLibraryProcessingPhase.WAITING_FOR_RETRY,
        override val processed: Int? = null,
        override val total: Int? = null,
        override val currentItem: String? = null
    ) : ManagedLibraryProcessingState
}
