package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
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

    internal data class DownloadBatchIdentity(
        val batchId: String,
        val generation: Long
    )

    internal data class DownloadBatchRecoverySnapshot(
        val batch: DownloadBatchEntity,
        val members: List<DownloadBatchMemberEntity>,
        val isConsistent: Boolean = batch.totalCount == members.size &&
            members.map { member -> member.ordinal } == members.indices.toList() &&
            members.map { member -> member.stableKey }.distinct().size == members.size &&
            members.all { member -> member.batchId == batch.batchId }
    )

    internal data class DownloadBatchNetworkPolicy(
        val identity: DownloadBatchIdentity,
        val stateBits: Int,
        val networkGeneration: Long?
    ) {
        val isWaitingForNetwork: Boolean
            get() = stateBits and DownloadBatchState.NETWORK_WAIT != 0

        fun allowsMobileData(currentNetworkGeneration: Long): Boolean {
            return !isWaitingForNetwork &&
                stateBits and DownloadBatchState.USER_MOBILE_ALLOWED != 0 &&
                networkGeneration == currentNetworkGeneration
        }
    }

    internal fun canStartBatchForCurrentNetwork(
        batch: DownloadBatchEntity,
        currentNetworkGeneration: Long?
    ): Boolean {
        val hasMobileDataAllowance =
            batch.stateBits and DownloadBatchState.USER_MOBILE_ALLOWED != 0
        if (hasMobileDataAllowance) {
            val currentGeneration = currentNetworkGeneration?.takeIf { generation -> generation >= 0L }
                ?: return false
            if (batch.networkGeneration != currentGeneration) return false
        }
        if (batch.stateBits and DownloadBatchState.NETWORK_WAIT != 0) return false
        return true
    }

    internal enum class BatchMemberMutation {
        APPLIED,
        IDEMPOTENT,
        STALE,
        MISSING
    }

    internal data class BatchMemberBinding(
        val stableKey: String,
        val operationId: String,
        val attemptId: Long?
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
            val preservedBatchId = request.batchId ?: existingRequest?.batchId ?: existingHeader?.batchId
            val preservedBatchGeneration = request.batchGeneration
                ?: existingRequest?.batchGeneration
                ?: existingHeader?.batchGeneration
            val effectiveUserInitiated = request.userInitiated ||
                existingRequest?.userInitiated == true
            val requestWithMonotonicIntent = request.copy(
                userInitiated = effectiveUserInitiated
            )
            val persistedRequest = when {
                restartForNewAttempt -> requestWithMonotonicIntent.copy(
                    artifactLeaseId = UUID.randomUUID().toString(),
                    batchId = preservedBatchId,
                    batchGeneration = preservedBatchGeneration
                )
                existingRequest != null -> requestWithMonotonicIntent.copy(
                    artifactLeaseId = existingRequest.artifactLeaseId,
                    batchId = preservedBatchId,
                    batchGeneration = preservedBatchGeneration
                )
                else -> requestWithMonotonicIntent
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
                    hostAdmittedAtMs = existingHeader?.hostAdmittedAtMs,
                    batchId = persistedRequest.batchId,
                    batchGeneration = persistedRequest.batchGeneration
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

    /**
     * 旧版本可能留下 core 状态但没有持久音频引用
     * 只有 artifact 已由同一 operation 重新取得 lease 时才允许回到传输阶段
     */
    suspend fun reopenMissingPostCoreArtifactForFreshTransfer(
        context: Context,
        operationId: String,
        stableKey: String,
        expectedAttemptId: Long?,
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
                    header.state !in MISSING_POST_CORE_ARTIFACT_REOPEN_STATES
            ) {
                return@withTransaction false
            }
            val request = readRequestFromHeader(dao, header).request
                ?: return@withTransaction false
            if (
                request.song.stableKey() != normalizedKey ||
                    expectedAttemptId != null && request.attemptId != expectedAttemptId
            ) {
                return@withTransaction false
            }
            dao.transitionState(
                operationId = operationId,
                expectedStates = MISSING_POST_CORE_ARTIFACT_REOPEN_STATES,
                state = "RUNNING",
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

    /** 用户重新点击下载时，只提升可恢复 operation 的意图，不重置租约或进度 */
    suspend fun promoteUserInitiatedOperation(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadExecutionRequest? {
        val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank) ?: return null
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return null
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(normalizedOperationId) ?: return@withTransaction null
            if (
                header.stableKey != normalizedKey ||
                    header.state !in IN_FLIGHT_OPERATION_STATES + REUSABLE_OPERATION_STATES ||
                    header.stopRequestedByUser
            ) {
                return@withTransaction null
            }
            val decoded = readRequestFromHeader(dao, header)
            val request = decoded.request ?: run {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayloadInTransaction(database, header)
                }
                return@withTransaction null
            }
            if (request.song.stableKey() != normalizedKey) {
                invalidateMalformedPayloadInTransaction(database, header)
                return@withTransaction null
            }
            if (request.userInitiated) return@withTransaction request
            val promoted = request.copy(userInitiated = true)
            if (
                dao.updateRequestPayload(
                    operationId = normalizedOperationId,
                    stableKey = normalizedKey,
                    sourceHintJson = requestToJson(promoted).toString(),
                    updatedAtMs = nextPayloadUpdatedAt(
                        previousUpdatedAtMs = header.updatedAtMs
                    )
                ) <= 0
            ) {
                return@withTransaction null
            }
            promoted
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
        currentNetworkGeneration: Long? = null,
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
            val batchId = target.batchId
            val batchGeneration = target.batchGeneration
            if (batchId != null || batchGeneration != null) {
                if (batchId == null || batchGeneration == null) {
                    return@withTransaction false
                }
                val batch = database.downloadBatchDao()
                    .findBatch(batchId, batchGeneration)
                    ?: return@withTransaction false
                // 网络代际和 RUNNING 转换必须在同一事务内检查，避免旧确认越过新等待态
                if (!canStartBatchForCurrentNetwork(batch, currentNetworkGeneration)) {
                    return@withTransaction false
                }
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

    suspend fun markCoreCommitted(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return false
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val entity = dao.find(normalizedOperationId)
                ?: return@withTransaction false
            val request = requestFromEntity(entity)
            val attemptId = request?.attemptId
            val changed = dao.markCoreCommitted(
                operationId = normalizedOperationId,
                expectedStates = CORE_COMMIT_SOURCE_STATES,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
            val currentState = dao.findState(normalizedOperationId)
            val committed = changed || currentState in CORE_COMMITTED_STATES
            if (committed) {
                markMembersCompletedForOperationInTransaction(
                    database = database,
                    operationId = normalizedOperationId,
                    stableKey = entity.stableKey,
                    attemptId = attemptId
                )
            }
            committed
        }
    }

    /** Creates one durable user-selection snapshot and all of its members atomically. */
    suspend fun createBatchSnapshot(
        context: Context,
        songs: Collection<SongItem>,
        initiallyCompletedSongKeys: Set<String> = emptySet(),
        clearEpoch: Long = 0L,
        networkGeneration: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadBatchIdentity {
        val normalizedSongs = songs
            .map { song -> song to song.stableKey().trim() }
            .filter { (_, key) -> key.isNotBlank() }
            .distinctBy { (_, key) -> key }
            .map { (song, _) -> song }
        require(normalizedSongs.isNotEmpty()) { "songs must not be empty" }
        return database.withTransaction {
            val batchDao = database.downloadBatchDao()
            val previousGeneration = batchDao.findMaxGeneration() ?: 0L
            require(previousGeneration < Long.MAX_VALUE) {
                "download batch generation exhausted"
            }
            val identity = DownloadBatchIdentity(
                batchId = UUID.randomUUID().toString(),
                generation = (previousGeneration + 1L).coerceAtLeast(1L)
            )
            val completedKeys = initiallyCompletedSongKeys
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            val members = normalizedSongs.mapIndexed { ordinal, song ->
                val stableKey = song.stableKey()
                val initiallyCompleted = stableKey in completedKeys
                DownloadBatchMemberEntity(
                    batchId = identity.batchId,
                    ordinal = ordinal,
                    stableKey = stableKey,
                    terminalBits = if (initiallyCompleted) {
                        DownloadBatchMemberTerminal.COMPLETED
                    } else {
                        DownloadBatchMemberTerminal.NONE
                    },
                    maxFractionMilli = if (initiallyCompleted) 1000 else 0,
                    initiallyCompleted = initiallyCompleted,
                    updatedAtMs = nowMs
                )
            }
            val batch = DownloadBatchEntity(
                batchId = identity.batchId,
                generation = identity.generation,
                totalCount = members.size,
                stateBits = DownloadBatchState.OPEN,
                clearEpoch = clearEpoch.coerceAtLeast(0L),
                networkGeneration = networkGeneration,
                updatedAtMs = nowMs,
                createdAtMs = nowMs
            )
            insertBatchSnapshotInTransaction(database, batch, members)
            if (members.all { member -> member.terminalBits != DownloadBatchMemberTerminal.NONE }) {
                batchDao.markCompletedIfAllMembersTerminal(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    nowMs = nowMs
                )
            }
            identity
        }
    }

    /** Compatibility overload used by migration fixtures and focused tests. */
    suspend fun createBatchSnapshot(
        context: Context,
        batch: DownloadBatchEntity,
        members: List<DownloadBatchMemberEntity>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) = database.withTransaction {
        insertBatchSnapshotInTransaction(database, batch, members)
    }

    private suspend fun insertBatchSnapshotInTransaction(
        database: NeriUserDataDatabase,
        batch: DownloadBatchEntity,
        members: List<DownloadBatchMemberEntity>
    ) {
        require(members.size == batch.totalCount) {
            "batch member count does not match total count"
        }
        require(members.map { member -> member.stableKey }.toSet().size == members.size) {
            "batch members must have unique stable keys"
        }
        require(members.map { member -> member.ordinal }.toSet().size == members.size) {
            "batch members must have unique ordinals"
        }
        require(members.map { member -> member.ordinal }.sorted() == members.indices.toList()) {
            "batch member ordinals must be contiguous"
        }
        require(members.all { member -> member.batchId == batch.batchId }) {
            "batch member belongs to a different batch"
        }
        database.downloadBatchDao().insertBatch(batch)
        database.downloadBatchDao().insertMembers(members)
    }

    suspend fun readOpenBatchSnapshots(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<DownloadBatchRecoverySnapshot> {
        val batchDao = database.downloadBatchDao()
        return database.withTransaction {
            batchDao.findOpenBatches().map { batch ->
                DownloadBatchRecoverySnapshot(
                    batch = batch,
                    members = batchDao.listMembers(batch.batchId)
                )
            }
        }
    }

    suspend fun readBatchNetworkPolicy(
        context: Context,
        identity: DownloadBatchIdentity,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadBatchNetworkPolicy? {
        return database.downloadBatchDao().findBatch(identity.batchId, identity.generation)
            ?.let { batch ->
                DownloadBatchNetworkPolicy(
                    identity = identity,
                    stateBits = batch.stateBits,
                    networkGeneration = batch.networkGeneration
                )
            }
    }

    suspend fun bindBatchMemberOperation(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): BatchMemberMutation {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return BatchMemberMutation.STALE
        val normalizedOperationId = normalizeDownloadOperationId(operationId)
            ?: return BatchMemberMutation.STALE
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            if (dao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction BatchMemberMutation.MISSING
            }
            val updated = dao.bindMemberOperationCAS(
                batchId = identity.batchId,
                batchGeneration = identity.generation,
                stableKey = normalizedKey,
                operationId = normalizedOperationId,
                attemptId = attemptId?.takeIf { it > 0L },
                nowMs = nowMs
            )
            if (updated > 0) {
                BatchMemberMutation.APPLIED
            } else {
                val current = dao.findMember(identity.batchId, normalizedKey)
                if (
                    current?.operationId == normalizedOperationId &&
                        current.attemptId == attemptId?.takeIf { it > 0L }
                ) {
                    BatchMemberMutation.IDEMPOTENT
                } else {
                    BatchMemberMutation.STALE
                }
            }
        }
    }

    /** 按批次成员捕获网络策略作用域，避免使用 stableKey 扫描所有批次。 */
    suspend fun findOpenBatchIdentitiesForStableKeys(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<DownloadBatchIdentity> {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) return emptyList()
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            dao.findOpenBatches().mapNotNull { batch ->
                dao.listMembers(batch.batchId)
                    .any { member -> member.stableKey in keys }
                    .takeIf { it }
                    ?.let {
                        DownloadBatchIdentity(
                            batchId = batch.batchId,
                            generation = batch.generation
                        )
                    }
            }
        }
    }

    /** 在一个 Room 事务内绑定一页实际 attempt，避免批量启动逐成员提交造成空窗 */
    suspend fun bindBatchMemberOperations(
        context: Context,
        identity: DownloadBatchIdentity,
        bindings: Collection<BatchMemberBinding>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val normalizedBindings = bindings.mapNotNull { binding ->
            val key = binding.stableKey.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val operationId = normalizeDownloadOperationId(binding.operationId)
                ?: return@mapNotNull null
            BatchMemberBinding(key, operationId, binding.attemptId?.takeIf { it > 0L })
        }.distinctBy { binding -> binding.stableKey }
        if (normalizedBindings.isEmpty()) return 0
        return database.withTransaction {
            val operationDao = database.downloadOperationDao()
            val batchDao = database.downloadBatchDao()
            if (batchDao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction 0
            }
            normalizedBindings.count { binding ->
                val header = operationDao.findHeader(binding.operationId)
                if (
                    header == null ||
                        header.stableKey != binding.stableKey ||
                        header.batchId != identity.batchId ||
                        header.batchGeneration != identity.generation
                ) {
                    false
                } else {
                    batchDao.bindMemberOperationCAS(
                        batchId = identity.batchId,
                        batchGeneration = identity.generation,
                        stableKey = binding.stableKey,
                        operationId = binding.operationId,
                        attemptId = binding.attemptId,
                        nowMs = nowMs
                    ) > 0
                }
            }
        }
    }

    /** 把一批已存在的 operation 绑定到批次，保留旧 operation 的身份并避免覆盖别的批次 */
    suspend fun attachBatchIdentity(
        context: Context,
        identity: DownloadBatchIdentity,
        requests: Collection<DownloadExecutionRequest>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val distinctRequests = requests
            .asSequence()
            .filter { request -> request.song.stableKey().isNotBlank() }
            .distinctBy(DownloadExecutionRequest::operationId)
            .toList()
        if (distinctRequests.isEmpty()) return 0
        return database.withTransaction {
            val operationDao = database.downloadOperationDao()
            val batchDao = database.downloadBatchDao()
            if (batchDao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction 0
            }
            var boundMembers = 0
            distinctRequests.forEach { request ->
                val stableKey = request.song.stableKey()
                val header = operationDao.findHeader(request.operationId) ?: return@forEach
                if (header.stableKey != stableKey) return@forEach
                val existingRequest = readSourceHintJson(operationDao, header)
                    ?.let { sourceHintJson ->
                        requestFromEntity(header.toEntity(sourceHintJson))
                    }
                val existingBatchId = existingRequest?.batchId ?: header.batchId
                val existingBatchGeneration =
                    existingRequest?.batchGeneration ?: header.batchGeneration
                val operationBoundToTarget = when {
                    existingBatchId == null && existingBatchGeneration == null -> {
                        operationDao.bindBatchIdentityIfUnbound(
                            operationId = request.operationId,
                            stableKey = stableKey,
                            batchId = identity.batchId,
                            batchGeneration = identity.generation,
                            sourceHintJson = requestToJson(
                                request.copy(
                                    batchId = identity.batchId,
                                    batchGeneration = identity.generation
                                )
                            ).toString(),
                            updatedAtMs = nowMs
                        ) > 0
                    }

                    existingBatchId == identity.batchId &&
                        existingBatchGeneration == identity.generation -> true

                    else -> false
                }
                if (!operationBoundToTarget) {
                    // 一个 operation 只能归属一个批次。不能把旧批次的 operation
                    // 再挂到新批次，否则同一回调会同时推进两个批次
                    return@forEach
                }
                val effectiveAttemptId = request.attemptId
                    ?: existingRequest?.attemptId
                val changed = batchDao.bindMemberOperationCAS(
                    batchId = identity.batchId,
                    batchGeneration = identity.generation,
                    stableKey = stableKey,
                    operationId = request.operationId,
                    attemptId = effectiveAttemptId?.takeIf { it > 0L },
                    nowMs = nowMs
                )
                if (changed > 0) boundMembers++
            }
            boundMembers
        }
    }

    /** 已有音频被重新排入传输时，撤销创建批次时的初始完成标记 */
    suspend fun prepareBatchMemberForTransfer(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            if (dao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction false
            }
            dao.clearInitialMemberCompletionCAS(
                batchId = identity.batchId,
                stableKey = normalizedKey,
                nowMs = nowMs
            )
            dao.findMember(identity.batchId, normalizedKey)?.terminalBits ==
                DownloadBatchMemberTerminal.NONE
        }
    }

    /** 一页清除重新传输前的初始完成标记，保持批量选择的事务边界 */
    suspend fun prepareBatchMembersForTransfer(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            if (dao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction 0
            }
            keys.sumOf { stableKey ->
                dao.clearInitialMemberCompletionCAS(
                    batchId = identity.batchId,
                    stableKey = stableKey,
                    nowMs = nowMs
                )
            }
        }
    }

    suspend fun updateBatchMemberProgress(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        fractionMilli: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): BatchMemberMutation {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return BatchMemberMutation.STALE
        val normalizedOperationId = normalizeDownloadOperationId(operationId)
            ?: return BatchMemberMutation.STALE
        val fraction = fractionMilli.coerceIn(0, 1000)
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            if (dao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction BatchMemberMutation.MISSING
            }
            val normalizedAttemptId = attemptId?.takeIf { it > 0L }
            val updated = dao.updateMemberFractionMaxCAS(
                batchId = identity.batchId,
                stableKey = normalizedKey,
                operationId = normalizedOperationId,
                attemptId = normalizedAttemptId,
                fraction = fraction,
                nowMs = nowMs
            )
            if (updated > 0) {
                BatchMemberMutation.APPLIED
            } else {
                val current = dao.findMember(identity.batchId, normalizedKey)
                if (
                    current?.operationId == normalizedOperationId &&
                        current.attemptId == normalizedAttemptId &&
                        current.maxFractionMilli >= fraction
                ) {
                    BatchMemberMutation.IDEMPOTENT
                } else {
                    BatchMemberMutation.STALE
                }
            }
        }
    }

    suspend fun markBatchMemberTerminal(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        terminalBits: Int,
        fractionMilli: Int = 1000,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): BatchMemberMutation {
        require(terminalBits in DownloadBatchMemberTerminal.VALID_BITS) {
            "invalid batch member terminal bits"
        }
        require(terminalBits != DownloadBatchMemberTerminal.NONE) {
            "terminal bits must not be NONE"
        }
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return BatchMemberMutation.STALE
        val normalizedOperationId = normalizeDownloadOperationId(operationId)
            ?: return BatchMemberMutation.STALE
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            if (dao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction BatchMemberMutation.MISSING
            }
            val normalizedAttemptId = attemptId?.takeIf { it > 0L }
            val updated = dao.markMemberTerminalCAS(
                batchId = identity.batchId,
                stableKey = normalizedKey,
                operationId = normalizedOperationId,
                attemptId = normalizedAttemptId,
                terminalBits = terminalBits,
                fraction = fractionMilli.coerceIn(0, 1000),
                nowMs = nowMs
            )
            val result = if (updated > 0) {
                BatchMemberMutation.APPLIED
            } else {
                val current = dao.findMember(identity.batchId, normalizedKey)
                if (
                    current?.operationId == normalizedOperationId &&
                        current.attemptId == normalizedAttemptId &&
                        current.terminalBits == terminalBits
                ) {
                    BatchMemberMutation.IDEMPOTENT
                } else {
                    BatchMemberMutation.STALE
                }
            }
            if (result == BatchMemberMutation.APPLIED || result == BatchMemberMutation.IDEMPOTENT) {
                dao.markCompletedIfAllMembersTerminal(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    nowMs = nowMs
                )
            }
            result
        }
    }

    /** 依据 operation 身份更新所有引用该 operation 的批次成员，旧 attempt 会被 CAS 拒绝 */
    suspend fun updateBatchMembersForOperation(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long?,
        fractionMilli: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return 0
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            val incomingAttemptId = attemptId?.takeIf { it > 0L }
            dao.findMembersByOperation(normalizedOperationId)
                .filter { member ->
                    member.stableKey == normalizedKey && member.attemptId == incomingAttemptId
                }
                .sumOf { member ->
                    dao.updateMemberFractionMaxCAS(
                        batchId = member.batchId,
                        stableKey = member.stableKey,
                        operationId = normalizedOperationId,
                        attemptId = incomingAttemptId,
                        fraction = fractionMilli.coerceIn(0, 1000),
                        nowMs = nowMs
                    )
                }
        }
    }

    suspend fun markBatchMembersForOperation(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long?,
        terminalBits: Int,
        fractionMilli: Int = 0,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        require(terminalBits in DownloadBatchMemberTerminal.VALID_BITS)
        require(terminalBits != DownloadBatchMemberTerminal.NONE)
        val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return 0
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            val incomingAttemptId = attemptId?.takeIf { it > 0L }
            var changed = 0
            dao.findMembersByOperation(normalizedOperationId)
                .filter { member ->
                    member.stableKey == normalizedKey && member.attemptId == incomingAttemptId
                }
                .forEach { member ->
                    changed += dao.markMemberTerminalCAS(
                        batchId = member.batchId,
                        stableKey = member.stableKey,
                        operationId = normalizedOperationId,
                        attemptId = incomingAttemptId,
                        terminalBits = terminalBits,
                        fraction = if (terminalBits == DownloadBatchMemberTerminal.COMPLETED) {
                            1000
                        } else {
                            maxOf(member.maxFractionMilli, fractionMilli.coerceIn(0, 1000))
                        },
                        nowMs = nowMs
                    )
                    dao.findBatchById(member.batchId)?.let { batch ->
                        dao.markCompletedIfAllMembersTerminal(
                            batchId = batch.batchId,
                            generation = batch.generation,
                            nowMs = nowMs
                        )
                    }
                }
            changed
        }
    }

    /** operation 身份未知时按 stableKey/attempt 查找成员，仍由 CAS 过滤迟到回调 */
    suspend fun markBatchMembersForStableKey(
        context: Context,
        stableKey: String,
        attemptId: Long?,
        terminalBits: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        require(terminalBits in DownloadBatchMemberTerminal.VALID_BITS)
        require(terminalBits != DownloadBatchMemberTerminal.NONE)
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            var changed = 0
            dao.findMembersByStableKey(normalizedKey).forEach { member ->
                val operationId = member.operationId ?: return@forEach
                val expectedAttemptId = member.attemptId
                val incomingAttemptId = attemptId?.takeIf { it > 0L }
                if (
                    expectedAttemptId != null &&
                        expectedAttemptId != incomingAttemptId
                ) {
                    return@forEach
                }
                changed += dao.markMemberTerminalCAS(
                    batchId = member.batchId,
                    stableKey = normalizedKey,
                    operationId = operationId,
                    attemptId = expectedAttemptId,
                    terminalBits = terminalBits,
                    fraction = if (terminalBits == DownloadBatchMemberTerminal.COMPLETED) {
                        1000
                    } else {
                        member.maxFractionMilli
                    },
                    nowMs = nowMs
                )
                dao.findBatchById(member.batchId)?.let { batch ->
                    dao.markCompletedIfAllMembersTerminal(
                        batchId = batch.batchId,
                        generation = batch.generation,
                        nowMs = nowMs
                    )
                }
            }
            changed
        }
    }

    suspend fun markBatchesCancelled(
        context: Context,
        identities: Collection<DownloadBatchIdentity>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val distinctIdentities = identities.distinct()
        if (distinctIdentities.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            var changed = 0
            distinctIdentities.forEach { identity ->
                dao.markMembersCancelled(identity.batchId, nowMs)
                changed += dao.markCancelled(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    nowMs = nowMs
                )
            }
            changed
        }
    }

    suspend fun markBatchMembersCancelled(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            if (dao.findBatch(identity.batchId, identity.generation) == null) {
                return@withTransaction 0
            }
            keys.sumOf { stableKey ->
                dao.markMemberCancelled(
                    batchId = identity.batchId,
                    stableKey = stableKey,
                    nowMs = nowMs
                )
            }.also {
                dao.markCompletedIfAllMembersTerminal(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    nowMs = nowMs
                )
            }
        }
    }

    suspend fun markAllOpenBatchesCancelled(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int = database.withTransaction {
        val dao = database.downloadBatchDao()
        dao.markMembersCancelledForAllOpenBatches(nowMs)
        dao.markAllOpenBatchesCancelled(nowMs)
    }

    /** 网络策略等待必须落在批次状态中，进程重启后才能继续等待或恢复。 */
    suspend fun markBatchesNetworkWaiting(
        context: Context,
        stableKeys: Collection<String>,
        networkGeneration: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) return 0
        require(networkGeneration >= 0L) { "networkGeneration must not be negative" }
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            var changed = 0
            dao.findOpenBatches().forEach { batch ->
                if (dao.listMembers(batch.batchId).none { member -> member.stableKey in keys }) {
                    return@forEach
                }
                changed += dao.markNetworkWaitingCAS(
                    batchId = batch.batchId,
                    generation = batch.generation,
                    networkGeneration = networkGeneration,
                    expectedNetworkGeneration = batch.networkGeneration,
                    nowMs = nowMs
                )
            }
            changed
        }
    }

    /** 仅按已捕获批次身份写网络等待，避免同 stableKey 的不同批次互相污染 */
    suspend fun markBatchesNetworkWaiting(
        context: Context,
        identities: Collection<DownloadBatchIdentity>,
        networkGeneration: Long,
        expectedNetworkGeneration: Long?,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val distinctIdentities = identities.distinct()
        if (distinctIdentities.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            distinctIdentities.sumOf { identity ->
                val batch = dao.findBatch(identity.batchId, identity.generation)
                    ?: return@sumOf 0
                if (expectedNetworkGeneration != null &&
                    batch.networkGeneration != expectedNetworkGeneration
                ) {
                    return@sumOf 0
                }
                dao.markNetworkWaitingCAS(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    networkGeneration = networkGeneration,
                    expectedNetworkGeneration = batch.networkGeneration,
                    nowMs = nowMs
                )
            }
        }
    }

    /** 旧 stableKey 入口不再允许空集合，避免一次恢复清除所有批次。 */
    suspend fun clearBatchesNetworkWaiting(
        context: Context,
        stableKeys: Collection<String> = emptySet(),
        networkGeneration: Long,
        expectedNetworkGeneration: Long? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) return 0
        require(networkGeneration >= 0L) { "networkGeneration must not be negative" }
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            var changed = 0
            dao.findOpenBatches().forEach { batch ->
                if (keys.isNotEmpty() &&
                    dao.listMembers(batch.batchId).none { member -> member.stableKey in keys }
                ) {
                    return@forEach
                }
                changed += dao.clearNetworkWaitingCAS(
                    batchId = batch.batchId,
                    generation = batch.generation,
                    networkGeneration = networkGeneration,
                    expectedNetworkGeneration = expectedNetworkGeneration,
                    nowMs = nowMs
                )
            }
            changed
        }
    }

    /** 按已捕获身份清除网络等待，并在事务内读取每个批次的期望代次。 */
    suspend fun clearBatchesNetworkWaitingForIdentities(
        context: Context,
        identities: Collection<DownloadBatchIdentity>,
        networkGeneration: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val distinctIdentities = identities.distinct()
        if (distinctIdentities.isEmpty()) return 0
        require(networkGeneration >= 0L) { "networkGeneration must not be negative" }
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            distinctIdentities.sumOf { identity ->
                val batch = dao.findBatch(identity.batchId, identity.generation)
                    ?: return@sumOf 0
                dao.clearNetworkWaitingCAS(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    networkGeneration = networkGeneration,
                    expectedNetworkGeneration = batch.networkGeneration,
                    nowMs = nowMs
                )
            }
        }
    }

    /** 用户确认只允许请求中捕获的批次在同一网络代际使用移动数据 */
    suspend fun allowBatchesMobileData(
        context: Context,
        identities: Collection<DownloadBatchIdentity>,
        expectedNetworkGeneration: Long,
        networkGeneration: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val distinctIdentities = identities.distinct()
        if (distinctIdentities.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            distinctIdentities.sumOf { identity ->
                dao.allowMobileDataCAS(
                    batchId = identity.batchId,
                    generation = identity.generation,
                    expectedNetworkGeneration = expectedNetworkGeneration,
                    networkGeneration = networkGeneration,
                    nowMs = nowMs
                )
            }
        }
    }

    suspend fun markInitialBatchMembersCompleted(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return 0
        return database.withTransaction {
            val dao = database.downloadBatchDao()
            var updated = 0
            keys.forEach { stableKey ->
                updated += dao.markInitialMemberTerminalCAS(
                    batchId = identity.batchId,
                    stableKey = stableKey,
                    terminalBits = DownloadBatchMemberTerminal.COMPLETED,
                    fraction = 1000,
                    nowMs = nowMs
                )
            }
            dao.markCompletedIfAllMembersTerminal(
                batchId = identity.batchId,
                generation = identity.generation,
                nowMs = nowMs
            )
            updated
        }
    }

    private suspend fun markMembersCompletedForOperationInTransaction(
        database: NeriUserDataDatabase,
        operationId: String,
        stableKey: String,
        attemptId: Long?
    ) {
        val dao = database.downloadBatchDao()
        val incomingAttemptId = attemptId?.takeIf { it > 0L }
        dao.findMembersByOperation(operationId)
            .filter { member ->
                member.stableKey == stableKey &&
                    member.attemptId == incomingAttemptId
            }
            .forEach { member ->
                dao.markMemberTerminalCAS(
                    batchId = member.batchId,
                    stableKey = member.stableKey,
                    operationId = operationId,
                    attemptId = incomingAttemptId,
                    terminalBits = DownloadBatchMemberTerminal.COMPLETED,
                    fraction = 1000,
                    nowMs = System.currentTimeMillis()
                )
                dao.findBatchById(member.batchId)?.let { batch ->
                    dao.markCompletedIfAllMembersTerminal(
                        batchId = batch.batchId,
                        generation = batch.generation,
                        nowMs = System.currentTimeMillis()
                    )
                }
            }
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
                        markMembersCompletedForOperationInTransaction(
                            database = database,
                            operationId = normalizedOperationId,
                            stableKey = requestStableKey,
                            attemptId = request.attemptId
                        )
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
                            markMembersCompletedForOperationInTransaction(
                                database = database,
                                operationId = normalizedOperationId,
                                stableKey = requestStableKey,
                                attemptId = request.attemptId
                            )
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
            request.batchId?.let { put("batchId", it) }
            request.batchGeneration?.let { put("batchGeneration", it) }
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
                },
                batchId = root.optString("batchId").takeIf(String::isNotBlank) ?: entity.batchId,
                batchGeneration = root.optLong("batchGeneration", 0L).takeIf { it > 0L } ?: entity.batchGeneration
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
            hostAdmittedAtMs = hostAdmittedAtMs,
            batchId = batchId,
            batchGeneration = batchGeneration
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
    /** 共享泵只接管可新开始传输的 operation，core 后的收尾由独立恢复路径处理 */
    internal val PUMP_OPERATION_STATES = REUSABLE_OPERATION_STATES
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
    private val MISSING_POST_CORE_ARTIFACT_REOPEN_STATES = listOf(
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
