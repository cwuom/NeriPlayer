package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot

interface SyncBackend<TVersion> {
    val isFirstSync: Boolean
    val lastSyncTime: Long
    val mutationConflictMessage: String
    suspend fun fetch(): Result<SyncDatasetRemoteSnapshot<TVersion>>
    suspend fun refetch(version: TVersion): Result<SyncDatasetRemoteSnapshot<TVersion>>
    suspend fun upload(data: SyncDataset, version: TVersion): Result<TVersion>
    fun remoteChanged(version: TVersion): Boolean
    fun isConflict(error: Throwable?): Boolean
    fun saveRemoteVersion(version: TVersion)
    fun saveSyncTime(timestamp: Long)
    fun saveCompletedSyncTime(timestamp: Long): Boolean
    fun scheduleFollowUp()
    fun onFailure(error: Throwable)
}
