package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetMergeResult
import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution

internal class SyncSessionCommitter(
    private val local: SyncLocalDataStore,
    private val nowMs: () -> Long
) {
    suspend fun <TVersion> commit(
        backend: SyncBackend<TVersion>,
        resolution: SyncUploadResolution<SyncDatasetMergeResult, TVersion>,
        firstSync: Boolean,
        mutationVersion: Long
    ) {
        val applied = if (local.mutationVersion() == mutationVersion) {
            local.apply(
                resolution.merged.dataset,
                firstSync || resolution.remoteChangedDuringSync,
                mutationVersion
            )
        } else {
            false
        }
        val localUnchanged = applied && local.mutationVersion() == mutationVersion
        if (localUnchanged) {
            backend.saveRemoteVersion(resolution.remoteVersion)
            backend.saveSyncTime(nowMs())
        } else {
            backend.scheduleFollowUp()
        }
    }
}
