package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncRemoteSnapshot

interface SyncBackend<TVersion> {
    val isFirstSync: Boolean
    val lastSyncTime: Long
    val mutationConflictMessage: String
    suspend fun fetch(): Result<SyncRemoteSnapshot<TVersion>>
    suspend fun refetch(version: TVersion): Result<SyncRemoteSnapshot<TVersion>>
    suspend fun upload(data: SyncData, version: TVersion): Result<TVersion>
    fun remoteChanged(version: TVersion): Boolean
    fun isConflict(error: Throwable?): Boolean
    fun saveRemoteVersion(version: TVersion)
    fun saveSyncTime(timestamp: Long)
    fun scheduleFollowUp()
    fun onFailure(error: Throwable)
}
