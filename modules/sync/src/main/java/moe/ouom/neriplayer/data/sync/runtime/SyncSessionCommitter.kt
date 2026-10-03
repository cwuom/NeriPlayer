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
    ): Boolean {
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
        val completedAt = nowMs()
        if (localUnchanged) {
            backend.saveRemoteVersion(resolution.remoteVersion)
            backend.saveSyncTime(completedAt)
        } else {
            backend.scheduleFollowUp()
        }
        // 远端交换已完成，新产生的本地数据继续重试，合并检查点仍按应用结果推进
        return backend.saveCompletedSyncTime(completedAt)
    }
}
