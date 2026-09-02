package moe.ouom.neriplayer.core.download.storage.queue

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection

internal fun isLegacyQueueImportSuppressed(cutoverState: String?): Boolean {
    return cutoverState == DownloadRecoveryRoomStore.USER_CLEARED_STATE
}

/** compatibility facade for v15 queue files; operation journal owns new work */
internal class DownloadRecoveryRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) {
    private val appContext = context.applicationContext

    suspend fun upsertPendingDownloadQueue(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null
    ): List<String> {
        return database.withTransaction {
            val distinctSongs = songs.distinctBy(SongItem::stableKey)
            val songKeys = distinctSongs.map(SongItem::stableKey)
            val inFlightOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserCancelledStops = true
                )
                .mapValues { (_, request) -> request.operationId }
            DownloadExecutionRoomStore.rehydrateMalformedReusableOperations(
                context = appContext,
                songs = distinctSongs.filter { song ->
                    inFlightOperationIds[song.stableKey()] == null
                },
                userInitiated = userInitiated,
                requiresWifiNetwork = requiresWifiNetwork,
                downloadAudioQuality = downloadAudioQuality,
                updatedAtMs = nowMs,
                database = database
            )
            val reusableEntries = DownloadExecutionRoomStore.listByStates(
                context = appContext,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                excludeUserStoppedOperations = true,
                database = database
            )
            val reusableGroups = reusableEntries.groupBy { it.request.song.stableKey() }
            val existing = reusableGroups
                .mapValues { (_, entries) ->
                    entries.maxWithOrNull(
                        compareBy<DownloadExecutionRoomStore.StateEntry> {
                            it.createdAtMs
                        }.thenBy { it.request.operationId }
                    ) ?: error("missing operation entry")
                }
                .toMutableMap()
            reusableGroups.forEach { (_, entries) ->
                val winnerId = entries.maxWithOrNull(
                    compareBy<DownloadExecutionRoomStore.StateEntry> { it.createdAtMs }
                        .thenBy { it.request.operationId }
                )?.request?.operationId
                entries.filterNot { entry -> entry.request.operationId == winnerId }
                    .forEach { entry ->
                        database.downloadOperationDao().transitionState(
                            operationId = entry.request.operationId,
                            expectedStates = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                            state = "INVALID",
                            updatedAtMs = nowMs,
                            errorCode = "DUPLICATE_STABLE_KEY_OPERATION"
                        )
                    }
            }
            val existingOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                    excludeUserStoppedOperations = true
                )
                .mapValues { (_, request) -> request.operationId }
            var nextOrder = (existing.values.maxOfOrNull { it.queueOrder } ?: -1) + 1
            distinctSongs.map { song ->
                val key = song.stableKey()
                inFlightOperationIds[key]?.let { operationId ->
                    return@map operationId
                }
                val old = existing[key]
                val operationId = existingOperationIds[key]
                    ?: old?.request?.operationId
                    ?: UUID.randomUUID().toString()
                val effectiveRequiresWifiNetwork = if (userInitiated) {
                    requiresWifiNetwork
                } else {
                    old?.request?.requiresWifiNetwork ?: requiresWifiNetwork
                }
                DownloadExecutionRoomStore.upsert(
                    context = appContext,
                    request = DownloadExecutionRequest(
                        operationId = operationId,
                        song = song,
                        preserveStaging = old?.request?.preserveStaging ?: false,
                        requiresWifiNetwork = effectiveRequiresWifiNetwork,
                        attemptId = old?.request?.attemptId,
                        artifactLeaseId = old?.request?.artifactLeaseId
                            ?: UUID.randomUUID().toString(),
                        userInitiated = old?.request?.userInitiated == true || userInitiated,
                        downloadAudioQuality = old?.request?.downloadAudioQuality
                            ?: downloadAudioQuality
                    ),
                    state = PENDING_QUEUE_STATE,
                    queueOrder = old?.queueOrder ?: nextOrder++,
                    createdAtMs = old?.createdAtMs ?: nowMs,
                    database = database
                )
                operationId
            }
        }
    }

    suspend fun findQueuedOperationIdForSong(songKey: String): String? {
        val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return null
        return DownloadExecutionRoomStore.findReadableOperationIdForSong(
            context = appContext,
            songKey = normalizedKey,
            database = database,
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        )
    }

    /**
     * records a user request that must wait for a SAF mutation without letting an
     * execution host observe it as runnable work
     * returns only operation ids that remain in the waiting state
     */
    suspend fun upsertWaitingStorageMutation(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null
    ): List<String> {
        if (songs.isEmpty()) return emptyList()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val distinctSongs = songs.distinctBy(SongItem::stableKey)
            val songKeys = distinctSongs.map(SongItem::stableKey)
            val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(appContext)
            val waitingEntries = DownloadExecutionRoomStore.listByStates(
                context = appContext,
                states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                excludeUserStoppedOperations = true,
                database = database
            )
            val waitingGroups = waitingEntries.groupBy { entry ->
                entry.request.song.stableKey()
            }
            val waitingWinners = waitingGroups.mapValues { (_, entries) ->
                entries.maxWithOrNull(
                    compareBy<DownloadExecutionRoomStore.StateEntry> { entry ->
                        entry.createdAtMs
                    }.thenBy { entry -> entry.request.operationId }
                ) ?: error("missing waiting storage mutation entry")
            }
            waitingGroups.forEach { (_, entries) ->
                val winnerId = entries.maxWithOrNull(
                    compareBy<DownloadExecutionRoomStore.StateEntry> { entry ->
                        entry.createdAtMs
                    }.thenBy { entry -> entry.request.operationId }
                )?.request?.operationId
                entries.filterNot { entry -> entry.request.operationId == winnerId }
                    .forEach { entry ->
                        dao.transitionState(
                            operationId = entry.request.operationId,
                            expectedStates = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                            state = "INVALID",
                            updatedAtMs = nowMs,
                            errorCode = "DUPLICATE_STABLE_KEY_OPERATION"
                        )
                    }
            }
            val inFlightOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserCancelledStops = true
                )
                .mapValues { (_, request) -> request.operationId }
            val reusableOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                    excludeUserStoppedOperations = true
                )
                .mapValues { (_, request) -> request.operationId }
            val blockedOperationIds = linkedMapOf<String, String>()
            songKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { stableKeyChunk ->
                dao.findAllHeadersByStableKeys(
                    libraryId = libraryId,
                    stableKeys = stableKeyChunk,
                    states = WAITING_STORAGE_MUTATION_BLOCKING_STATES
                ).forEach { header ->
                    blockedOperationIds.putIfAbsent(header.stableKey, header.operationId)
                }
            }
            val stoppedWaitingOperationIds = linkedMapOf<String, String>()
            songKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { stableKeyChunk ->
                dao.findAllHeadersByStableKeys(
                    libraryId = libraryId,
                    stableKeys = stableKeyChunk,
                    states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
                ).forEach { header ->
                    if (header.stopRequestedByUser) {
                        stoppedWaitingOperationIds.putIfAbsent(
                            header.stableKey,
                            header.operationId
                        )
                    }
                }
            }
            val deterministicOperationIdsBySongKey = distinctSongs.associate { song ->
                song.stableKey() to waitingStorageMutationOperationId(libraryId, song.stableKey())
            }
            val deterministicOperationsById = linkedMapOf<String, String>()
            deterministicOperationIdsBySongKey.values
                .chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE)
                .forEach { operationIdChunk ->
                    dao.findAllHeadersByOperationIds(operationIdChunk).forEach { header ->
                        deterministicOperationsById[header.operationId] = header.state
                    }
                }
            var nextOrder = (
                (waitingWinners.values + DownloadExecutionRoomStore.listByStates(
                    context = appContext,
                    states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                    database = database
                ))
                    .maxOfOrNull { entry -> entry.queueOrder } ?: -1
                ) + 1
            distinctSongs.mapNotNull { song ->
                val key = song.stableKey()
                if (
                    inFlightOperationIds[key] != null ||
                        reusableOperationIds[key] != null ||
                        blockedOperationIds[key] != null ||
                        stoppedWaitingOperationIds[key] != null
                ) {
                    return@mapNotNull null
                }
                val existing = waitingWinners[key]
                val deterministicOperationId = checkNotNull(
                    deterministicOperationIdsBySongKey[key]
                )
                val deterministicOperationState = deterministicOperationsById[
                    deterministicOperationId
                ]
                val mustReplaceDeterministicOperation =
                    deterministicOperationState in WAITING_STORAGE_MUTATION_REPLACED_TERMINAL_STATES
                val operationId = existing?.request?.operationId
                    ?: if (mustReplaceDeterministicOperation) {
                        UUID.randomUUID().toString()
                    } else {
                        deterministicOperationId
                    }
                val effectiveRequiresWifiNetwork = if (userInitiated) {
                    requiresWifiNetwork
                } else {
                    existing?.request?.requiresWifiNetwork ?: requiresWifiNetwork
                }
                DownloadExecutionRoomStore.upsert(
                    context = appContext,
                    request = DownloadExecutionRequest(
                        operationId = operationId,
                        song = song,
                        preserveStaging = existing?.request?.preserveStaging ?: false,
                        requiresWifiNetwork = effectiveRequiresWifiNetwork,
                        attemptId = existing?.request?.attemptId,
                        artifactLeaseId = existing?.request?.artifactLeaseId
                            ?: UUID.randomUUID().toString(),
                        userInitiated = existing?.request?.userInitiated == true || userInitiated,
                        downloadAudioQuality = existing?.request?.downloadAudioQuality
                            ?: downloadAudioQuality
                    ),
                    state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
                    queueOrder = existing?.queueOrder ?: nextOrder++,
                    createdAtMs = existing?.createdAtMs ?: nowMs,
                    database = database
                )
                operationId
            }
        }
    }

    suspend fun listWaitingStorageMutations(): List<DownloadExecutionRoomStore.StateEntry> {
        return DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
            excludeUserStoppedOperations = true,
            database = database
        )
            .sortedWith(compareBy({ it.queueOrder }, { it.request.operationId }))
    }

    suspend fun promoteWaitingStorageMutation(
        operationId: String,
        stableKey: String
    ): Boolean {
        return DownloadExecutionRoomStore.promoteWaitingStorageMutation(
            context = appContext,
            operationId = operationId,
            stableKey = stableKey,
            database = database
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
                    operationId = entry.request.operationId,
                    requiresWifiNetwork = entry.request.requiresWifiNetwork
                )
            }
    }

    suspend fun removePendingDownloadQueueEntries(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet().toList()
        CLEARABLE_PENDING_QUEUE_STATES.forEach { state ->
            DownloadExecutionRoomStore.deleteByStateAndStableKeys(
                context = appContext,
                state = state,
                stableKeys = keys,
                database = database
            )
        }
    }

    suspend fun clearPendingDownloadQueue() {
        database.withTransaction {
            markLegacyQueueImportSuppressed()
            CLEARABLE_PENDING_QUEUE_STATES.forEach { state ->
                DownloadExecutionRoomStore.deleteByState(
                    context = appContext,
                    state = state,
                    database = database
                )
            }
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
        if (!queueFile.isFile) {
            database.withTransaction {
                if (!isLegacyQueueFileImportBlocked() && !queueFile.isFile) {
                    markPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)
                }
            }
            return
        }
        val parsed = ManagedDownloadQueueStore.readPendingDownloadQueueFile(queueFile) ?: return
        database.withTransaction {
            if (isLegacyQueueFileImportBlocked()) return@withTransaction
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
    }

    private suspend fun bootstrapLegacyCancelledFile() {
        val cancelledFile = File(appContext.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        if (isLegacyQueueImportSuppressed()) return
        if (isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)) return
        if (!cancelledFile.isFile) {
            database.withTransaction {
                if (
                    !isLegacyQueueImportSuppressed() &&
                        !isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY) &&
                        !cancelledFile.isFile
                ) {
                    markPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
                }
            }
            return
        }
        val parsed = ManagedDownloadQueueStore.readCancelledDownloadKeysFile(cancelledFile) ?: return
        database.withTransaction {
            if (
                isLegacyQueueImportSuppressed() ||
                    isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
            ) {
                return@withTransaction
            }
            bootstrapLegacyQueueFile()
            parsed.filter(String::isNotBlank).distinct().forEach { stableKey ->
                val existing = DownloadExecutionRoomStore.listByStates(
                    context = appContext,
                    states = PENDING_QUEUE_VISIBLE_STATES + listOf(
                        "QUEUED", WAITING_STORAGE_MUTATION_OPERATION_STATE, "RUNNING", "STOPPED",
                        "RETRYABLE", "CANCEL_REQUESTED", "CANCELLED"
                    ),
                    database = database
                ).filter { it.request.song.stableKey() == stableKey }
                if (existing.isEmpty()) {
                    // legacy cancellation markers without a durable operation have no owner
                    // and must not become synthetic runtime operations
                } else {
                    existing.forEach { entry ->
                        if (entry.request.operationId.isNotBlank()) {
                            DownloadExecutionRoomStore.requestCancel(
                                context = appContext,
                                operationId = entry.request.operationId,
                                database = database
                            )
                        }
                    }
                }
            }
            markPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
        }
    }

    private suspend fun isRoomPrimary(key: String): Boolean {
        return database.syncMetadataDao().getMigrationMetadata(key)?.value == ROOM_PRIMARY_STATE
    }

    private suspend fun isLegacyQueueFileImportBlocked(): Boolean {
        val state = database.syncMetadataDao()
            .getMigrationMetadata(PENDING_QUEUE_CUTOVER_STATE_KEY)
            ?.value
        return state == ROOM_PRIMARY_STATE || isLegacyQueueImportSuppressed(state)
    }

    private suspend fun isLegacyQueueImportSuppressed(): Boolean {
        val state = database.syncMetadataDao()
            .getMigrationMetadata(PENDING_QUEUE_CUTOVER_STATE_KEY)
            ?.value
        return isLegacyQueueImportSuppressed(state)
    }

    private suspend fun markLegacyQueueImportSuppressed() {
        val nowMs = System.currentTimeMillis()
        listOf(PENDING_QUEUE_CUTOVER_STATE_KEY, CANCELLED_KEYS_CUTOVER_STATE_KEY).forEach { key ->
            database.syncMetadataDao().upsertMigrationMetadata(
                MigrationMetadataEntity(
                    key = key,
                    value = USER_CLEARED_STATE,
                    updatedAt = nowMs
                )
            )
        }
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
        const val USER_CLEARED_STATE = "user_cleared"
        private const val PENDING_QUEUE_STATE = "QUEUED"
        private const val DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE = 900
        private val PENDING_QUEUE_STATES = listOf(
            PENDING_QUEUE_STATE,
            "PENDING_QUEUE"
        )
        private val CLEARABLE_PENDING_QUEUE_STATES = PENDING_QUEUE_STATES +
            "RETRYABLE" +
            WAITING_STORAGE_MUTATION_OPERATION_STATE
        private val WAITING_STORAGE_MUTATION_BLOCKING_STATES = listOf(
            "CANCEL_REQUESTED",
            "CANCELLED",
            "STOPPED"
        )
        private val WAITING_STORAGE_MUTATION_REPLACED_TERMINAL_STATES = setOf(
            "COMPLETED",
            "FINALIZED",
            "INVALID"
        )
        private val PENDING_QUEUE_VISIBLE_STATES = listOf(
            PENDING_QUEUE_STATE,
            "CANCEL_REQUESTED",
            "PENDING_QUEUE",
            "RETRYABLE"
        )
        private fun pendingOperationId(stableKey: String): String {
            return UUID.nameUUIDFromBytes(
                "pending-download:$stableKey".toByteArray(Charsets.UTF_8)
            ).toString()
        }

        private fun waitingStorageMutationOperationId(
            libraryId: String,
            stableKey: String
        ): String {
            return UUID.nameUUIDFromBytes(
                "waiting-storage-mutation:$libraryId:$stableKey".toByteArray(Charsets.UTF_8)
            ).toString()
        }

    }
}
