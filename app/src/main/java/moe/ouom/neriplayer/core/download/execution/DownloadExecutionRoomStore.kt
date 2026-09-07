package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import org.json.JSONObject

internal const val WAITING_STORAGE_MUTATION_OPERATION_STATE = "WAITING_STORAGE_MUTATION"

internal object DownloadExecutionRoomStore {
    private const val OPERATION_QUERY_PAGE_SIZE = 64
    private const val CANCELLATION_QUERY_PAGE_SIZE = 256
    private const val PUMP_QUERY_MAX_ITEMS = 64

    internal data class StateEntry(
        val request: DownloadExecutionRequest,
        val queueOrder: Int,
        val createdAtMs: Long,
        val state: String = "",
        val updatedAtMs: Long = createdAtMs
    )

    internal data class OperationSnapshot(
        val request: DownloadExecutionRequest,
        val state: String
    )

    internal data class OperationRequestMetadata(
        val operationId: String,
        val stableKey: String,
        val state: String,
        val preserveStaging: Boolean,
        val requiresWifiNetwork: Boolean,
        val attemptId: Long?,
        val artifactLeaseId: String,
        val userInitiated: Boolean,
        val downloadAudioQuality: DownloadAudioQualitySelection?
    )

    internal data class CoreCommitJournalRecovery(
        val outcome: Outcome,
        val state: String?,
        val stopRequestedByUser: Boolean
    ) {
        internal enum class Outcome {
            COMMITTED,
            PREPARED,
            MISSING,
            BLOCKED
        }
    }

    internal data class CancellationSnapshot(
        val entries: List<StateEntry>,
        val operationIds: List<String>,
        val stableKeys: Set<String>,
        val requestedAtMs: Long
    )

    /** 取消入口固定的时间边界，避免替代 operation 被旧收敛误触碰 */
    internal data class CancellationBoundary(
        val stableKey: String,
        val createdAtMsAtMost: Long
    )

    internal data class OperationIdentity(
        val operationId: String,
        val stableKey: String,
        val createdAtMs: Long = 0L
    )

    internal data class ProgressCheckpoint(
        val bytesWritten: Long,
        val totalBytes: Long?
    )

    internal data class ProgressEntry(
        val request: DownloadExecutionRequest,
        val state: String,
        val bytesWritten: Long,
        val totalBytes: Long?,
        val stopRequestedByUser: Boolean,
        val updatedAtMs: Long,
        val queueOrder: Int = 0
    )

    suspend fun upsert(
        context: Context,
        request: DownloadExecutionRequest,
        state: String,
        queueOrder: Int = 0,
        createdAtMs: Long? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        database.withTransaction {
            val requestedCreatedAtMs = createdAtMs ?: System.currentTimeMillis()
            val song = request.song
            val dao = database.downloadOperationDao()
            val existingHeader = dao.findHeader(request.operationId)
            val payloadUpdatedAtMs = nextPayloadUpdatedAt(
                previousUpdatedAtMs = existingHeader?.updatedAtMs,
                requestedAtMs = requestedCreatedAtMs
            )
            val existing = existingHeader?.let { header ->
                readSourceHintJson(dao, header)?.let { sourceHintJson ->
                    header.toEntity(sourceHintJson)
                }
            }
            val restartForNewAttempt = shouldRestartOperation(
                existingState = existingHeader?.state,
                requestedState = state,
                userInitiated = request.userInitiated
            )
            val existingRequest = existing?.let(::requestFromEntity)
            val persistedRequest = when {
                restartForNewAttempt -> request.copy(
                    artifactLeaseId = UUID.randomUUID().toString()
                )
                existingRequest != null -> request.copy(
                    artifactLeaseId = existingRequest.artifactLeaseId
                )
                else -> request
            }
            dao.upsert(
                DownloadOperationEntity(
                    operationId = request.operationId,
                    stableKey = song.stableKey(),
                    libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context),
                    // 重新排队不能把已有 operation 的持久状态倒退
                    state = if (restartForNewAttempt) state else existingHeader?.state ?: state,
                    queueOrder = if (queueOrder == 0) {
                        existingHeader?.queueOrder ?: 0
                    } else {
                        queueOrder
                    },
                    sourceHintJson = requestToJson(persistedRequest).toString(),
                    stagingDirName = request.operationId,
                    bytesWritten = existingHeader?.bytesWritten ?: 0L,
                    totalBytes = existingHeader?.totalBytes,
                    resumeJson = readResumeJson(dao, existingHeader),
                    retryCount = existingHeader?.retryCount ?: 0,
                    nextRetryAtMs = existingHeader?.nextRetryAtMs
                        ?.takeIf { state == DownloadOperationState.RETRYABLE.wireName },
                    lastErrorCode = existingHeader?.lastErrorCode,
                    stopRequestedByUser = if (restartForNewAttempt) {
                        false
                    } else {
                        existingHeader?.stopRequestedByUser ?: false
                    },
                    createdAtMs = existingHeader?.createdAtMs ?: requestedCreatedAtMs,
                    updatedAtMs = payloadUpdatedAtMs,
                    hostProcessToken = existingHeader?.hostProcessToken,
                    hostAdmittedAtMs = existingHeader?.hostAdmittedAtMs
                )
            )
        }
    }

    // facade 保持既有调用面，读取、取消和状态查询分别由独立 store 承担
    suspend fun read(context: Context, operationId: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.read(context, operationId, database)
    suspend fun readOperationSnapshots(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readOperationSnapshots(context, operationIds, database)
    suspend fun readOperationHeaders(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readOperationHeaders(context, operationIds, database)
    suspend fun readOperationRequestMetadata(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readOperationRequestMetadata(context, operationIds, database)
    suspend fun promoteWaitingStorageMutations(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.promoteWaitingStorageMutations(context, operationIds, database)
    suspend fun readOperationIdentities(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readOperationIdentities(context, operationIds, database)
    suspend fun checkpointProgress(context: Context, operationId: String, stableKey: String, attemptId: Long?, bytesWritten: Long, totalBytes: Long?, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.checkpointProgress(context, operationId, stableKey, attemptId, bytesWritten, totalBytes, database)
    suspend fun readProgressCheckpoint(context: Context, operationId: String, stableKey: String, attemptId: Long?, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readProgressCheckpoint(context, operationId, stableKey, attemptId, database)
    suspend fun listByState(context: Context, state: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.listByState(context, state, database)
    suspend fun countByStates(context: Context, states: List<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.countByStates(context, states, database)
    suspend fun listSchedulableForPumpPage(context: Context, afterCursor: DownloadExecutionPumpCursor?, limit: Int, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context), nowMs: Long = System.currentTimeMillis()) =
        DownloadExecutionRoomReadStore.listSchedulableForPumpPage(context, afterCursor, limit, database, nowMs)
    suspend fun listByStates(context: Context, states: List<String>, excludeUserStoppedOperations: Boolean = false, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.listByStates(context, states, excludeUserStoppedOperations, database)
    suspend fun listByStatesAnyLibrary(context: Context, states: List<String>, excludeUserStoppedOperations: Boolean = false, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.listByStatesAnyLibrary(context, states, excludeUserStoppedOperations, database)
    fun hasAnyByStatesAnyLibrary(context: Context, states: List<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.hasAnyByStatesAnyLibrary(context, states, database)
    suspend fun rehomeActiveOperationsToCurrentLibrary(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.rehomeActiveOperationsToCurrentLibrary(context, database)
    suspend fun listProgressEntries(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.listProgressEntries(context, database)
    suspend fun listProgressEntriesAnyLibrary(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.listProgressEntriesAnyLibrary(context, database)
    suspend fun rehomeOperationToCurrentLibrary(context: Context, operationId: String, stableKey: String, states: List<String> = ACTIVE_OPERATION_STATES, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.rehomeOperationToCurrentLibrary(context, operationId, stableKey, states, database)
    suspend fun listCancellationCandidates(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.listCancellationCandidates(context, database)
    suspend fun listAllOperationIds(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.listAllOperationIds(context, database)
    suspend fun listAllOperationIdentities(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.listAllOperationIdentities(context, database)
    suspend fun listCancellationCandidatesAnyLibrary(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.listCancellationCandidatesAnyLibrary(context, database)
    suspend fun findUserCancellationOperationIdsForSong(context: Context, songKey: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context), createdAtMsAtMost: Long? = null) =
        DownloadExecutionRoomCancellationStore.findUserCancellationOperationIdsForSong(context, songKey, database, createdAtMsAtMost)
    suspend fun listCancellationIdentitiesAnyLibrary(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.listCancellationIdentitiesAnyLibrary(context, database)
    suspend fun listOperationIdentitiesForStableKeys(context: Context, stableKeys: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.listOperationIdentitiesForStableKeys(context, stableKeys, database)
    suspend fun requestCancelAll(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.requestCancelAll(context, database)
    suspend fun requestCancelAllFast(context: Context, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.requestCancelAllFast(context, database)
    suspend fun requestCancelForStableKeysFast(context: Context, stableKeys: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.requestCancelForStableKeysFast(context, stableKeys, database)
    suspend fun requestCancelOperations(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.requestCancelOperations(context, operationIds, database)
    suspend fun requestCancelOperationsFast(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.requestCancelOperationsFast(context, operationIds, database)
    suspend fun finalizeRequestedCancellations(context: Context, operationIds: Collection<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.finalizeRequestedCancellations(context, operationIds, database)
    suspend fun deleteByStateAndStableKeys(context: Context, state: String, stableKeys: List<String>, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.deleteByStateAndStableKeys(context, state, stableKeys, database)
    suspend fun deleteByState(context: Context, state: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.deleteByState(context, state, database)
    suspend fun pruneTerminalOperations(context: Context, cutoffMs: Long, limit: Int, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.pruneTerminalOperations(context, cutoffMs, limit, database)
    suspend fun findOperationIdForSong(context: Context, songKey: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context), states: List<String> = ACTIVE_OPERATION_STATES) =
        DownloadExecutionRoomCancellationStore.findOperationIdForSong(context, songKey, database, states)
    suspend fun findOperationIdsForSong(context: Context, songKey: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context), states: List<String> = CANCELLATION_CANDIDATE_OPERATION_STATES, createdAtMsAtMost: Long? = null) =
        DownloadExecutionRoomCancellationStore.findOperationIdsForSong(context, songKey, database, states, createdAtMsAtMost)
    suspend fun findReadableOperationIdForSong(context: Context, songKey: String, states: List<String>, excludeUserCancelledStops: Boolean = false, excludeUserStoppedOperations: Boolean = false, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.findReadableOperationIdForSong(context, songKey, states, excludeUserCancelledStops, excludeUserStoppedOperations, database)
    suspend fun findReadableOperationsBySongKeys(context: Context, songKeys: Collection<String>, states: List<String>, excludeUserCancelledStops: Boolean = false, excludeUserStoppedOperations: Boolean = false, excludedOperationIds: Collection<String> = emptySet(), database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.findReadableOperationsBySongKeys(context, songKeys, states, excludeUserCancelledStops, excludeUserStoppedOperations, excludedOperationIds, database)
    suspend fun rehydrateMalformedReusableOperation(context: Context, song: SongItem, userInitiated: Boolean, requiresWifiNetwork: Boolean, updatedAtMs: Long, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.rehydrateMalformedReusableOperation(context, song, userInitiated, requiresWifiNetwork, updatedAtMs, database)
    suspend fun rehydrateMalformedReusableOperations(context: Context, songs: Collection<SongItem>, userInitiated: Boolean, requiresWifiNetwork: Boolean, updatedAtMs: Long, downloadAudioQuality: DownloadAudioQualitySelection? = null, excludedOperationIds: Collection<String> = emptySet(), database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomCancellationStore.rehydrateMalformedReusableOperations(context, songs, userInitiated, requiresWifiNetwork, updatedAtMs, downloadAudioQuality, excludedOperationIds, database)
    suspend fun isStopped(context: Context, operationId: String) =
        DownloadExecutionRoomStatusStore.isStopped(context, operationId)
    suspend fun isUserCancellationRequested(context: Context, operationId: String) =
        DownloadExecutionRoomStatusStore.isUserCancellationRequested(context, operationId)
    suspend fun isExplicitResumePending(context: Context, operationId: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomStatusStore.isExplicitResumePending(context, operationId, database)
    suspend fun isExecutionOwned(context: Context, operationId: String, stableKey: String, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomStatusStore.isExecutionOwned(context, operationId, stableKey, database)
    suspend fun stoppedSongKeys(context: Context) =
        DownloadExecutionRoomStatusStore.stoppedSongKeys(context)

    suspend fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val current = dao.findHeader(operationId) ?: return@withTransaction false
            val nextState = resolveDownloadOperationState(current.state, state)
                ?: return@withTransaction false
            if (nextState == current.state) return@withTransaction !current.stopRequestedByUser
            if (nextState == DownloadOperationState.RETRYABLE.wireName) {
                val retryPlan = planDownloadRetry(
                    currentRetryCount = current.retryCount,
                    errorCode = errorCode,
                    nowMs = nowMs
                )
                return@withTransaction dao.transitionToRetryable(
                    operationId = operationId,
                    expectedStates = listOf(current.state),
                    expectedRetryCount = current.retryCount,
                    expectedUpdatedAtMs = current.updatedAtMs,
                    retryCount = retryPlan.retryCount,
                    nextRetryAtMs = retryPlan.nextRetryAtMs,
                    updatedAtMs = nowMs,
                    errorCode = errorCode
                ) > 0
            }
            dao.transitionState(
                operationId = operationId,
                expectedStates = listOf(current.state),
                state = nextState,
                updatedAtMs = nowMs,
                errorCode = errorCode
            ) > 0
        }
    }

    /**
     * 旧版本可能先把任务写成完成态，再留下 pending 音频
     * 只有调用方已经确认物理引用仍是 pending 时，才允许重新打开收尾入口
     */
    suspend fun reopenCorePublicationRecovery(
        context: Context,
        operationId: String,
        stableKey: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        if (operationId.isBlank()) return false
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction false
            if (
                header.stableKey != normalizedKey ||
                    header.stopRequestedByUser ||
                    header.state !in setOf("COMPLETED", "FINALIZED")
            ) {
                return@withTransaction false
            }
            dao.transitionState(
                operationId = operationId,
                expectedStates = listOf(header.state),
                state = "DEGRADED_COMPLETE",
                updatedAtMs = System.currentTimeMillis(),
                errorCode = errorCode
            ) > 0
        }
    }

    suspend fun markScheduleRejectedRetryable(
        context: Context,
        operationId: String,
        stableKey: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val current = dao.findHeader(operationId) ?: return@withTransaction false
            if (
                current.stableKey != normalizedKey ||
                    current.stopRequestedByUser ||
                    current.state !in REUSABLE_OPERATION_STATES
            ) {
                return@withTransaction false
            }
            if (current.state == DownloadOperationState.RETRYABLE.wireName) {
                return@withTransaction true
            }
            val retryPlan = planDownloadRetry(
                currentRetryCount = current.retryCount,
                errorCode = errorCode,
                nowMs = nowMs
            )
            dao.transitionToRetryable(
                operationId = operationId,
                expectedStates = listOf(current.state),
                expectedRetryCount = current.retryCount,
                expectedUpdatedAtMs = current.updatedAtMs,
                retryCount = retryPlan.retryCount,
                nextRetryAtMs = retryPlan.nextRetryAtMs,
                updatedAtMs = nowMs,
                errorCode = errorCode
            ) > 0
        }
    }

    suspend fun markWaitingForStorageMutation(
        context: Context,
        operationId: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        // 复用带 CAS 的统一状态机，不能让空间等待把已经提交的核心文件降级
        return updateState(
            context = context,
            operationId = operationId,
            state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
            errorCode = errorCode,
            database = database
        )
    }

    /**
     * 调用方确认存储变更和清空栅栏都已收敛后，才提升用户意图
     */
    suspend fun promoteWaitingStorageMutation(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        val libraryId = currentLibraryId(context)
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction false
            if (
                header.stableKey != normalizedKey ||
                    header.state != WAITING_STORAGE_MUTATION_OPERATION_STATE ||
                    header.stopRequestedByUser
            ) {
                return@withTransaction false
            }
            val decoded = readRequestFromHeader(dao, header)
            val request = decoded.request ?: run {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayloadInTransaction(database, header)
                }
                return@withTransaction false
            }
            if (request.song.stableKey() != normalizedKey) {
                invalidateMalformedPayloadInTransaction(database, header)
                return@withTransaction false
            }
            dao.promoteWaitingStorageMutation(
                operationId = operationId,
                libraryId = libraryId,
                stableKey = normalizedKey,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
        }
    }

    suspend fun markStagingPrepared(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction false
            if (header.stableKey != normalizedKey) return@withTransaction false
            val request = readRequestFromHeader(dao, header).request ?: return@withTransaction false
            if (request.song.stableKey() != normalizedKey) return@withTransaction false
            if (request.preserveStaging) return@withTransaction true
            dao.updateRequestPayload(
                operationId = operationId,
                stableKey = normalizedKey,
                sourceHintJson = requestToJson(
                    request.copy(preserveStaging = true)
                ).toString(),
                updatedAtMs = nextPayloadUpdatedAt(
                    previousUpdatedAtMs = header.updatedAtMs
                )
            ) > 0
        }
    }

    /** 为没有进度身份的旧记录持久化新生成的尝试编号 */
    suspend fun ensureAttemptId(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        if (operationId.isBlank() || attemptId <= 0L) return false
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction false
            if (header.stableKey != normalizedKey) return@withTransaction false
            val request = readRequestFromHeader(dao, header).request ?: return@withTransaction false
            if (request.song.stableKey() != normalizedKey) return@withTransaction false
            if (request.attemptId == attemptId) return@withTransaction true
            if (request.attemptId?.takeIf { it > 0L } != null) return@withTransaction false
            dao.updateRequestPayload(
                operationId = operationId,
                stableKey = normalizedKey,
                sourceHintJson = requestToJson(request.copy(attemptId = attemptId)).toString(),
                updatedAtMs = nextPayloadUpdatedAt(
                    previousUpdatedAtMs = header.updatedAtMs
                )
            ) > 0
        }
    }

    suspend fun state(context: Context, operationId: String): String? {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .findState(operationId)
    }

    suspend fun tryStart(
        context: Context,
        operationId: String,
        allowExistingRunning: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            var target = dao.findHeader(operationId) ?: return@withTransaction false
            if (target.libraryId != currentLibraryId(context)) {
                if (
                    target.stopRequestedByUser ||
                        target.state !in ACTIVE_OPERATION_STATES
                ) {
                    return@withTransaction false
                }
                val rebound = dao.rehomeOperationLibrary(
                    operationId = operationId,
                    stableKey = target.stableKey,
                    libraryId = currentLibraryId(context),
                    states = listOf(target.state),
                    updatedAtMs = System.currentTimeMillis()
                ) > 0
                if (!rebound) return@withTransaction false
                target = dao.findHeader(operationId) ?: return@withTransaction false
            }
            if (target.stopRequestedByUser) return@withTransaction false
            if (
                target.state == DownloadOperationState.RETRYABLE.wireName &&
                    !isRetryDeadlineReady(target.nextRetryAtMs, nowMs)
            ) {
                return@withTransaction false
            }
            val expectedStates = buildList {
                add("PENDING_QUEUE")
                add("QUEUED")
                add("RETRYABLE")
                if (allowExistingRunning) {
                    addAll(INTERRUPTED_DOWNLOAD_OPERATION_STATES)
                }
            }
            if (target.state !in expectedStates) return@withTransaction false
            if (hasOtherValidWaitingStorageMutation(database, target)) {
                return@withTransaction false
            }

            val contenders = dao.findAllHeadersByStableKey(
                libraryId = target.libraryId,
                stableKey = target.stableKey,
                states = EXECUTION_CONVERGENCE_STATES
            ).filterNot(DownloadOperationHeaderRow::stopRequestedByUser)
            val validContenders = buildList {
                for (header in contenders) {
                    val decoded = readRequestFromHeader(dao, header)
                    val request = decoded.request
                    if (request == null) {
                        if (decoded.payloadWasRead) {
                            invalidateMalformedPayloadInTransaction(database, header)
                        }
                    } else {
                        add(header to request)
                    }
                }
            }
            val leasedIds = validContenders
                .map { (entity, _) -> entity.libraryId }
                .distinct()
                .mapNotNull { libraryId ->
                    database.managedDownloadArtifactDao()
                        .find(libraryId, target.stableKey)
                        ?.leaseId
                }
                .toSet()
            val winner = validContenders.maxWithOrNull(
                compareBy<Pair<DownloadOperationHeaderRow, DownloadExecutionRequest>> { (_, request) ->
                    request.artifactLeaseId in leasedIds
                }.thenBy { (header, _) -> executionConvergencePriority(header.state) }
                    .thenBy { (header, _) -> header.updatedAtMs }
                    .thenBy { (header, _) -> header.createdAtMs }
                    .thenBy { (header, _) -> header.operationId }
            ) ?: return@withTransaction false

            if (winner.first.operationId != operationId) {
                // 另一个宿主可能正在收尾。此时不能把刚排队的替代请求
                // 提前标成 INVALID，否则取消竞态会永久吞掉新任务
                return@withTransaction false
            }
            // 只有当前 operation 已赢得稳定键仲裁时，才清理尚未开始的重复行
            validContenders
                .filter { (header, _) ->
                    header.operationId != winner.first.operationId &&
                        header.state in REUSABLE_OPERATION_STATES
                }
                .forEach { (header, _) ->
                    dao.transitionState(
                        operationId = header.operationId,
                        expectedStates = listOf(header.state),
                        state = "INVALID",
                        updatedAtMs = System.currentTimeMillis(),
                        errorCode = "DUPLICATE_STABLE_KEY_OPERATION"
                    )
                }
            if (target.state in DURABLE_CORE_EXECUTION_STATES) {
                return@withTransaction true
            }
            dao.transitionState(
                operationId = operationId,
                expectedStates = expectedStates,
                state = "RUNNING",
                updatedAtMs = System.currentTimeMillis(),
                errorCode = null
            ) > 0
        }
    }

    suspend fun requestCancel(context: Context, operationId: String): Boolean {
        val database = NeriUserDataDatabase.getInstance(context)
        val dao = database.downloadOperationDao()
        if (
            dao.transitionState(
                operationId = operationId,
                expectedStates = CANCELABLE_OPERATION_STATES,
                state = "CANCEL_REQUESTED",
                updatedAtMs = System.currentTimeMillis(),
                errorCode = "USER_CANCELLED"
            ) > 0
        ) {
            return true
        }
        if (
            dao.requestStoppedCancellation(
                operationId = operationId,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
        ) {
            return true
        }
        return dao.requestCommitBoundaryStop(
            operationId = operationId,
            expectedStates = COMMIT_BOUNDARY_CANCEL_STATES,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
    }

    suspend fun requestCancel(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase,
        updatedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        val dao = database.downloadOperationDao()
        if (
            dao.transitionState(
                operationId = operationId,
                expectedStates = CANCELABLE_OPERATION_STATES,
                state = "CANCEL_REQUESTED",
                updatedAtMs = updatedAtMs,
                errorCode = "USER_CANCELLED"
            ) > 0
        ) {
            return true
        }
        if (
            dao.requestStoppedCancellation(
                operationId = operationId,
                updatedAtMs = updatedAtMs
            ) > 0
        ) {
            return true
        }
        return dao.requestCommitBoundaryStop(
            operationId = operationId,
            expectedStates = COMMIT_BOUNDARY_CANCEL_STATES,
            updatedAtMs = updatedAtMs
        ) > 0
    }

    /** 在新请求落库后，按取消时刻原子标记仍残留的旧 operation */
    suspend fun requestCancelForStableKeysBefore(
        context: Context,
        boundaries: Collection<CancellationBoundary>,
        excludedOperationIds: Collection<String> = emptySet(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        val boundaryByKey = boundaries.asSequence()
            .map { boundary ->
                boundary.stableKey.trim() to boundary.createdAtMsAtMost.coerceAtLeast(0L)
            }
            .filter { (stableKey, _) -> stableKey.isNotBlank() }
            .groupBy({ (stableKey, _) -> stableKey }, { (_, cutoff) -> cutoff })
            .mapValues { (_, cutoffs) -> cutoffs.maxOrNull() ?: 0L }
        if (boundaryByKey.isEmpty()) return emptySet()
        val excludedIds = excludedOperationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val headers = boundaryByKey.keys
                .toList()
                .chunked(SQLITE_IN_QUERY_CHUNK_SIZE)
                .flatMap { stableKeyChunk ->
                    dao.findAllHeadersByStableKeysAnyLibrary(
                        stableKeys = stableKeyChunk,
                        states = CANCELLATION_CANDIDATE_OPERATION_STATES
                    )
                }
            val eligibleHeaders = headers.filter { header ->
                header.operationId !in excludedIds &&
                    header.createdAtMs <= (boundaryByKey[header.stableKey] ?: -1L)
            }
            val cancelIds = eligibleHeaders
                .filter { header ->
                    header.state in setOf(
                        "PENDING_QUEUE",
                        "QUEUED",
                        WAITING_STORAGE_MUTATION_OPERATION_STATE,
                        "RUNNING",
                        "STOPPED",
                        "RETRYABLE"
                    ) && !header.stopRequestedByUser
                }
                .map(DownloadOperationHeaderRow::operationId)
                .distinct()
            val commitBoundaryIds = eligibleHeaders
                .filter { header ->
                    header.state in setOf(
                        "COMMITTING",
                        "CORE_COMMITTED",
                        "ASSETS_ENRICHING",
                        "DEGRADED_COMPLETE"
                    ) && !header.stopRequestedByUser
                }
                .map(DownloadOperationHeaderRow::operationId)
                .distinct()
            val updatedAtMs = System.currentTimeMillis()
            cancelIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.requestCancellations(chunk, updatedAtMs)
            }
            commitBoundaryIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.requestCommitBoundaryCancellations(chunk, updatedAtMs)
            }
            (cancelIds + commitBoundaryIds).toSet()
        }
    }

    suspend fun purgeCancelled(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            deleteByStateAndStableKeys(
                context = context,
                state = state,
                stableKeys = keys,
                database = database
            )
        }
    }

    /** 按固定 operation 身份清理取消终态，避免删除替代请求的取消凭据 */
    suspend fun purgeCancelledOperationIds(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        val eligibleIds = ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
            database.downloadOperationDao()
                .findAllHeadersByOperationIds(chunk)
                .filter { header -> header.state in setOf("CANCEL_REQUESTED", "CANCELLED") }
                .map(DownloadOperationHeaderRow::operationId)
        }
        return deleteOperationsWithAdmissions(database, eligibleIds)
    }

    suspend fun purgeAllCancelled(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            deleteByState(context, state, database)
        }
    }

    suspend fun purgeClearedOperations(
        context: Context,
        operationIds: Collection<String>,
        cancelledAtMs: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        return ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                val eligibleIds = dao.findClearedOperationIds(
                    operationIds = chunk,
                    cancelledAtMs = cancelledAtMs
                )
                if (eligibleIds.isEmpty()) {
                    0
                } else {
                    dao.deleteHostAdmissions(eligibleIds)
                    dao.deleteOperations(eligibleIds)
                }
            }
        }
    }

    /**
     * 只有宿主和提交工作都完全停止后，才删除清空快照
     */
    suspend fun purgeFullyClearedOperations(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        return deleteOperationsWithAdmissions(database, ids)
    }

    /**
     * 为当前进程预留一个系统宿主交接槽位，不把所有持久排队记录都当成活动任务
     */
    suspend fun tryAcquireHostAdmission(
        context: Context,
        operationId: String,
        capacity: Int,
        nowMs: Long = System.currentTimeMillis(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        if (capacity <= 0 || operationId.isBlank()) return false
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            // Room 只在主进程使用，其他令牌留下的记录已经无法继续拥有系统宿主
            dao.deleteHostAdmissionsFromOtherProcesses(HOST_ADMISSION_PROCESS_TOKEN)
            dao.deleteExpiredHostAdmissions(
                processToken = HOST_ADMISSION_PROCESS_TOKEN,
                cutoffMs = (nowMs - HOST_ADMISSION_HANDOFF_LEASE_MS).coerceAtLeast(0L),
                states = HOST_ADMISSION_EXPIRABLE_STATES
            )
            val operation = dao.findHeader(operationId) ?: return@withTransaction false
            if (operation.hostProcessToken == HOST_ADMISSION_PROCESS_TOKEN) {
                return@withTransaction true
            }
            if (
                operation.stopRequestedByUser ||
                    operation.state !in HOST_ADMISSION_HANDOFF_STATES
            ) {
                return@withTransaction false
            }
            if (hasOtherValidWaitingStorageMutation(database, operation)) {
                return@withTransaction false
            }
            if (dao.countHostAdmissions(HOST_ADMISSION_PROCESS_TOKEN) >= capacity) {
                return@withTransaction false
            }
            dao.setHostAdmission(
                operationId = operationId,
                processToken = HOST_ADMISSION_PROCESS_TOKEN,
                admittedAtMs = nowMs
            ) > 0
        }
    }

    suspend fun releaseHostAdmission(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        if (operationId.isBlank()) return
        database.downloadOperationDao().deleteHostAdmission(operationId)
    }

    suspend fun releaseHostAdmissions(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return
        ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
            database.downloadOperationDao().deleteHostAdmissions(chunk)
        }
    }

    suspend fun currentHostAdmissionCount(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            dao.deleteHostAdmissionsFromOtherProcesses(HOST_ADMISSION_PROCESS_TOKEN)
            dao.deleteExpiredHostAdmissions(
                processToken = HOST_ADMISSION_PROCESS_TOKEN,
                cutoffMs = (nowMs - HOST_ADMISSION_HANDOFF_LEASE_MS).coerceAtLeast(0L),
                states = HOST_ADMISSION_EXPIRABLE_STATES
            )
            dao.countHostAdmissions(HOST_ADMISSION_PROCESS_TOKEN)
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
        return dao.findState(operationId) in CORE_COMMITTED_STATES
    }

    /** 失败重试时重新确认提交边界，不把取消或停止的 operation 重新变成可执行任务 */
    suspend fun reconcileCoreCommitJournal(
        context: Context,
        operationId: String,
        stableKey: String? = null,
        expectedAttemptId: Long? = null,
        coreMetadataDurable: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): CoreCommitJournalRecovery {
        val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank)
            ?: return CoreCommitJournalRecovery(
                outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                state = null,
                stopRequestedByUser = false
            )
        val normalizedStableKey = stableKey?.trim()?.takeIf(String::isNotBlank)
        val normalizedAttemptId = expectedAttemptId?.takeIf { it > 0L }
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            var current = dao.findHeader(normalizedOperationId)
                ?: return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.MISSING,
                    state = null,
                    stopRequestedByUser = false
                )
            repeat(2) {
                if (
                    normalizedStableKey != null &&
                        current.stableKey != normalizedStableKey
                ) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                val decoded = readRequestFromHeader(dao, current)
                val request = decoded.request ?: run {
                    if (decoded.payloadWasRead) {
                        invalidateMalformedPayloadInTransaction(database, current)
                    }
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                val requestStableKey = request.song.stableKey()
                if (
                    requestStableKey != current.stableKey ||
                        normalizedStableKey != null && requestStableKey != normalizedStableKey ||
                        normalizedAttemptId != null &&
                            request.attemptId != null &&
                            request.attemptId != normalizedAttemptId
                ) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                if (current.state in CORE_COMMITTED_STATES) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.COMMITTED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                if (current.state in CORE_COMMIT_BLOCKED_STATES) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                if (
                    current.stopRequestedByUser &&
                        current.state != "COMMITTING"
                ) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                        state = current.state,
                        stopRequestedByUser = true
                    )
                }
                if (current.state == "COMMITTING") {
                    if (!coreMetadataDurable) {
                        return@withTransaction CoreCommitJournalRecovery(
                            outcome = CoreCommitJournalRecovery.Outcome.PREPARED,
                            state = current.state,
                            stopRequestedByUser = current.stopRequestedByUser
                        )
                    }
                    if (
                        dao.markCoreCommitted(
                            operationId = normalizedOperationId,
                            expectedStates = CORE_COMMIT_SOURCE_STATES,
                            updatedAtMs = System.currentTimeMillis()
                        ) > 0
                    ) {
                        return@withTransaction CoreCommitJournalRecovery(
                            outcome = CoreCommitJournalRecovery.Outcome.COMMITTED,
                            state = "CORE_COMMITTED",
                            stopRequestedByUser = current.stopRequestedByUser
                        )
                    }
                } else if (current.state in CORE_COMMIT_RECOVERY_SOURCE_STATES) {
                    if (
                        dao.transitionState(
                            operationId = normalizedOperationId,
                            expectedStates = listOf(current.state),
                            state = "COMMITTING",
                            updatedAtMs = System.currentTimeMillis(),
                            errorCode = "CORE_COMMIT_RECOVERY"
                        ) > 0
                    ) {
                        if (!coreMetadataDurable) {
                            return@withTransaction CoreCommitJournalRecovery(
                                outcome = CoreCommitJournalRecovery.Outcome.PREPARED,
                                state = "COMMITTING",
                                stopRequestedByUser = current.stopRequestedByUser
                            )
                        }
                        if (
                            dao.markCoreCommitted(
                                operationId = normalizedOperationId,
                                expectedStates = CORE_COMMIT_SOURCE_STATES,
                                updatedAtMs = System.currentTimeMillis()
                            ) > 0
                        ) {
                            return@withTransaction CoreCommitJournalRecovery(
                                outcome = CoreCommitJournalRecovery.Outcome.COMMITTED,
                                state = "CORE_COMMITTED",
                                stopRequestedByUser = current.stopRequestedByUser
                            )
                        }
                    }
                } else {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                current = dao.findHeader(normalizedOperationId)
                    ?: return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.MISSING,
                        state = null,
                        stopRequestedByUser = false
                    )
            }
            CoreCommitJournalRecovery(
                outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                state = current.state,
                stopRequestedByUser = current.stopRequestedByUser
            )
        }
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

    /** 进程退出后释放旧宿主租约并恢复用户发起的下载 */
    suspend fun markUserRequestedProcessExitOperations(
        context: Context,
        entries: Collection<StateEntry>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        val requests = entries
            .filter { entry -> entry.request.userInitiated }
            .distinctBy { entry -> entry.request.operationId }
        if (requests.isEmpty()) return emptySet()
        val nowMs = System.currentTimeMillis()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            requests.mapNotNullTo(linkedSetOf()) { entry ->
                val operationId = entry.request.operationId
                val stableKey = entry.request.song.stableKey()
                val header = dao.findHeader(operationId)
                    ?.takeIf { it.stableKey == stableKey }
                    ?.takeUnless { it.stopRequestedByUser }
                    ?: return@mapNotNullTo null
                val requeueState = resolveProcessExitRecoveryState(header.state)
                val requeued = requeueState != null && dao.requeueAfterProcessExit(
                    operationId = operationId,
                    stableKey = stableKey,
                    updatedAtMs = nowMs
                ) > 0
                val released = if (requeued) {
                    true
                } else if (header.state in IN_FLIGHT_OPERATION_STATES) {
                    dao.releaseAfterProcessExit(
                        operationId = operationId,
                        stableKey = stableKey,
                        updatedAtMs = nowMs
                    ) > 0
                } else {
                    false
                }
                stableKey.takeIf { released }
            }
        }
    }

    suspend fun clearUserStopForStableKeys(
        context: Context,
        stableKeys: Collection<String>
    ): Boolean {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return false
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        val updatedAtMs = System.currentTimeMillis()
        return keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            dao.clearUserStopForStableKeysAnyLibrary(
                stableKeys = chunk,
                updatedAtMs = updatedAtMs
            )
        } > 0
    }

    suspend fun clearUserStopForFreshStart(
        context: Context,
        stableKeys: Collection<String>
    ): Boolean {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return false
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        val updatedAtMs = System.currentTimeMillis()
        return keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            dao.clearUserStopForFreshStartAnyLibrary(
                stableKeys = chunk,
                updatedAtMs = updatedAtMs
            )
        } > 0
    }

    suspend fun prepareExplicitResume(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.downloadOperationDao().prepareExplicitResume(
            operationId = operationId,
            stableKey = normalizedKey,
            expectedStates = EXPLICIT_RESUME_SOURCE_STATES,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
    }

    suspend fun prepareExplicitResumesForStableKeys(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val normalizedKeys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
        if (normalizedKeys.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            dao.findUserStoppedHeaders()
                .filter { header -> header.stableKey in normalizedKeys }
                .sumOf { header ->
                    dao.prepareExplicitResume(
                        operationId = header.operationId,
                        stableKey = header.stableKey,
                        expectedStates = EXPLICIT_RESUME_SOURCE_STATES,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
        }
    }

    suspend fun restoreExplicitStop(
        context: Context,
        operationId: String,
        stableKey: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.downloadOperationDao().restoreExplicitStop(
            operationId = operationId,
            stableKey = normalizedKey,
            expectedStates = EXPLICIT_STOP_RESTORE_SOURCE_STATES,
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
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
        val database = NeriUserDataDatabase.getInstance(context)
        database.withTransaction {
            database.downloadOperationDao().deleteHostAdmission(operationId)
            database.downloadOperationDao().delete(operationId)
        }
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
            // 持久化实体真正使用的身份，不能只保存可选的来源元数据
            put("sourceStableKey", request.song.stableKey())
            put("preserveStaging", request.preserveStaging)
            put("requiresWifiNetwork", request.requiresWifiNetwork)
            put("userInitiated", request.userInitiated)
            request.attemptId?.let { attemptId -> put("attemptId", attemptId) }
            put("artifactLeaseId", request.artifactLeaseId)
            request.downloadAudioQuality?.let { quality ->
                put(
                    "downloadAudioQuality",
                    JSONObject().apply {
                        put("neteaseQuality", quality.neteaseQuality)
                        put("youtubeQuality", quality.youtubeQuality)
                        put("biliQuality", quality.biliQuality)
                    }
                )
            }
        }
    }

    private fun requestFromEntity(
        entity: DownloadOperationEntity
    ): DownloadExecutionRequest? {
        val root = runCatching { JSONObject(entity.sourceHintJson) }
            .onFailure { error ->
                logDecodeFailure(entity, "invalid_json", error)
            }
            .getOrNull() ?: return null
        if (root.optInt("schemaVersion") != JOURNAL_PAYLOAD_VERSION) {
            logDecodeFailure(entity, "schema_version=${root.optInt("schemaVersion")}")
            return null
        }
        val songJson = root.optJSONObject("song") ?: run {
            logDecodeFailure(entity, "missing_song")
            return null
        }
        val parsedSong = runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(
                songJson.toString()
            )
        }.onFailure { error ->
            logDecodeFailure(entity, "song_decode", error)
        }.getOrNull() ?: run {
            logDecodeFailure(entity, "song_decode_null")
            return null
        }
        val song = parsedSong.copy(
            sourceStableKey = root.optString("sourceStableKey")
                .takeIf { root.has("sourceStableKey") && !root.isNull("sourceStableKey") }
                ?.takeIf(String::isNotBlank)
        )
        if (song.stableKey() != entity.stableKey) {
            logDecodeFailure(
                entity,
                "stable_key_mismatch parsed=${song.stableKey()} entity=${entity.stableKey}"
            )
            return null
        }
        return runCatching {
            DownloadExecutionRequest(
                operationId = entity.operationId,
                song = song,
                preserveStaging = root.optBoolean("preserveStaging", false),
                requiresWifiNetwork = if (root.has("requiresWifiNetwork")) {
                    root.optBoolean("requiresWifiNetwork", true)
                } else {
                    true
                },
                attemptId = root.optLong("attemptId", 0L).takeIf { it > 0L },
                artifactLeaseId = root.optString("artifactLeaseId")
                    .takeIf(String::isNotBlank)
                    ?: entity.operationId,
                userInitiated = if (root.has("userInitiated")) {
                    root.optBoolean("userInitiated", false)
                } else {
                    false
                },
                downloadAudioQuality = root.optJSONObject("downloadAudioQuality")?.let { quality ->
                    DownloadAudioQualitySelection.normalized(
                        neteaseQuality = quality.optString("neteaseQuality"),
                        youtubeQuality = quality.optString("youtubeQuality"),
                        biliQuality = quality.optString("biliQuality")
                    )
                }
            )
        }.onFailure { error ->
            logDecodeFailure(entity, "request_decode", error)
        }.getOrNull()
    }

    internal data class HeaderRequestRead(
        val request: DownloadExecutionRequest?,
        val payloadWasRead: Boolean
    )

    /** source_hint_json 可能包含完整歌词，必须分段读取以避开 CursorWindow 上限 */
    private suspend fun readRequestFromHeader(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow
    ): HeaderRequestRead {
        val sourceHintJson = readSourceHintJson(dao, header)
            ?: return HeaderRequestRead(request = null, payloadWasRead = false)
        return HeaderRequestRead(
            request = requestFromEntity(header.toEntity(sourceHintJson)),
            payloadWasRead = true
        )
    }

    private suspend fun readSourceHintJson(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow
    ): String? {
        val payloadLength = dao.findSourceHintJsonLength(
            operationId = header.operationId,
            updatedAtMs = header.updatedAtMs
        ) ?: return null
        if (payloadLength < 0) return null
        if (payloadLength == 0) return ""
        val payload = StringBuilder(payloadLength)
        var startOffset = 1
        var readCharacterCount = 0
        while (readCharacterCount < payloadLength) {
            val chunk = dao.findSourceHintJsonChunk(
                operationId = header.operationId,
                startOffset = startOffset,
                chunkLength = SOURCE_HINT_JSON_CHUNK_LENGTH,
                updatedAtMs = header.updatedAtMs
            ) ?: return null
            if (chunk.isEmpty()) return null
            payload.append(chunk)
            readCharacterCount += chunk.codePointCount(0, chunk.length)
            if (readCharacterCount > payloadLength) return null
            startOffset = readCharacterCount + 1
        }
        return payload.toString()
    }

    private suspend fun readResumeJson(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow?
    ): String? {
        if (header == null) return null
        val payloadLength = dao.findResumeJsonLength(
            operationId = header.operationId,
            updatedAtMs = header.updatedAtMs
        ) ?: return null
        if (payloadLength < 0) return null
        if (payloadLength == 0) return ""
        val payload = StringBuilder(payloadLength)
        var startOffset = 1
        var readCharacterCount = 0
        while (readCharacterCount < payloadLength) {
            val chunk = dao.findResumeJsonChunk(
                operationId = header.operationId,
                startOffset = startOffset,
                chunkLength = SOURCE_HINT_JSON_CHUNK_LENGTH,
                updatedAtMs = header.updatedAtMs
            ) ?: return null
            if (chunk.isEmpty()) return null
            payload.append(chunk)
            readCharacterCount += chunk.codePointCount(0, chunk.length)
            if (readCharacterCount > payloadLength) return null
            startOffset = readCharacterCount + 1
        }
        return payload.toString()
    }

    private fun DownloadOperationHeaderRow.toEntity(
        sourceHintJson: String
    ): DownloadOperationEntity {
        return DownloadOperationEntity(
            operationId = operationId,
            stableKey = stableKey,
            libraryId = libraryId,
            state = state,
            queueOrder = queueOrder,
            sourceHintJson = sourceHintJson,
            stagingDirName = stagingDirName,
            bytesWritten = bytesWritten,
            totalBytes = totalBytes,
            resumeJson = null,
            retryCount = retryCount,
            nextRetryAtMs = nextRetryAtMs,
            lastErrorCode = lastErrorCode,
            stopRequestedByUser = stopRequestedByUser,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            hostProcessToken = hostProcessToken,
            hostAdmittedAtMs = hostAdmittedAtMs
        )
    }

    private fun logDecodeFailure(
        entity: DownloadOperationEntity,
        reason: String,
        error: Throwable? = null
    ) {
        moe.ouom.neriplayer.core.logging.NPLogger.w(
            "DownloadExecutionRoomStore",
            "operation payload decode failed: " +
                "operationId=${entity.operationId}, state=${entity.state}, " +
                "entityStableKey=${entity.stableKey}, reason=$reason",
            error
        )
    }

    private suspend fun invalidateMalformedPayload(
        database: NeriUserDataDatabase,
        header: DownloadOperationHeaderRow
    ) {
        database.withTransaction {
            invalidateMalformedPayloadInTransaction(database, header)
        }
    }

    private suspend fun invalidateMalformedPayloadInTransaction(
        database: NeriUserDataDatabase,
        header: DownloadOperationHeaderRow
    ) {
        val dao = database.downloadOperationDao()
        val updated = dao.invalidateMalformedPayloadAtVersion(
            operationId = header.operationId,
            expectedState = header.state,
            expectedUpdatedAtMs = header.updatedAtMs,
            invalidatedAtMs = System.currentTimeMillis()
        )
        if (updated > 0) {
            dao.deleteHostAdmission(header.operationId)
        }
    }

    private suspend fun hasOtherValidWaitingStorageMutation(
        database: NeriUserDataDatabase,
        target: DownloadOperationHeaderRow
    ): Boolean {
        return hasOtherValidWaitingStorageMutation(
            database = database,
            targetOperationId = target.operationId,
            targetLibraryId = target.libraryId,
            targetStableKey = target.stableKey
        )
    }

    private suspend fun hasOtherValidWaitingStorageMutation(
        database: NeriUserDataDatabase,
        targetOperationId: String,
        targetLibraryId: String,
        targetStableKey: String
    ): Boolean {
        val dao = database.downloadOperationDao()
        val candidates = dao.findAllHeadersByStableKey(
            libraryId = targetLibraryId,
            stableKey = targetStableKey,
            states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
        )
        for (header in candidates) {
            if (
                header.operationId == targetOperationId ||
                    header.stopRequestedByUser
            ) {
                continue
            }
            val decoded = readRequestFromHeader(dao, header)
            val request = decoded.request
            if (request == null || request.song.stableKey() != header.stableKey) {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayloadInTransaction(database, header)
                }
                continue
            }
            return true
        }
        return false
    }

    private fun requiresDirectCancellation(header: DownloadOperationHeaderRow): Boolean {
        return requiresDirectCancellation(
            state = header.state,
            stopRequestedByUser = header.stopRequestedByUser
        )
    }

    private fun requiresDirectCancellation(
        state: String,
        stopRequestedByUser: Boolean
    ): Boolean {
        return when (state) {
            "PENDING_QUEUE",
            "QUEUED",
            WAITING_STORAGE_MUTATION_OPERATION_STATE,
            "RUNNING",
            "RETRYABLE" -> !stopRequestedByUser

            "STOPPED" -> true
            else -> false
        }
    }

    private fun requiresCommitBoundaryCancellation(header: DownloadOperationHeaderRow): Boolean {
        return requiresCommitBoundaryCancellation(
            state = header.state,
            stopRequestedByUser = header.stopRequestedByUser
        )
    }

    private fun requiresCommitBoundaryCancellation(
        state: String,
        stopRequestedByUser: Boolean
    ): Boolean {
        return state in COMMIT_BOUNDARY_CANCEL_STATES && !stopRequestedByUser
    }

    private suspend fun deleteOperationsWithAdmissions(
        database: NeriUserDataDatabase,
        operationIds: Collection<String>
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        return ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                dao.deleteHostAdmissions(chunk)
                dao.deleteOperations(chunk)
            }
        }
    }

    private fun currentLibraryId(context: Context): String {
        return ManagedDownloadStorage.currentSnapshotCacheKey(context.applicationContext)
    }

    private fun nextPayloadUpdatedAt(
        previousUpdatedAtMs: Long?,
        requestedAtMs: Long = System.currentTimeMillis()
    ): Long {
        val previous = previousUpdatedAtMs ?: return requestedAtMs
        if (previous == Long.MAX_VALUE) return previous
        return maxOf(requestedAtMs, previous + 1L)
    }

    private const val JOURNAL_PAYLOAD_VERSION = 1
    private const val SOURCE_HINT_JSON_CHUNK_LENGTH = 64 * 1024
    private const val SQLITE_IN_QUERY_CHUNK_SIZE = 900
    internal const val HOST_ADMISSION_HANDOFF_LEASE_MS = 30_000L
    private val HOST_ADMISSION_PROCESS_TOKEN = UUID.randomUUID().toString()
    private val TERMINAL_STATES = listOf("COMPLETED", "CANCELLED", "INVALID")
    private val ACTIVE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "CANCEL_REQUESTED",
        "STOPPED",
        "RETRYABLE",
        "DEGRADED_COMPLETE"
    )
    internal val REUSABLE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE"
    )
    /** 共享泵必须覆盖核心写入后的收尾状态，避免延迟调度丢失后永久悬挂 */
    internal val PUMP_OPERATION_STATES =
        REUSABLE_OPERATION_STATES + DownloadOperationStateTransitions.resumableCoreWireNames.toList()
    internal val IN_FLIGHT_OPERATION_STATES = listOf(
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    /** 旧宿主消失后可交给新进程接管的状态，进程死亡可能让持久日志领先于内存调度器 */
    internal val HOST_ADMISSION_HANDOFF_STATES = REUSABLE_OPERATION_STATES +
        IN_FLIGHT_OPERATION_STATES
    /** 只有尚未进入执行的任务允许依靠时间租约回收，避免长下载被误释放 */
    internal val HOST_ADMISSION_EXPIRABLE_STATES = REUSABLE_OPERATION_STATES
    internal val PROGRESS_CHECKPOINT_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "RETRYABLE",
        "STOPPED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE
    )
    private val CANCELABLE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "STOPPED",
        "RETRYABLE"
    )
    private val CANCELLATION_CANDIDATE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "CANCEL_REQUESTED",
        "STOPPED",
        "RETRYABLE",
        "DEGRADED_COMPLETE"
    )
    private val COMMIT_BOUNDARY_CANCEL_STATES = listOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    private val EXPLICIT_RESUME_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "COMMITTING",
        "RETRYABLE",
        "STOPPED",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    private val EXPLICIT_STOP_RESTORE_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
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
    private val CORE_COMMIT_BLOCKED_STATES = setOf(
        "CANCEL_REQUESTED",
        "CANCELLED",
        "STOPPED"
    )
    private val CORE_COMMIT_RECOVERY_SOURCE_STATES = setOf(
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RETRYABLE"
    )
    private val COMMIT_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING"
    )

    private val ROOT_REHOME_OPERATION_STATES = REUSABLE_OPERATION_STATES +
        IN_FLIGHT_OPERATION_STATES +
        listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)

    private val EXECUTION_CONVERGENCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE",
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )

    private val DURABLE_CORE_EXECUTION_STATES = setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )

    /**
     * 供拆分后的 Room 读写边界使用的窄适配层
     *
     * 保留 payload 解码和状态常量的单一所有权，避免 facade 拆分后出现两套规则
     */
    internal object Access {
        internal val JOURNAL_PAYLOAD_VERSION: Int
            get() = DownloadExecutionRoomStore.JOURNAL_PAYLOAD_VERSION
        internal val ACTIVE_OPERATION_STATES: List<String>
            get() = DownloadExecutionRoomStore.ACTIVE_OPERATION_STATES
        internal val OPERATION_QUERY_PAGE_SIZE: Int
            get() = DownloadExecutionRoomStore.OPERATION_QUERY_PAGE_SIZE
        internal val CANCELLATION_QUERY_PAGE_SIZE: Int
            get() = DownloadExecutionRoomStore.CANCELLATION_QUERY_PAGE_SIZE
        internal val PUMP_QUERY_MAX_ITEMS: Int
            get() = DownloadExecutionRoomStore.PUMP_QUERY_MAX_ITEMS
        internal val SQLITE_IN_QUERY_CHUNK_SIZE: Int
            get() = DownloadExecutionRoomStore.SQLITE_IN_QUERY_CHUNK_SIZE
        internal val TERMINAL_STATES: List<String>
            get() = DownloadExecutionRoomStore.TERMINAL_STATES
        internal val CANCELLATION_CANDIDATE_OPERATION_STATES: List<String>
            get() = DownloadExecutionRoomStore.CANCELLATION_CANDIDATE_OPERATION_STATES
        internal val ROOT_REHOME_OPERATION_STATES: List<String>
            get() = DownloadExecutionRoomStore.ROOT_REHOME_OPERATION_STATES
        internal val PROGRESS_CHECKPOINT_OPERATION_STATES: List<String>
            get() = DownloadExecutionRoomStore.PROGRESS_CHECKPOINT_OPERATION_STATES
        internal val REUSABLE_OPERATION_STATES: List<String>
            get() = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        internal val PUMP_OPERATION_STATES: List<String>
            get() = DownloadExecutionRoomStore.PUMP_OPERATION_STATES

        internal fun requestToJson(request: DownloadExecutionRequest): JSONObject {
            return DownloadExecutionRoomStore.requestToJson(request)
        }

        internal fun nextPayloadUpdatedAt(
            previousUpdatedAtMs: Long?,
            requestedAtMs: Long = System.currentTimeMillis()
        ): Long {
            return DownloadExecutionRoomStore.nextPayloadUpdatedAt(
                previousUpdatedAtMs,
                requestedAtMs
            )
        }

        internal suspend fun readRequestFromHeader(
            dao: DownloadOperationDao,
            header: DownloadOperationHeaderRow
        ): HeaderRequestRead {
            return DownloadExecutionRoomStore.readRequestFromHeader(dao, header)
        }

        internal suspend fun readSourceHintJson(
            dao: DownloadOperationDao,
            header: DownloadOperationHeaderRow
        ): String? {
            return DownloadExecutionRoomStore.readSourceHintJson(dao, header)
        }

        internal suspend fun invalidateMalformedPayload(
            database: NeriUserDataDatabase,
            header: DownloadOperationHeaderRow
        ) {
            DownloadExecutionRoomStore.invalidateMalformedPayload(database, header)
        }

        internal suspend fun invalidateMalformedPayloadInTransaction(
            database: NeriUserDataDatabase,
            header: DownloadOperationHeaderRow
        ) {
            DownloadExecutionRoomStore.invalidateMalformedPayloadInTransaction(database, header)
        }

        internal fun currentLibraryId(context: Context): String {
            return DownloadExecutionRoomStore.currentLibraryId(context)
        }

        internal fun requiresDirectCancellation(
            header: DownloadOperationHeaderRow
        ): Boolean {
            return DownloadExecutionRoomStore.requiresDirectCancellation(header)
        }

        internal fun requiresCommitBoundaryCancellation(
            header: DownloadOperationHeaderRow
        ): Boolean {
            return DownloadExecutionRoomStore.requiresCommitBoundaryCancellation(header)
        }

        internal suspend fun deleteOperationsWithAdmissions(
            database: NeriUserDataDatabase,
            operationIds: Collection<String>
        ): Int {
            return DownloadExecutionRoomStore.deleteOperationsWithAdmissions(
                database,
                operationIds
            )
        }
    }
}

private fun executionConvergencePriority(state: String): Int {
    return when (state) {
        "DEGRADED_COMPLETE" -> 8
        "ASSETS_ENRICHING" -> 7
        "CORE_COMMITTED" -> 6
        "COMMITTING" -> 5
        "RUNNING" -> 4
        "RETRYABLE" -> 3
        "QUEUED" -> 2
        "PENDING_QUEUE" -> 1
        else -> 0
    }
}

internal fun shouldRestartOperation(
    existingState: String?,
    requestedState: String,
    userInitiated: Boolean
): Boolean {
    return false
}
