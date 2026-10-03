package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetMergeResult
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution

internal object SyncSessionResultPolicy {
    fun <TVersion> result(
        resolution: SyncUploadResolution<SyncDatasetMergeResult, TVersion>,
        firstSync: Boolean,
        initialRemoteMissing: Boolean,
        noChangeMessage: String,
        initialUploadMessage: String
    ): SyncResult = when {
        !resolution.uploadPerformed && !resolution.remoteChangedDuringSync && !firstSync ->
            SyncResult(success = true, message = noChangeMessage)
        initialRemoteMissing && resolution.uploadPerformed && !resolution.remoteChangedDuringSync ->
            SyncResult(success = true, message = initialUploadMessage)
        else -> resolution.merged.syncResult
    }
}
