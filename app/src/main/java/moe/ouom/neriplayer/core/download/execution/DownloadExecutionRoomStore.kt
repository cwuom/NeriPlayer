package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject

internal object DownloadExecutionRoomStore {
    internal data class StateEntry(
        val request: DownloadExecutionRequest,
        val queueOrder: Int,
        val createdAtMs: Long
    )

    suspend fun upsert(
        context: Context,
        request: DownloadExecutionRequest,
        state: String,
        queueOrder: Int = 0,
        createdAtMs: Long? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val now = createdAtMs ?: System.currentTimeMillis()
        val song = request.song
        val dao = database.downloadOperationDao()
        val existing = dao.find(request.operationId)
        val restartForNewAttempt = shouldRestartOperation(
            existingState = existing?.state,
            requestedState = state,
            userInitiated = request.userInitiated
        )
        dao.upsert(
            DownloadOperationEntity(
                operationId = request.operationId,
                stableKey = song.stableKey(),
                libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context),
                // Re-scheduling an existing operation must never roll its durable state back.
                state = if (restartForNewAttempt) state else existing?.state ?: state,
                queueOrder = if (queueOrder == 0) existing?.queueOrder ?: 0 else queueOrder,
                sourceHintJson = requestToJson(request).toString(),
                stagingDirName = request.operationId,
                bytesWritten = existing?.bytesWritten ?: 0L,
                totalBytes = existing?.totalBytes,
                resumeJson = existing?.resumeJson,
                retryCount = existing?.retryCount ?: 0,
                nextRetryAtMs = existing?.nextRetryAtMs,
                lastErrorCode = existing?.lastErrorCode,
                stopRequestedByUser = if (restartForNewAttempt) {
                    false
                } else {
                    existing?.stopRequestedByUser ?: false
                },
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now
            )
        )
    }

    suspend fun read(
        context: Context,
        operationId: String
    ): DownloadExecutionRequest? {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .find(operationId)
            ?.let(::requestFromEntity)
    }

    suspend fun listByState(
        context: Context,
        state: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        return database.downloadOperationDao()
            .findByState(state)
            .mapNotNull { entity ->
                requestFromEntity(entity)?.let { request ->
                    StateEntry(
                        request = request,
                        queueOrder = entity.queueOrder,
                        createdAtMs = entity.createdAtMs
                    )
                }
            }
    }

    suspend fun listByStates(
        context: Context,
        states: List<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        if (states.isEmpty()) return emptyList()
        return database.downloadOperationDao()
            .findByStates(states)
            .mapNotNull { entity ->
                requestFromEntity(entity)?.let { request ->
                    StateEntry(request, entity.queueOrder, entity.createdAtMs)
                }
            }
    }

    suspend fun deleteByStateAndStableKeys(
        context: Context,
        state: String,
        stableKeys: List<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        if (stableKeys.isEmpty()) return
        database.downloadOperationDao()
            .deleteByStateAndStableKeys(state, stableKeys)
    }

    suspend fun deleteByState(
        context: Context,
        state: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        database.downloadOperationDao().deleteByState(state)
    }

    suspend fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        if (limit <= 0) return 0
        return database.downloadOperationDao().deleteTerminalBefore(
            states = TERMINAL_STATES,
            cutoffMs = cutoffMs,
            limit = limit
        )
    }

    suspend fun findOperationIdForSong(
        context: Context,
        songKey: String
    ): String? {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return null
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .findLatestOperationIdByStableKey(
                stableKey = normalizedSongKey,
                states = ACTIVE_OPERATION_STATES
            )
    }

    suspend fun isStopped(
        context: Context,
        operationId: String
    ): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .isUserStopped(operationId) == true
    }

    suspend fun stoppedSongKeys(context: Context): Set<String> {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .findUserStopped()
            .mapNotNull(::requestFromEntity)
            .map { request -> request.song.stableKey() }
            .toSet()
    }

    suspend fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val dao = database.downloadOperationDao()
        val current = dao.find(operationId) ?: return
        val nextState = resolveDownloadOperationState(current.state, state) ?: return
        if (nextState == current.state) return
        dao.updateState(
            operationId = operationId,
            state = nextState,
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        )
    }

    suspend fun state(context: Context, operationId: String): String? {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .find(operationId)
            ?.state
    }

    suspend fun requestCancel(context: Context, operationId: String): Boolean {
        return transitionStateAtomically(
            context = context,
            operationId = operationId,
            expectedStates = CANCELABLE_OPERATION_STATES,
            requestedState = "CANCEL_REQUESTED",
            errorCode = "USER_CANCELLED"
        )
    }

    suspend fun requestCancel(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase,
        updatedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        return database.downloadOperationDao().transitionState(
            operationId = operationId,
            expectedStates = CANCELABLE_OPERATION_STATES,
            state = "CANCEL_REQUESTED",
            updatedAtMs = updatedAtMs,
            errorCode = "USER_CANCELLED"
        ) > 0
    }

    suspend fun clearCancellation(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return database.downloadOperationDao().clearCancellation(
            operationId = operationId,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
    }

    suspend fun purgeCancelled(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            database.downloadOperationDao().deleteByStateAndStableKeys(
                state = state,
                stableKeys = keys
            )
        }
    }

    suspend fun purgeAllCancelled(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            database.downloadOperationDao().deleteByState(state)
        }
    }

    suspend fun markCoreCommitted(context: Context, operationId: String): Boolean {
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        if (dao.markCoreCommitted(
                operationId = operationId,
                expectedStates = CORE_COMMIT_SOURCE_STATES,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
        ) {
            return true
        }
        return dao.find(operationId)?.state in CORE_COMMITTED_STATES
    }

    suspend fun markCommitting(context: Context, operationId: String): Boolean {
        return transitionStateAtomically(
            context = context,
            operationId = operationId,
            expectedStates = COMMIT_SOURCE_STATES,
            requestedState = "COMMITTING",
            errorCode = null
        )
    }

    suspend fun markStopped(context: Context, operationId: String): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .requestUserStop(
                operationId = operationId,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
    }

    suspend fun clearUserStopForStableKeys(
        context: Context,
        stableKeys: Collection<String>
    ): Boolean {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return false
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .clearUserStopForStableKeys(
                stableKeys = keys,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
    }

    private suspend fun transitionStateAtomically(
        context: Context,
        operationId: String,
        expectedStates: List<String>,
        requestedState: String,
        errorCode: String?
    ): Boolean {
        if (expectedStates.isEmpty()) return false
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .transitionState(
                operationId = operationId,
                expectedStates = expectedStates,
                state = requestedState,
                updatedAtMs = System.currentTimeMillis(),
                errorCode = errorCode
            ) > 0
    }

    suspend fun delete(context: Context, operationId: String) {
        NeriUserDataDatabase.getInstance(context).downloadOperationDao().delete(operationId)
    }

    private fun requestToJson(request: DownloadExecutionRequest): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", JOURNAL_PAYLOAD_VERSION)
            put(
                "song",
                ManagedDownloadStorageJsonCodec.workingResumeMetadataToJson(
                    song = request.song,
                    operationId = request.operationId
                )
            )
            put("sourceStableKey", request.song.sourceStableKey)
            put("preserveStaging", request.preserveStaging)
            put("userInitiated", request.userInitiated)
            request.attemptId?.let { attemptId -> put("attemptId", attemptId) }
        }
    }

    private fun requestFromEntity(
        entity: DownloadOperationEntity
    ): DownloadExecutionRequest? {
        val root = runCatching { JSONObject(entity.sourceHintJson) }.getOrNull() ?: return null
        if (root.optInt("schemaVersion") != JOURNAL_PAYLOAD_VERSION) return null
        val songJson = root.optJSONObject("song") ?: return null
        val parsedSong = runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(
                songJson.toString()
            )
        }.getOrNull() ?: return null
        val song = parsedSong.copy(
            sourceStableKey = root.optString("sourceStableKey")
                .takeIf(String::isNotBlank)
        )
        if (song.stableKey() != entity.stableKey) return null
        return runCatching {
            DownloadExecutionRequest(
                operationId = entity.operationId,
                song = song,
                preserveStaging = root.optBoolean("preserveStaging", false),
                attemptId = root.optLong("attemptId", 0L).takeIf { it > 0L },
                userInitiated = if (root.has("userInitiated")) {
                    root.optBoolean("userInitiated", false)
                } else {
                    false
                }
            )
        }.getOrNull()
    }

    private const val JOURNAL_PAYLOAD_VERSION = 1
    private val TERMINAL_STATES = listOf("COMPLETED", "CANCELLED")
    private val ACTIVE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "CANCEL_REQUESTED",
        "STOPPED",
        "RETRYABLE"
    )
    private val CANCELABLE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "STOPPED",
        "RETRYABLE"
    )
    private val CORE_COMMIT_SOURCE_STATES = listOf(
        "COMMITTING"
    )
    private val CORE_COMMITTED_STATES = setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE",
        "COMPLETED"
    )
    private val COMMIT_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING"
    )
}

internal fun shouldRestartOperation(
    existingState: String?,
    requestedState: String,
    userInitiated: Boolean
): Boolean {
    if (requestedState !in setOf("PENDING_QUEUE", "QUEUED", "RUNNING")) return false
    return existingState == "RETRYABLE" ||
        (userInitiated && existingState in setOf("STOPPED", "CANCEL_REQUESTED", "CANCELLED"))
}
