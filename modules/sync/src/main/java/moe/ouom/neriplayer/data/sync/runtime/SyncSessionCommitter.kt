package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncMergeResult
import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution

internal class SyncSessionCommitter(
    private val local: SyncLocalDataStore,
    private val nowMs: () -> Long
) {
    suspend fun <TVersion> commit(
        backend: SyncBackend<TVersion>,
        resolution: SyncUploadResolution<SyncMergeResult, TVersion>,
        firstSync: Boolean,
        mutationVersion: Long
    ) {
        val applied = if (local.mutationVersion() == mutationVersion) {
            local.apply(
                resolution.merged.mergedData,
                firstSync || resolution.remoteChangedDuringSync,
                mutationVersion
            )
        } else {
            false
        }
        val localUnchanged = applied && local.mutationVersion() == mutationVersion
        backend.saveRemoteVersion(resolution.remoteVersion)
        if (localUnchanged) backend.saveSyncTime(nowMs()) else backend.scheduleFollowUp()
    }
}
