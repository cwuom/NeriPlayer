package moe.ouom.neriplayer.core.download.execution.state

import moe.ouom.neriplayer.data.model.download.execution.DownloadOperationState as State

internal object DownloadControlTransitionPolicy {
    private val storageRecoveryTargets = DownloadOperationStateGroups.preCore + setOf(
        State.CANCEL_REQUESTED, State.CANCELLED, State.INVALID
    )

    fun resolve(current: State, requested: State): Boolean? = when {
        current == State.WAITING_STORAGE_MUTATION -> requested in storageRecoveryTargets
        requested == State.CANCEL_REQUESTED -> current in DownloadOperationStateGroups.cancellable
        requested == State.CANCELLED ->
            current == State.CANCEL_REQUESTED || current in DownloadOperationStateGroups.cancellable
        else -> null
    }
}
