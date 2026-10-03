package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset

interface SyncLocalDataStore {
    suspend fun awaitInitialized(): Boolean
    fun mutationVersion(): Long
    suspend fun snapshot(): SyncDataset
    suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean
}
