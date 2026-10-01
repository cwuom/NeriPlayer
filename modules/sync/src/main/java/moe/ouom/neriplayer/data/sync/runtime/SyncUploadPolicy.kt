package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.change.SyncDataChangeDetector

object SyncUploadPolicy {
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
