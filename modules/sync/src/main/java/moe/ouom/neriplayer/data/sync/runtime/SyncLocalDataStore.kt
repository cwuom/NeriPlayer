package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData

interface SyncLocalDataStore {
    suspend fun awaitInitialized(): Boolean
    fun mutationVersion(): Long
    fun snapshot(): SyncData
    suspend fun apply(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean
}
