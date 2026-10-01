package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState as State

internal object DownloadFinalizationTransitionPolicy {
    private val invalidatable = DownloadOperationStateGroups.preCore + setOf(
        State.COMMITTING, State.CANCEL_REQUESTED, State.STOPPED
    )
    private val completable = DownloadOperationStateGroups.preCore +
        DownloadOperationStateGroups.coreCommitted + State.COMMITTING

    fun resolve(current: State, requested: State): Boolean = when (requested) {
        State.INVALID -> current in invalidatable
        State.COMPLETED -> current in completable
        else -> allowsEnrichment(current, requested)
    }

    private fun allowsEnrichment(current: State, requested: State): Boolean {
        if (current == State.DEGRADED_COMPLETE && requested in DownloadOperationStateGroups.enrichment) {
            return true
        }
        val coreIndex = DownloadOperationStateGroups.coreCommitted.indexOf(current)
        return if (coreIndex >= 0) {
            DownloadOperationStateGroups.coreCommitted.indexOf(requested) >= coreIndex
        } else {
            current in DownloadOperationStateGroups.preCore
        }
    }
}
