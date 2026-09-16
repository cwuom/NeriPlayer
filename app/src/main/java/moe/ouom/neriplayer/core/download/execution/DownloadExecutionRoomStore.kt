package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    internal const val OPERATION_QUERY_PAGE_SIZE = 64
    internal const val CANCELLATION_QUERY_PAGE_SIZE = 256
    internal const val PUMP_QUERY_MAX_ITEMS = 64
    internal data class CachedNetworkPolicy(
        val requiresWifiNetwork: Boolean,
        val updatedAtMs: Long
    )

    internal val networkPolicyByOperationId =
        ConcurrentHashMap<String, CachedNetworkPolicy>()

    internal data class StateEntry(
        val request: DownloadExecutionRequest,
        val queueOrder: Int,
        val createdAtMs: Long,
        val state: String = "",
        val updatedAtMs: Long = createdAtMs,
        val retryCount: Int = 0,
        val nextRetryAtMs: Long? = null,
        val lastErrorCode: String? = null
    )

    internal data class PostCoreRetryRecord(
        val retryCount: Int,
        val nextRetryAtMs: Long,
        val updatedAtMs: Long
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
        val queueOrder: Int = 0,
        val nextRetryAtMs: Long? = null,
        val lastErrorCode: String? = null,
        val batchStateBits: Int? = null
    )

    internal data class DownloadBatchIdentity(
        val batchId: String,
        val generation: Long
    )

    internal data class DownloadBatchClearCapture(
        val identities: List<DownloadBatchIdentity>,
        val operationIdentities: List<OperationIdentity>,
        val stableKeys: Set<String>
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

    }

    internal fun canStartBatchForCurrentNetwork(
        batch: DownloadBatchEntity,
        currentNetworkGeneration: Long?
    ): Boolean {
        return this.canStartBatchForCurrentNetworkImpl(batch, currentNetworkGeneration)
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
        return this.upsertImpl(context, request, state, queueOrder, createdAtMs, database)
    }

    internal fun cachedNetworkPolicy(operationId: String): Boolean? {
        return networkPolicyByOperationId[operationId]?.requiresWifiNetwork
    }

    internal fun cacheNetworkPolicy(
        operationId: String,
        requiresWifiNetwork: Boolean,
        updatedAtMs: Long
    ) {
        return this.cacheNetworkPolicyImpl(operationId, requiresWifiNetwork, updatedAtMs)
    }

    internal fun evictNetworkPolicy(operationId: String) {
        networkPolicyByOperationId.remove(operationId)
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
    suspend fun readLatestOperationNetworkPoliciesForStableKeys(context: Context, stableKeys: Collection<String>, states: List<String>, excludeUserStoppedOperations: Boolean = true, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readLatestOperationNetworkPoliciesForStableKeys(context, stableKeys, states, excludeUserStoppedOperations, database)
    suspend fun readLatestOperationNetworkPoliciesByStatesAnyLibrary(context: Context, states: List<String>, excludeUserStoppedOperations: Boolean = true, database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)) =
        DownloadExecutionRoomReadStore.readLatestOperationNetworkPoliciesByStatesAnyLibrary(context, states, excludeUserStoppedOperations, database)
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
        return this.updateStateImpl(context, operationId, state, errorCode, database, nowMs)
    }

    suspend fun recordPostCoreRetryFailure(
        context: Context,
        operationId: String,
        stableKey: String,
        expectedAttemptId: Long?,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): PostCoreRetryRecord? {
        return this.recordPostCoreRetryFailureImpl(
            operationId,
            stableKey,
            expectedAttemptId,
            errorCode,
            database,
            nowMs
        )
    }

    suspend fun markPostCoreRetryExhausted(
        context: Context,
        operationId: String,
        stableKey: String,
        expectedAttemptId: Long?,
        minimumRetryCount: Int,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return this.markPostCoreRetryExhaustedImpl(
            operationId,
            stableKey,
            expectedAttemptId,
            minimumRetryCount,
            errorCode,
            database,
            nowMs
        )
    }

    suspend fun repairPrematurePostCoreBatchCompletions(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.repairPrematurePostCoreBatchCompletionsImpl(
            operationIds,
            database,
            nowMs
        )
    }

    /**
     * 已有可播放音频时，把仍处于传输前/传输中的 operation 原子收口
     *
     * 直接命中缓存不能只删除 transient task，否则执行宿主会把 RUNNING 行再次
     * 解释为 Retry。这里同时校验 operation、stableKey、payload attempt 和版本，
     * 并在同一事务内推进批次成员；等待目录变更或已跨过 Core Commit 的状态不走此捷径
     */
    suspend fun markAlreadyDownloadedCompleted(
        context: Context,
        operationId: String,
        stableKey: String,
        expectedAttemptId: Long? = null,
        errorCode: String = "DOWNLOAD_ALREADY_PRESENT",
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return this.markAlreadyDownloadedCompletedImpl(context, operationId, stableKey, expectedAttemptId, errorCode, database, nowMs)
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
        return this.reopenCorePublicationRecoveryImpl(context, operationId, stableKey, errorCode, database)
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
        return this.reopenMissingPostCoreArtifactForFreshTransferImpl(context, operationId, stableKey, expectedAttemptId, errorCode, database)
    }

    suspend fun markScheduleRejectedRetryable(
        context: Context,
        operationId: String,
        stableKey: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return this.markScheduleRejectedRetryableImpl(context, operationId, stableKey, errorCode, database, nowMs)
    }

    suspend fun markWaitingForStorageMutation(
        context: Context,
        operationId: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return this.markWaitingForStorageMutationImpl(context, operationId, errorCode, database)
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
        return this.promoteWaitingStorageMutationImpl(context, operationId, stableKey, database)
    }

    suspend fun markStagingPrepared(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return this.markStagingPreparedImpl(context, operationId, stableKey, database)
    }

    /** 用户重新点击下载时，只提升可恢复 operation 的意图，不重置租约或进度 */
    suspend fun promoteUserInitiatedOperation(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadExecutionRequest? {
        return this.promoteUserInitiatedOperationImpl(context, operationId, stableKey, database)
    }

    /** 为没有进度身份的旧记录持久化新生成的尝试编号 */
    suspend fun ensureAttemptId(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return this.ensureAttemptIdImpl(context, operationId, stableKey, attemptId, database)
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
        return this.tryStartImpl(context, operationId, allowExistingRunning, currentNetworkGeneration, database, nowMs)
    }

    suspend fun requestCancel(context: Context, operationId: String): Boolean {
        return this.requestCancelImpl(context, operationId)
    }

    suspend fun requestCancel(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase,
        updatedAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        return this.requestCancelImpl(context, operationId, database, updatedAtMs)
    }

    /** 在新请求落库后，按取消时刻原子标记仍残留的旧 operation */
    suspend fun requestCancelForStableKeysBefore(
        context: Context,
        boundaries: Collection<CancellationBoundary>,
        excludedOperationIds: Collection<String> = emptySet(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        return this.requestCancelForStableKeysBeforeImpl(context, boundaries, excludedOperationIds, database)
    }

    suspend fun purgeCancelled(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        return this.purgeCancelledImpl(context, stableKeys, database)
    }

    /** 按固定 operation 身份清理取消终态，避免删除替代请求的取消凭据 */
    suspend fun purgeCancelledOperationIds(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        return this.purgeCancelledOperationIdsImpl(context, operationIds, database)
    }

    suspend fun purgeAllCancelled(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            deleteByState(context, state, database)
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
        return ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                // core 文件保留时停止凭据也必须保留，否则目录恢复会重新创建旧任务
                dao.retainClearedArtifactRecoveryStops(
                    chunk, CLEARED_ARTIFACT_RECOVERY_STOP_STATES, System.currentTimeMillis()
                )
                val removableIds = dao.findAllHeadersByOperationIds(chunk)
                    .filterNot { header ->
                        header.stopRequestedByUser &&
                            header.state in CLEARED_ARTIFACT_RECOVERY_STOP_STATES
                    }
                    .map { it.operationId }
                dao.deleteHostAdmissions(chunk)
                if (removableIds.isEmpty()) 0 else dao.deleteOperations(removableIds)
            }
        }
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
        return this.tryAcquireHostAdmissionImpl(context, operationId, capacity, nowMs, database)
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
        return this.releaseHostAdmissionsImpl(context, operationIds, database)
    }

    suspend fun currentHostAdmissionCount(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        return this.currentHostAdmissionCountImpl(context, nowMs, database)
    }

    suspend fun markCoreCommitted(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return this.markCoreCommittedImpl(context, operationId, database)
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
        return this.createBatchSnapshotImpl(context, songs, initiallyCompletedSongKeys, clearEpoch, networkGeneration, nowMs, database)
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

    internal suspend fun insertBatchSnapshotInTransaction(
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
        return this.readOpenBatchSnapshotsImpl(context, database)
    }

    /** 按批次成员捕获网络策略作用域，避免使用 stableKey 扫描所有批次。 */
    suspend fun findOpenBatchIdentitiesForStableKeys(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<DownloadBatchIdentity> {
        return this.findOpenBatchIdentitiesForStableKeysImpl(context, stableKeys, database)
    }

    /** 按已捕获身份读取仍会被批次围栏阻塞的成员键 */
    suspend fun findPendingStableKeysForOpenBatches(
        context: Context,
        identities: Collection<DownloadBatchIdentity>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        return this.findPendingStableKeysForOpenBatchesImpl(context, identities, database)
    }

    /** 在一个 Room 事务内绑定一页实际 attempt，避免批量启动逐成员提交造成空窗 */
    suspend fun bindBatchMemberOperations(
        context: Context,
        identity: DownloadBatchIdentity,
        bindings: Collection<BatchMemberBinding>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.bindBatchMemberOperationsImpl(context, identity, bindings, database, nowMs)
    }

    /** 把一批已存在的 operation 绑定到批次，保留旧 operation 的身份并避免覆盖别的批次 */
    suspend fun attachBatchIdentity(
        context: Context,
        identity: DownloadBatchIdentity,
        requests: Collection<DownloadExecutionRequest>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.attachBatchIdentityImpl(context, identity, requests, database, nowMs)
    }

    /** 已有音频被重新排入传输时，撤销创建批次时的初始完成标记 */
    /** 一页清除重新传输前的初始完成标记，保持批量选择的事务边界 */
    suspend fun prepareBatchMembersForTransfer(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.prepareBatchMembersForTransferImpl(context, identity, stableKeys, database, nowMs)
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
        return this.markBatchMemberTerminalImpl(context, identity, stableKey, operationId, attemptId, terminalBits, fractionMilli, database, nowMs)
    }

    /** 依据 operation 身份更新所有引用该 operation 的批次成员；有 attempt 时继续精确匹配 */
    suspend fun updateBatchMembersForOperation(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long?,
        fractionMilli: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.updateBatchMembersForOperationImpl(context, operationId, stableKey, attemptId, fractionMilli, database, nowMs)
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
        return this.markBatchMembersForOperationImpl(context, operationId, stableKey, attemptId, terminalBits, fractionMilli, database, nowMs)
    }

    /** operation 身份未知时按 stableKey/attempt 查找成员，仍由 CAS 过滤迟到回调 */
    /**
     * 在持久清空 fence 已激活后，以 clearEpoch 为边界捕获旧批次并设置 CLEARING
     *
     * 成员取消和批次状态写入同一事务，晚到的 operation 回调会被 DAO 的 CLEARING CAS 拒绝
     */
    suspend fun beginBatchClear(
        context: Context,
        clearEpoch: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): DownloadBatchClearCapture {
        return this.beginBatchClearImpl(context, clearEpoch, database, nowMs)
    }

    /** 将已捕获的 CLEARING 批次原子收敛到 CANCELLED，允许恢复重试幂等调用。 */
    suspend fun finalizeBatchClear(
        context: Context,
        identities: Collection<DownloadBatchIdentity>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return this.finalizeBatchClearImpl(context, identities, database, nowMs)
    }

    suspend fun markBatchMembersCancelled(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.markBatchMembersCancelledImpl(context, identity, stableKeys, database, nowMs)
    }

    suspend fun markAllOpenBatchesCancelled(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int = this.markAllOpenBatchesCancelledImpl(context, database, nowMs)

    /** 网络策略等待必须落在批次状态中，进程重启后才能继续等待或恢复。 */
    suspend fun markBatchesNetworkWaiting(
        context: Context,
        stableKeys: Collection<String>,
        networkGeneration: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.markBatchesNetworkWaitingImpl(context, stableKeys, networkGeneration, database, nowMs)
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
        return this.markBatchesNetworkWaitingImpl(context, identities, networkGeneration, expectedNetworkGeneration, database, nowMs)
    }

    /** 旧 stableKey 入口不再允许空集合，避免一次恢复清除所有批次。 */
    /** 按已捕获身份清除网络等待，并在事务内读取每个批次的期望代次。 */
    /** 已确认 WIFI 时原子解除所有不晚于当前网络代次的开放批次网络围栏 */
    suspend fun clearAllOpenBatchNetworkPolicyFences(
        context: Context,
        networkGeneration: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.clearAllOpenBatchNetworkPolicyFencesImpl(context, networkGeneration, database, nowMs)
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
        return this.allowBatchesMobileDataImpl(context, identities, expectedNetworkGeneration, networkGeneration, database, nowMs)
    }

    suspend fun markInitialBatchMembersCompleted(
        context: Context,
        identity: DownloadBatchIdentity,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return this.markInitialBatchMembersCompletedImpl(context, identity, stableKeys, database, nowMs)
    }

    internal suspend fun markMembersCompletedForOperationInTransaction(
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
                    (incomingAttemptId == null ||
                        member.attemptId == null ||
                        member.attemptId == incomingAttemptId)
            }
            .forEach { member ->
                val memberAttemptId = member.attemptId ?: incomingAttemptId
                dao.markMemberTerminalCAS(
                    batchId = member.batchId,
                    stableKey = member.stableKey,
                    operationId = operationId,
                    attemptId = memberAttemptId,
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
        return this.reconcileCoreCommitJournalImpl(context, operationId, stableKey, expectedAttemptId, coreMetadataDurable, database)
    }

    suspend fun markCommitting(context: Context, operationId: String): Boolean {
        return this.markCommittingImpl(context, operationId)
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
        return this.markUserRequestedProcessExitOperationsImpl(context, entries, database)
    }

    /**
     * 回收上一个进程遗留的传输态
     *
     * RUNNING 不属于共享泵查询状态，不能依赖 ApplicationExitInfo 才恢复
     * 当前进程已持有宿主令牌的行不会被改动，提交后的状态也不会被降级
     */
    suspend fun requeueOrphanedRunningOperations(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        return this.requeueOrphanedRunningOperationsImpl(context, database)
    }

    suspend fun clearUserStopForStableKeys(
        context: Context,
        stableKeys: Collection<String>
    ): Boolean {
        return this.clearUserStopForStableKeysImpl(context, stableKeys)
    }

    suspend fun clearUserStopForFreshStart(
        context: Context,
        stableKeys: Collection<String>
    ): Boolean {
        return this.clearUserStopForFreshStartImpl(context, stableKeys)
    }

    suspend fun prepareExplicitResume(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return this.prepareExplicitResumeImpl(context, operationId, stableKey, database)
    }

    suspend fun prepareExplicitResumesForStableKeys(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        return this.prepareExplicitResumesForStableKeysImpl(context, stableKeys, database)
    }

    suspend fun restoreExplicitStop(
        context: Context,
        operationId: String,
        stableKey: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return this.restoreExplicitStopImpl(context, operationId, stableKey, errorCode, database)
    }

    internal suspend fun transitionStateAtomically(
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
        evictNetworkPolicy(operationId)
    }

    internal fun requestToJson(request: DownloadExecutionRequest): JSONObject {
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

    internal fun requestFromEntity(
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
        val request = runCatching {
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
        request?.let { decodedRequest ->
            cacheNetworkPolicy(
                operationId = entity.operationId,
                requiresWifiNetwork = decodedRequest.requiresWifiNetwork,
                updatedAtMs = entity.updatedAtMs
            )
        }
        return request
    }

    internal data class HeaderRequestRead(
        val request: DownloadExecutionRequest?,
        val payloadWasRead: Boolean
    )

    /** source_hint_json 可能包含完整歌词，必须分段读取以避开 CursorWindow 上限 */
    internal suspend fun readRequestFromHeader(
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

    internal suspend fun readSourceHintJson(
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

    internal suspend fun readResumeJson(
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

    internal fun DownloadOperationHeaderRow.toEntity(
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

    internal fun logDecodeFailure(
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

    internal suspend fun invalidateMalformedPayload(
        database: NeriUserDataDatabase,
        header: DownloadOperationHeaderRow
    ) {
        database.withTransaction {
            invalidateMalformedPayloadInTransaction(database, header)
        }
    }

    internal suspend fun invalidateMalformedPayloadInTransaction(
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

    internal suspend fun hasOtherValidWaitingStorageMutation(
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

    internal suspend fun hasOtherValidWaitingStorageMutation(
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

    internal fun requiresDirectCancellation(header: DownloadOperationHeaderRow): Boolean {
        return requiresDirectCancellation(
            state = header.state,
            stopRequestedByUser = header.stopRequestedByUser
        )
    }

    internal fun requiresDirectCancellation(
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

    internal fun requiresCommitBoundaryCancellation(header: DownloadOperationHeaderRow): Boolean {
        return requiresCommitBoundaryCancellation(
            state = header.state,
            stopRequestedByUser = header.stopRequestedByUser
        )
    }

    internal fun requiresCommitBoundaryCancellation(
        state: String,
        stopRequestedByUser: Boolean
    ): Boolean {
        return state in COMMIT_BOUNDARY_CANCEL_STATES && !stopRequestedByUser
    }

    internal suspend fun deleteOperationsWithAdmissions(
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

    internal fun currentLibraryId(context: Context): String {
        return ManagedDownloadStorage.currentSnapshotCacheKey(context.applicationContext)
    }

    internal fun nextPayloadUpdatedAt(
        previousUpdatedAtMs: Long?,
        requestedAtMs: Long = System.currentTimeMillis()
    ): Long {
        val previous = previousUpdatedAtMs ?: return requestedAtMs
        if (previous == Long.MAX_VALUE) return previous
        return maxOf(requestedAtMs, previous + 1L)
    }

    internal const val JOURNAL_PAYLOAD_VERSION = 1
    internal const val SOURCE_HINT_JSON_CHUNK_LENGTH = 64 * 1024
    internal const val SQLITE_IN_QUERY_CHUNK_SIZE = 900
    internal const val HOST_ADMISSION_HANDOFF_LEASE_MS = 30_000L
    internal val HOST_ADMISSION_PROCESS_TOKEN = UUID.randomUUID().toString()
    internal val TERMINAL_STATES = listOf("COMPLETED", "CANCELLED", "INVALID")
    internal val ACTIVE_OPERATION_STATES = listOf(
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
    /** states that may be closed after a verified, already-present audio hit */
    internal val DIRECT_CACHED_COMPLETION_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
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
    internal val CANCELABLE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "STOPPED",
        "RETRYABLE"
    )
    internal val CANCELLATION_CANDIDATE_OPERATION_STATES = listOf(
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
    internal val COMMIT_BOUNDARY_CANCEL_STATES = listOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    internal val EXPLICIT_RESUME_SOURCE_STATES = listOf(
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
    internal val EXPLICIT_STOP_RESTORE_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE"
    )
    internal val CORE_COMMIT_SOURCE_STATES = listOf(
        "COMMITTING"
    )
    internal val CORE_COMMITTED_STATES = setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE",
        "COMPLETED"
    )
    internal val CORE_COMMIT_BLOCKED_STATES = setOf(
        "CANCEL_REQUESTED",
        "CANCELLED",
        "STOPPED"
    )
    internal val CORE_COMMIT_RECOVERY_SOURCE_STATES = setOf(
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RETRYABLE"
    )
    internal val COMMIT_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING"
    )

    internal val ROOT_REHOME_OPERATION_STATES = REUSABLE_OPERATION_STATES +
        IN_FLIGHT_OPERATION_STATES +
        listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)

    internal val EXECUTION_CONVERGENCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE",
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )

    internal val DURABLE_CORE_EXECUTION_STATES = setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    internal val MISSING_POST_CORE_ARTIFACT_REOPEN_STATES = listOf(
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
        internal val DIRECT_CACHED_COMPLETION_SOURCE_STATES: List<String>
            get() = DownloadExecutionRoomStore.DIRECT_CACHED_COMPLETION_SOURCE_STATES
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

internal fun executionConvergencePriority(state: String): Int {
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
