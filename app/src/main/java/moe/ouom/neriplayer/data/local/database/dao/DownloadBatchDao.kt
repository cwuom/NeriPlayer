package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState

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
            "WHERE state_bits & 1 != 0 AND state_bits & 24 = 0 " +
            "ORDER BY updated_at_ms ASC, generation ASC"
    )
    suspend fun findOpenBatches(): List<DownloadBatchEntity>

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
            "AND state_bits & 1 != 0 AND state_bits & 24 = 0) " +
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
            "WHERE batch_id = :batchId AND state_bits & 1 != 0 AND state_bits & 24 = 0) " +
            "AND terminal_bits = 0 AND operation_id = :operationId " +
            "AND ((attempt_id IS NULL AND :attemptId IS NULL) OR attempt_id = :attemptId)"
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
            "WHERE batch_id = :batchId AND state_bits & 1 != 0 AND state_bits & 24 = 0) " +
            "AND terminal_bits = 0 AND operation_id = :operationId " +
            "AND ((attempt_id IS NULL AND :attemptId IS NULL) OR attempt_id = :attemptId)"
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
            "WHERE batch_id = :batchId AND state_bits & 1 != 0 AND state_bits & 24 = 0) " +
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
            "WHERE batch_id = :batchId AND state_bits & 1 != 0 AND state_bits & 24 = 0) " +
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
            "WHERE batch_id = :batchId AND state_bits & 1 != 0 AND state_bits & 24 = 0) " +
            "AND terminal_bits = 1 AND initially_completed = 1"
    )
    suspend fun clearInitialMemberCompletionCAS(
        batchId: String,
        stableKey: String,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = :stateBits, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & 1 != 0 AND state_bits & 24 = 0"
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
            "AND state_bits & 1 != 0 AND state_bits & 24 = 0 " +
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
            "WHERE state_bits & 1 != 0 AND state_bits & 24 = 0) AND terminal_bits = 0"
    )
    suspend fun markMembersCancelledForAllOpenBatches(nowMs: Long): Int

    @Query(
        "UPDATE download_batch SET state_bits = state_bits & ~${DownloadBatchState.NETWORK_WAIT}, " +
            "network_generation = :networkGeneration, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & 1 != 0 AND state_bits & ${DownloadBatchState.NETWORK_WAIT} != 0 " +
            "AND state_bits & 24 = 0 " +
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

    @Query(
        "UPDATE download_batch SET state_bits = " +
            "(state_bits & ~${DownloadBatchState.NETWORK_WAIT}) | ${DownloadBatchState.USER_MOBILE_ALLOWED}, " +
            "network_generation = :networkGeneration, updated_at_ms = :nowMs " +
            "WHERE batch_id = :batchId AND generation = :generation " +
            "AND state_bits & 1 != 0 AND state_bits & ${DownloadBatchState.NETWORK_WAIT} != 0 " +
            "AND state_bits & 24 = 0 AND network_generation = :expectedNetworkGeneration " +
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
            "AND state_bits & 1 != 0 AND state_bits & 24 = 0 " +
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
            "AND generation = :generation AND state_bits & 1 != 0 AND state_bits & 24 = 0"
    )
    suspend fun markCancelled(
        batchId: String,
        generation: Long,
        nowMs: Long
    ): Int

    @Query(
        "UPDATE download_batch SET state_bits = (state_bits & ~1) | 16, " +
            "updated_at_ms = :nowMs WHERE state_bits & 1 != 0 AND state_bits & 24 = 0"
    )
    suspend fun markAllOpenBatchesCancelled(nowMs: Long): Int
}
