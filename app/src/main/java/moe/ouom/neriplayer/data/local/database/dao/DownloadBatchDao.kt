package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.DOWNLOAD_BATCH_POST_CORE_PENDING_FRACTION_MILLI

@Dao
internal interface DownloadBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBatch(batch: DownloadBatchEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMembers(members: List<DownloadBatchMemberEntity>)

    @Query("SELECT MAX(generation) FROM download_batch")
    suspend fun findMaxGeneration(): Long?

    @Query("SELECT * FROM download_batch WHERE batch_id = :batchId AND generation = :generation LIMIT 1") suspend fun findBatch(batchId: String, generation: Long): DownloadBatchEntity?

    @Query("SELECT * FROM download_batch WHERE batch_id = :batchId LIMIT 1")
    suspend fun findBatchById(batchId: String): DownloadBatchEntity?
    @Query(
        "SELECT * FROM download_batch " +
            "WHERE state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "ORDER BY updated_at_ms ASC, generation ASC"
    )
    suspend fun findOpenBatches(): List<DownloadBatchEntity>

    /** 按成员键直接定位开放批次，避免网络边沿逐批加载全部成员 */
    @Query(
        "SELECT * FROM download_batch WHERE batch_id IN (" +
            "SELECT DISTINCT batch_id FROM download_batch_member " +
            "WHERE stable_key IN (:stableKeys) AND terminal_bits = 0) " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "ORDER BY updated_at_ms ASC, generation ASC"
    )
    suspend fun findOpenBatchesForStableKeys(
        stableKeys: List<String>
    ): List<DownloadBatchEntity>

    /** 清空只捕获当前 fence 之前的批次，已进入 CLEARING 的行可幂等重试 */
    @Query(
        "SELECT * FROM download_batch " +
            "WHERE state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND ((state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND clear_epoch < :clearEpoch) " +
            "OR (state_bits & ${DownloadBatchState.CLEARING} != 0 " +
            "AND clear_epoch <= :clearEpoch)) " +
            "ORDER BY updated_at_ms ASC, generation ASC"
    )
    suspend fun findBatchesForClear(clearEpoch: Long): List<DownloadBatchEntity>

    @Query(
        "SELECT * FROM download_batch_member WHERE batch_id = :batchId " +
            "AND (:afterOrdinal IS NULL OR ordinal > :afterOrdinal) " +
            "ORDER BY ordinal ASC LIMIT :limit"
    )
    suspend fun pageMembers(
        batchId: String,
        afterOrdinal: Int?,
        limit: Int
    ): List<DownloadBatchMemberEntity>

    @Query(
        "UPDATE download_batch_member SET operation_id = :operationId, " +
            "attempt_id = :attemptId, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId AND generation = :batchGeneration " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND EXISTS (SELECT 1 FROM download_operation " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND batch_id = :batchId AND batch_generation = :batchGeneration) " +
            "AND terminal_bits = 0 " +
            "AND (operation_id IS NULL OR operation_id = :operationId) " +
            "AND (attempt_id IS NULL OR attempt_id = :attemptId)"
    )
    suspend fun bindMemberOperationCAS(
        batchId: String,
        batchGeneration: Long,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch_member SET " +
            "max_fraction_milli = MAX(max_fraction_milli, :fraction), " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND terminal_bits = 0 AND operation_id = :operationId " +
            // legacy 成员可能尚未写入 attempt；operation identity 仍提供边界，
            // 已有 attempt 时继续精确匹配
            "AND (attempt_id IS NULL OR attempt_id = :attemptId)"
    )
    suspend fun updateMemberFractionMaxCAS(
        batchId: String,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        fraction: Int,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch_member SET terminal_bits = :terminalBits, " +
            "max_fraction_milli = CASE WHEN :terminalBits = 1 " +
            "THEN 1000 ELSE MAX(max_fraction_milli, :fraction) END, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND terminal_bits = 0 AND operation_id = :operationId " +
            "AND (attempt_id IS NULL OR attempt_id = :attemptId)"
    )
    suspend fun markMemberTerminalCAS(
        batchId: String,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        terminalBits: Int,
        fraction: Int,
        nowMs: Long
    ): Int

    /** 保留旧调用名，供既有恢复代码和测试使用 */
    @Query(
            "UPDATE download_batch_member SET terminal_bits = 1, " +
            "max_fraction_milli = 1000, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND terminal_bits = 0 AND operation_id = :operationId " +
            "AND ((attempt_id IS NULL AND :attemptId IS NULL) OR attempt_id = :attemptId)"
    )
    suspend fun markMemberCompletedCAS(
        batchId: String,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch_member SET terminal_bits = :terminalBits, " +
            "max_fraction_milli = CASE WHEN :terminalBits = 1 " +
            "THEN 1000 ELSE MAX(max_fraction_milli, :fraction) END, " +
            "initially_completed = 1, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND terminal_bits = 0 AND operation_id IS NULL"
    )
    suspend fun markInitialMemberTerminalCAS(
        batchId: String,
        stableKey: String,
        terminalBits: Int,
        fraction: Int,
        nowMs: Long
    ): Int

    @Query(
        "SELECT COUNT(*) FROM download_batch_member " +
            "WHERE batch_id = :batchId AND terminal_bits != 0"
    )
    suspend fun countTerminals(batchId: String): Int

    @Query("SELECT COUNT(*) FROM download_batch_member WHERE batch_id = :batchId")
    suspend fun countMembers(batchId: String): Int

    @Query("SELECT * FROM download_batch_member WHERE batch_id = :batchId ORDER BY ordinal ASC")
    suspend fun listMembers(batchId: String): List<DownloadBatchMemberEntity>

    /** 只取仍未终态的成员键，供网络等待展示和计数使用 */
    @Query(
        "SELECT stable_key FROM download_batch_member " +
            "WHERE batch_id = :batchId AND terminal_bits = 0 " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "ORDER BY ordinal ASC"
    )
    suspend fun listPendingStableKeysForOpenBatch(
        batchId: String,
        generation: Long
    ): List<String>

    @Query(
        "SELECT * FROM download_batch_member " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey LIMIT 1"
    )
    suspend fun findMember(batchId: String, stableKey: String): DownloadBatchMemberEntity?

    @Query(
        "SELECT * FROM download_batch_member " +
            "WHERE operation_id = :operationId ORDER BY batch_id, ordinal"
    )
    suspend fun findMembersByOperation(operationId: String): List<DownloadBatchMemberEntity>

    @Query(
        "SELECT * FROM download_batch_member " +
            "WHERE stable_key = :stableKey ORDER BY batch_id, ordinal"
    )
    suspend fun findMembersByStableKey(stableKey: String): List<DownloadBatchMemberEntity>

    @Query(
        "UPDATE download_batch_member SET terminal_bits = 0, " +
            "max_fraction_milli = 0, initially_completed = 0, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE batch_id = :batchId " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND terminal_bits = 1 AND initially_completed = 1"
    )
    suspend fun clearInitialMemberCompletionCAS(
        batchId: String,
        stableKey: String,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch_member SET terminal_bits = 0, " +
            "max_fraction_milli = MIN(max_fraction_milli, " +
            "$DOWNLOAD_BATCH_POST_CORE_PENDING_FRACTION_MILLI), " +
            "initially_completed = 0, updated_at_ms = :nowMs " +
            "WHERE operation_id IN (:operationIds) " +
            "AND terminal_bits = ${moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal.COMPLETED} " +
            "AND EXISTS (SELECT 1 FROM download_batch " +
            "WHERE download_batch.batch_id = download_batch_member.batch_id " +
            "AND download_batch.state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND download_batch.state_bits & ${DownloadBatchState.CANCELLED} = 0)"
    )
    suspend fun clearPrematurePostCoreCompletions(
        operationIds: List<String>,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = " +
            "(state_bits & ~(${DownloadBatchState.COMPLETED} | " +
            "${DownloadBatchState.NETWORK_WAIT} | ${DownloadBatchState.USER_MOBILE_ALLOWED})) " +
            "| ${DownloadBatchState.OPEN}, network_generation = NULL, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id IN (SELECT DISTINCT batch_id FROM download_batch_member " +
            "WHERE operation_id IN (:operationIds) AND terminal_bits = 0) " +
            "AND state_bits & ${DownloadBatchState.COMPLETED} != 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND state_bits & ${DownloadBatchState.CANCELLED} = 0"
    )
    suspend fun reopenBatchesForPostCoreOperations(
        operationIds: List<String>,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = :stateBits, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0"
    )
    suspend fun updateStateBitsCAS(
        batchId: String,
        generation: Long,
        stateBits: Int,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = " +
            "(state_bits | ${DownloadBatchState.NETWORK_WAIT}) & ~${DownloadBatchState.USER_MOBILE_ALLOWED}, " +
            "network_generation = :networkGeneration, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND ((:expectedNetworkGeneration IS NULL AND network_generation IS NULL) " +
            "OR network_generation = :expectedNetworkGeneration) " +
            "AND (network_generation IS NULL OR network_generation <= :networkGeneration)"
    )
    suspend fun markNetworkWaitingCAS(
        batchId: String,
        generation: Long,
        networkGeneration: Long,
        expectedNetworkGeneration: Long?,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch_member SET terminal_bits = ${moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal.CANCELLED}, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND terminal_bits = 0"
    )
    suspend fun markMembersCancelled(batchId: String, nowMs: Long): Int

    @Query(
        "UPDATE download_batch_member SET terminal_bits = ${moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal.CANCELLED}, " +
            "updated_at_ms = :nowMs WHERE batch_id = :batchId AND stable_key = :stableKey " +
            "AND terminal_bits = 0"
    )
    suspend fun markMemberCancelled(batchId: String, stableKey: String, nowMs: Long): Int

    @Query(
        "UPDATE download_batch_member SET terminal_bits = ${moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal.CANCELLED}, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id IN (SELECT batch_id FROM download_batch " +
            "WHERE state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0) " +
            "AND terminal_bits = 0"
    )
    suspend fun markMembersCancelledForAllOpenBatches(nowMs: Long): Int

    @Query(
        "UPDATE download_batch SET state_bits = state_bits & ~${DownloadBatchState.NETWORK_WAIT}, " +
            "network_generation = :networkGeneration, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.NETWORK_WAIT} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND ((:expectedNetworkGeneration IS NULL AND network_generation IS NULL) " +
            "OR network_generation = :expectedNetworkGeneration) " +
            "AND (network_generation IS NULL OR network_generation <= :networkGeneration)"
    )
    suspend fun clearNetworkWaitingCAS(
        batchId: String,
        generation: Long,
        networkGeneration: Long,
        expectedNetworkGeneration: Long?,
        nowMs: Long
    ): Int

    /**
     * 已确认 WIFI 后一次性解除旧等待和旧移动网络许可，代次条件阻止旧 WIFI
     * 回调清掉随后由移动网络回调写入的新状态
     */
    @Query(
        "UPDATE download_batch SET state_bits = state_bits & " +
            "~(${DownloadBatchState.NETWORK_WAIT} | ${DownloadBatchState.USER_MOBILE_ALLOWED}), " +
            "network_generation = :networkGeneration, updated_at_ms = :nowMs " +
            "WHERE state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & (${DownloadBatchState.NETWORK_WAIT} | " +
            "${DownloadBatchState.USER_MOBILE_ALLOWED}) != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND (network_generation IS NULL OR network_generation <= :networkGeneration)"
    )
    suspend fun clearAllOpenNetworkPolicyFencesAtOrBeforeGeneration(
        networkGeneration: Long,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = " +
            "(state_bits & ~${DownloadBatchState.NETWORK_WAIT}) | ${DownloadBatchState.USER_MOBILE_ALLOWED}, " +
            "network_generation = :networkGeneration, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.NETWORK_WAIT} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND network_generation = :expectedNetworkGeneration " +
            "AND :networkGeneration = :expectedNetworkGeneration"
    )
    suspend fun allowMobileDataCAS(
        batchId: String,
        generation: Long,
        expectedNetworkGeneration: Long,
        networkGeneration: Long,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = " +
            "(state_bits & ~1) | 8, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0 " +
            "AND (SELECT COUNT(*) FROM download_batch_member " +
            "WHERE batch_id = :batchId) = total_count " +
            "AND NOT EXISTS (SELECT 1 FROM download_batch_member " +
            "WHERE batch_id = :batchId AND terminal_bits = 0)"
    )
    suspend fun markCompletedIfAllMembersTerminal(
        batchId: String,
        generation: Long,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = (state_bits & ~1) | 16, " +
            "updated_at_ms = :nowMs WHERE batch_id = :batchId " +
            "AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0"
    )
    suspend fun markCancelled(
        batchId: String,
        generation: Long,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = (state_bits & ~1) | 16, " +
            "updated_at_ms = :nowMs WHERE state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND state_bits & ${DownloadBatchState.CLEARING} = 0"
    )
    suspend fun markAllOpenBatchesCancelled(nowMs: Long): Int

    @Query(
        "UPDATE download_batch SET state_bits = state_bits | ${DownloadBatchState.CLEARING}, " +
            "clear_epoch = MAX(clear_epoch, :clearEpoch), updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0 " +
            "AND ((state_bits & ${DownloadBatchState.OPEN} != 0 " +
            "AND clear_epoch < :clearEpoch) " +
            "OR (state_bits & ${DownloadBatchState.CLEARING} != 0 " +
            "AND clear_epoch <= :clearEpoch))"
    )
    suspend fun markBatchClearingCAS(
        batchId: String,
        generation: Long,
        clearEpoch: Long,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = " +
            "(state_bits & ~(${DownloadBatchState.OPEN} | " +
            "${DownloadBatchState.CLEARING} | ${DownloadBatchState.NETWORK_WAIT} | " +
            "${DownloadBatchState.USER_MOBILE_ALLOWED})) | ${DownloadBatchState.CANCELLED}, " +
            "updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & ${DownloadBatchState.CLEARING} != 0 " +
            "AND state_bits & ${DownloadBatchState.TERMINAL_MASK} = 0"
    )
    suspend fun finalizeBatchClearingCAS(
        batchId: String,
        generation: Long,
        nowMs: Long
    ): Int
}
