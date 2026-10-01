package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState as State

internal object DownloadCoreTransitionPolicy {
    private val restrictedSources = mapOf(
        State.COMMITTING to setOf(State.PENDING_QUEUE, State.QUEUED, State.RUNNING),
        State.CORE_COMMITTED to setOf(State.COMMITTING),
        State.RETRYABLE to setOf(State.PENDING_QUEUE, State.QUEUED, State.RUNNING, State.COMMITTING),
        State.METADATA_ACTION_REQUIRED to setOf(State.DEGRADED_COMPLETE)
    )

    fun resolve(current: State, requested: State): Boolean? {
        val sources = restrictedSources[requested]
        if (sources != null) return current in sources
        if (isRunningContinuation(current, requested)) return true
        return resolvePendingPublication(current, requested)
    }

    private fun isRunningContinuation(current: State, requested: State): Boolean =
        requested == State.RUNNING && current in setOf(State.RUNNING, State.COMMITTING)

    private fun resolvePendingPublication(current: State, requested: State): Boolean? = when (current) {
        State.METADATA_ACTION_REQUIRED -> requested in DownloadOperationStateGroups.enrichment
        State.CANCEL_REQUESTED -> requested in DownloadOperationStateGroups.coreCommitted
        else -> null
    }
}
