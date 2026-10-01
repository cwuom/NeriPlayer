package moe.ouom.neriplayer.data.sync.runtime

import moe.ouom.neriplayer.data.model.sync.SyncMergeResult
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncUploadResolution

internal object SyncSessionResultPolicy {
    fun <TVersion> result(
        resolution: SyncUploadResolution<SyncMergeResult, TVersion>,
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
