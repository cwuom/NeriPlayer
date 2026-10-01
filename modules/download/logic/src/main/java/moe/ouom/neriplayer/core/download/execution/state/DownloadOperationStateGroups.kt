package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState as State

internal object DownloadOperationStateGroups {
    val coreCommitted = listOf(
        State.CORE_COMMITTED, State.ASSETS_ENRICHING, State.FINALIZED, State.DEGRADED_COMPLETE
    )
    val resumableCore = setOf(State.CORE_COMMITTED, State.ASSETS_ENRICHING, State.DEGRADED_COMPLETE)
    val interrupted = setOf(State.RUNNING, State.COMMITTING) + resumableCore
    val preCore = setOf(State.PENDING_QUEUE, State.QUEUED, State.RUNNING, State.RETRYABLE)
    val cancellable = setOf(State.QUEUED, State.RUNNING, State.STOPPED, State.RETRYABLE)
    val enrichment = setOf(State.ASSETS_ENRICHING, State.FINALIZED)
}
