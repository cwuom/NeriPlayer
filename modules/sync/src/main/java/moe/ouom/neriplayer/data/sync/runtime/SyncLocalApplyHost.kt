package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData

interface SyncLocalApplyHost {
    suspend fun applyPlaylists(data: SyncData, expectedMutationVersion: Long): Boolean
    fun applyDeletions(data: SyncData, expectedMutationVersion: Long): Boolean
    suspend fun applyFavorites(data: SyncData, expectedMutationVersion: Long): Boolean
    suspend fun applyHistory(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean
    suspend fun applyStatistics(data: SyncData)
    suspend fun applyVideoSkipRules(data: SyncData, expectedMutationVersion: Long): Boolean
}
