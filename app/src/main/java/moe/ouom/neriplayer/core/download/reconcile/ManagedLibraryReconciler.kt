package moe.ouom.neriplayer.core.download.reconcile

import moe.ouom.neriplayer.data.model.download.ScanConfidence
import moe.ouom.neriplayer.data.model.download.EmptyScanDecision
import moe.ouom.neriplayer.data.model.download.EmptyScanObservation

class ManagedLibraryReconciler {
    private var pendingEmpty: PendingEmptyScan? = null
    private var lastCompleteEmpty: ScanMarker? = null

    fun observeEmpty(
        observation: EmptyScanObservation,
        existingCount: Int
    ): EmptyScanDecision {
        if (existingCount == 0) {
            pendingEmpty = null
            lastCompleteEmpty = null
            return EmptyScanDecision.CLEAR_CONFIRMED
        }
        if (observation.confidence != ScanConfidence.COMPLETE) {
            pendingEmpty = null
            lastCompleteEmpty = null
            return EmptyScanDecision.PRESERVE
        }
        val marker = ScanMarker(observation.rootKey, observation.scanId)
        if (lastCompleteEmpty == marker) {
            return EmptyScanDecision.PRESERVE
        }
        if (
            observation.knownReferenceCount <= 0 ||
            observation.missingReferenceCount != observation.knownReferenceCount
        ) {
            pendingEmpty = null
            lastCompleteEmpty = marker
            return EmptyScanDecision.PRESERVE
        }
        val previous = pendingEmpty
        if (
            previous != null &&
            previous.rootKey == observation.rootKey &&
            previous.scanId != observation.scanId &&
            observation.isUncached
        ) {
            pendingEmpty = null
            lastCompleteEmpty = marker
            return EmptyScanDecision.CLEAR_CONFIRMED
        }
        pendingEmpty = PendingEmptyScan(
            rootKey = observation.rootKey,
            scanId = observation.scanId
        )
        lastCompleteEmpty = marker
        return EmptyScanDecision.WAIT_FOR_CONFIRMATION
    }

    fun reset() {
        pendingEmpty = null
        lastCompleteEmpty = null
    }

    private data class PendingEmptyScan(
        val rootKey: String,
        val scanId: Long
    )

    private data class ScanMarker(
        val rootKey: String,
        val scanId: Long
    )
}
