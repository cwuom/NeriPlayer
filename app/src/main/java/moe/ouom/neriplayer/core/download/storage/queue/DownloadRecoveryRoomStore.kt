package moe.ouom.neriplayer.core.download.storage.queue

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import java.util.UUID

/** compatibility facade for v15 queue files; operation journal owns new work */
internal class DownloadRecoveryRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) {
    private val appContext = context.applicationContext

    suspend fun upsertPendingDownloadQueue(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = false
    ): List<String> {
        val distinctSongs = songs.distinctBy(SongItem::stableKey)
        val existing = DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
            database = database
        )
            .filter { entry ->
                database.downloadOperationDao()
                    .isUserStopped(entry.request.operationId) != true
            }
            .groupBy { it.request.song.stableKey() }
            .mapValues { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<DownloadExecutionRoomStore.StateEntry> {
                        it.createdAtMs
                    }.thenBy { it.request.operationId }
                ) ?: error("missing operation entry")
            }
            .toMutableMap()
        val existingOperationIds = distinctSongs.associate { song ->
            song.stableKey() to DownloadExecutionRoomStore.findOperationIdForSong(
                context = appContext,
                songKey = song.stableKey(),
                database = database,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
            )
        }
        var nextOrder = (existing.values.maxOfOrNull { it.queueOrder } ?: -1) + 1
        return distinctSongs.map { song ->
            val key = song.stableKey()
            val old = existing[key]
            val deterministicOperationId = pendingOperationId(key)
            val deterministicEntity = database.downloadOperationDao()
                .find(deterministicOperationId)
            val operationId = existingOperationIds[key]
                ?: old?.request?.operationId
                ?: if (
                    deterministicEntity == null ||
                    (
                        deterministicEntity.state in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES &&
                            deterministicEntity.stopRequestedByUser != true
                        )
                ) {
                    deterministicOperationId
                } else {
                    UUID.randomUUID().toString()
                }
            DownloadExecutionRoomStore.upsert(
                context = appContext,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    preserveStaging = old?.request?.preserveStaging ?: false,
                    attemptId = old?.request?.attemptId,
                    userInitiated = old?.request?.userInitiated == true || userInitiated
                ),
                state = PENDING_QUEUE_STATE,
                queueOrder = old?.queueOrder ?: nextOrder++,
                createdAtMs = old?.createdAtMs ?: nowMs,
                database = database
            )
            operationId
        }
    }

    suspend fun findQueuedOperationIdForSong(songKey: String): String? {
        val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return null
        return DownloadExecutionRoomStore.findOperationIdForSong(
            context = appContext,
            songKey = normalizedKey,
            database = database,
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        )
    }

    suspend fun listPendingQueuedDownloads(): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        return DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = PENDING_QUEUE_VISIBLE_STATES,
            database = database
        )
            .sortedWith(compareBy({ it.queueOrder }, { it.request.operationId }))
            .map { entry ->
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = entry.request.song.stableKey(),
                    song = entry.request.song,
                    order = entry.queueOrder,
                    queuedAtMs = entry.createdAtMs,
                    operationId = entry.request.operationId
                )
            }
    }

    suspend fun removePendingDownloadQueueEntries(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet().toList()
        PENDING_QUEUE_STATES.forEach { state ->
            DownloadExecutionRoomStore.deleteByStateAndStableKeys(
                context = appContext,
                state = state,
                stableKeys = keys,
                database = database
            )
        }
    }

    suspend fun clearPendingDownloadQueue() {
        PENDING_QUEUE_STATES.forEach { state ->
            DownloadExecutionRoomStore.deleteByState(
                context = appContext,
                state = state,
                database = database
            )
        }
    }

    suspend fun markCancelledDownloadKeys(
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        markCancelledDownloadKeysInternal(songKeys, nowMs)
    }

    private suspend fun markCancelledDownloadKeysInternal(
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        songKeys.filter(String::isNotBlank).distinct().forEach { key ->
            val operation = DownloadExecutionRoomStore.listByStates(
                context = appContext,
                states = listOf(PENDING_QUEUE_STATE, "QUEUED", "RUNNING", "STOPPED", "RETRYABLE"),
                database = database
            ).firstOrNull { it.request.song.stableKey() == key }
            if (operation != null) {
                DownloadExecutionRoomStore.requestCancel(
                    context = appContext,
                    operationId = operation.request.operationId,
                    database = database,
                    updatedAtMs = nowMs
                )
            }
        }
    }

    suspend fun listCancelledDownloadKeys(): Set<String> {
        return DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = listOf("CANCEL_REQUESTED", "CANCELLED"),
            database = database
        )
            .map { it.request.song.stableKey() }
            .toSet()
    }

    suspend fun removeCancelledDownloadKeys(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet().toList()
        DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = listOf("CANCEL_REQUESTED", "CANCELLED"),
            database = database
        ).filter { it.request.song.stableKey() in keys }
            .forEach { entry ->
                DownloadExecutionRoomStore.clearCancellation(
                    context = appContext,
                    operationId = entry.request.operationId,
                    database = database
                )
            }
    }

    suspend fun discardCancelledDownloadKeys(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet().toList()
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            DownloadExecutionRoomStore.deleteByStateAndStableKeys(
                context = appContext,
                state = state,
                stableKeys = keys,
                database = database
            )
        }
    }

    suspend fun clearCancelledDownloadKeys() {
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            DownloadExecutionRoomStore.deleteByState(
                context = appContext,
                state = state,
                database = database
            )
        }
    }

    /**
     * imports the v15 files once after the Room database is available
     * runtime queue operations must never call this method implicitly
     */
    suspend fun bootstrapLegacyFilesOnce() {
        bootstrapLegacyQueueFile()
        bootstrapLegacyCancelledFile()
    }

    private suspend fun bootstrapLegacyQueueFile() {
        val queueFile = File(appContext.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        if (isRoomPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)) return
        if (!queueFile.isFile) {
            markPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)
            return
        }
        val parsed = ManagedDownloadQueueStore.readPendingDownloadQueueFile(queueFile) ?: return
        parsed.sortedBy { it.order }.forEach { entry ->
            DownloadExecutionRoomStore.upsert(
                context = appContext,
                request = DownloadExecutionRequest(
                    operationId = entry.operationId ?: pendingOperationId(entry.stableKey),
                    song = entry.song,
                    userInitiated = false
                ),
                state = PENDING_QUEUE_STATE,
                queueOrder = entry.order,
                createdAtMs = entry.queuedAtMs,
                database = database
            )
        }
        markPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)
    }

    private suspend fun bootstrapLegacyCancelledFile() {
        val cancelledFile = File(appContext.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        if (isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)) return
        if (!cancelledFile.isFile) {
            markPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
            return
        }
        val parsed = ManagedDownloadQueueStore.readCancelledDownloadKeysFile(cancelledFile) ?: return
        bootstrapLegacyQueueFile()
        parsed.filter(String::isNotBlank).distinct().forEach { stableKey ->
            val existing = DownloadExecutionRoomStore.listByStates(
                context = appContext,
                states = PENDING_QUEUE_VISIBLE_STATES + listOf(
                    "QUEUED", "RUNNING", "STOPPED", "RETRYABLE", "CANCEL_REQUESTED", "CANCELLED"
                ),
                database = database
            ).firstOrNull { it.request.song.stableKey() == stableKey }
            if (existing == null) {
                // legacy cancellation markers without a durable operation have no owner
                // and must not become synthetic runtime operations
            } else if (existing.request.operationId.isNotBlank()) {
                DownloadExecutionRoomStore.requestCancel(
                    context = appContext,
                    operationId = existing.request.operationId,
                    database = database
                )
            }
        }
        markPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
    }

    private suspend fun isRoomPrimary(key: String): Boolean {
        return database.syncMetadataDao().getMigrationMetadata(key)?.value == ROOM_PRIMARY_STATE
    }

    private suspend fun markPrimary(key: String) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = key,
                value = ROOM_PRIMARY_STATE,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val PENDING_QUEUE_CUTOVER_STATE_KEY = "download_pending_queue_cutover_state"
        const val CANCELLED_KEYS_CUTOVER_STATE_KEY = "download_cancelled_keys_cutover_state"
        const val ROOM_PRIMARY_STATE = "room_primary"
        private const val PENDING_QUEUE_STATE = "QUEUED"
        private val PENDING_QUEUE_STATES = listOf(
            PENDING_QUEUE_STATE,
            "PENDING_QUEUE"
        )
        private val PENDING_QUEUE_VISIBLE_STATES = listOf(
            PENDING_QUEUE_STATE,
            "CANCEL_REQUESTED",
            "PENDING_QUEUE"
        )
        private fun pendingOperationId(stableKey: String): String {
            return UUID.nameUUIDFromBytes(
                "pending-download:$stableKey".toByteArray(Charsets.UTF_8)
            ).toString()
        }

    }
}
