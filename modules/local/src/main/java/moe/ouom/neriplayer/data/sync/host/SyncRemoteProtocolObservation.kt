package moe.ouom.neriplayer.data.sync.host

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot

internal suspend fun <TVersion> observeCurrentSyncProtocol(
    snapshot: SyncDatasetRemoteSnapshot<TVersion>,
    observe: suspend () -> Unit
): Result<SyncDatasetRemoteSnapshot<TVersion>> = try {
    observe()
    Result.success(snapshot)
} catch (error: Exception) {
    try { snapshot.dataset?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
    if (error is CancellationException) throw error
    Result.failure(error)
}
