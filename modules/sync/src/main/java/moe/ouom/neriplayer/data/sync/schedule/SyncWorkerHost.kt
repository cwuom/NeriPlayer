package moe.ouom.neriplayer.data.sync.schedule

import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome

interface SyncWorkerHost {
    fun autoSyncEnabled(): Boolean
    fun configured(): Boolean
    suspend fun protocolUpgradeApproved(): Boolean
    fun playbackActive(): Boolean
    fun validatedNetwork(): Boolean
    fun deferForPlayback()
    suspend fun synchronize(): Result<SyncResult>
    suspend fun handleFailure(error: Throwable?, manual: Boolean, unexpected: Boolean): SyncWorkerOutcome
}
