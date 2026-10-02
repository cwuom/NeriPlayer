package moe.ouom.neriplayer.data.sync.host

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot

internal suspend fun <TVersion> observeCurrentSyncProtocol(
    snapshot: SyncDatasetRemoteSnapshot<TVersion>,
    protocolVersion: Int = SyncProtocolUpgradeRepository.CURRENT_PROTOCOL_VERSION,
    observe: suspend (Int) -> Unit
): Result<SyncDatasetRemoteSnapshot<TVersion>> = try {
    observe(protocolVersion)
    Result.success(snapshot)
} catch (error: Exception) {
    try { snapshot.dataset?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
    if (error is CancellationException) throw error
    Result.failure(error)
}
