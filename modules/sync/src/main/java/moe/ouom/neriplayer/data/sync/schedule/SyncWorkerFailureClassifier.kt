package moe.ouom.neriplayer.data.sync.schedule

import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind

class SyncWorkerFailureClassifier(private val kinds: Map<Class<out Throwable>, SyncWorkerFailureKind>) {
    fun classify(error: Throwable?): SyncWorkerFailureKind = kinds.entries
        .firstOrNull { it.key.isInstance(error) }?.value ?: SyncWorkerFailureKind.OTHER
}
