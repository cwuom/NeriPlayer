package moe.ouom.neriplayer.data.model.download

enum class ScanConfidence {
    COMPLETE,
    PARTIAL,
    ROOT_UNAVAILABLE,
    PERMISSION_LOST,
    PROVIDER_ERROR
}

enum class EmptyScanDecision {
    PRESERVE,
    WAIT_FOR_CONFIRMATION,
    CLEAR_CONFIRMED
}

data class EmptyScanObservation(
    val rootKey: String,
    val confidence: ScanConfidence,
    val isUncached: Boolean,
    val knownReferenceCount: Int,
    val missingReferenceCount: Int,
    val scanId: Long
)
