package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.sync.SyncData

internal object GitHubSyncUploadPolicy {
    fun shouldUpload(
        remoteData: SyncData?,
        requiresMigrationUpload: Boolean,
        mergedData: SyncData
    ): Boolean {
        if (remoteData == null || requiresMigrationUpload) {
            return true
        }
        return SyncDataChangeDetector.hasDataChanged(remoteData, mergedData)
    }
}
