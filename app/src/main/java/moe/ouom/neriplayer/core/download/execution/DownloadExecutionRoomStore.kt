package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject

internal object DownloadExecutionRoomStore {
    suspend fun upsert(
        context: Context,
        request: DownloadExecutionRequest,
        state: String,
        queueOrder: Int = 0
    ) {
        val now = System.currentTimeMillis()
        val song = request.song
        NeriUserDataDatabase.getInstance(context).downloadOperationDao().upsert(
            DownloadOperationEntity(
                operationId = request.operationId,
                stableKey = song.stableKey(),
                libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context),
                state = state,
                queueOrder = queueOrder,
                sourceHintJson = JSONObject().apply {
                    put("sourceStableKey", song.sourceStableKey)
                    put("mediaUri", song.mediaUri)
                    put("channelId", song.channelId)
                    put("audioId", song.audioId)
                    put("subAudioId", song.subAudioId)
                    put("playlistContextId", song.playlistContextId)
                }.toString(),
                stagingDirName = request.operationId,
                bytesWritten = 0L,
                totalBytes = null,
                resumeJson = null,
                retryCount = 0,
                nextRetryAtMs = null,
                lastErrorCode = null,
                createdAtMs = now,
                updatedAtMs = now
            )
        )
    }

    suspend fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null
    ) {
        NeriUserDataDatabase.getInstance(context).downloadOperationDao().updateState(
            operationId = operationId,
            state = state,
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        )
    }

    suspend fun delete(context: Context, operationId: String) {
        NeriUserDataDatabase.getInstance(context).downloadOperationDao().delete(operationId)
    }
}
