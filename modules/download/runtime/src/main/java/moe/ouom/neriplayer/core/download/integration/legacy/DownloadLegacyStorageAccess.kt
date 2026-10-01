package moe.ouom.neriplayer.core.download.integration.legacy

import android.content.Context
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.json.JSONObject

object DownloadLegacyStorageAccess {
    const val PENDING_QUEUE_CUTOVER_STATE_KEY = DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY
    const val CANCELLED_KEYS_CUTOVER_STATE_KEY = DownloadRecoveryRoomStore.CANCELLED_KEYS_CUTOVER_STATE_KEY
    const val USER_CLEARED_STATE = DownloadRecoveryRoomStore.USER_CLEARED_STATE
    const val CATALOG_CUTOVER_STATE_KEY = DownloadedSongCatalogRoomStore.CUTOVER_STATE_METADATA_KEY
    const val SNAPSHOT_CUTOVER_STATE_KEY = ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY

    fun serializeAudioMetadata(metadata: DownloadedAudioMetadata): JSONObject =
        ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(metadata)

    suspend fun bootstrapLegacyQueues(context: Context) {
        DownloadRecoveryRoomStore(context).bootstrapLegacyFilesOnce()
    }

    suspend fun upsertOperation(
        context: Context,
        request: DownloadExecutionRequest,
        state: String,
        queueOrder: Int,
        database: NeriUserDataDatabase
    ) {
        DownloadExecutionRoomStore.upsert(context, request, state, queueOrder, database = database)
    }

    suspend fun updateOperationState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String?,
        database: NeriUserDataDatabase
    ): Boolean = DownloadExecutionRoomStore.updateState(
        context, operationId, state, errorCode, database
    )
}
