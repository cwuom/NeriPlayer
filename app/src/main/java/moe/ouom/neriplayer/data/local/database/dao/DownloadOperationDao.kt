package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity

@Dao
internal interface DownloadOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: DownloadOperationEntity)

    @Query("SELECT * FROM download_operation WHERE operation_id = :operationId LIMIT 1")
    suspend fun find(operationId: String): DownloadOperationEntity?

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE stable_key = :stableKey AND state IN (:states) " +
            "AND stop_requested_by_user = 0 " +
            "ORDER BY updated_at_ms DESC, operation_id ASC LIMIT 1"
    )
    suspend fun findLatestByStableKey(
        stableKey: String,
        states: List<String>
    ): DownloadOperationEntity?

    @Query(
        "SELECT operation_id FROM download_operation " +
            "WHERE stable_key = :stableKey AND state IN (:states) " +
            "AND stop_requested_by_user = 0 " +
            "ORDER BY updated_at_ms DESC, operation_id ASC LIMIT 1"
    )
    suspend fun findLatestOperationIdByStableKey(
        stableKey: String,
        states: List<String>
    ): String?

    @Query("SELECT * FROM download_operation WHERE state = :state")
    suspend fun findByState(state: String): List<DownloadOperationEntity>

    @Query("SELECT * FROM download_operation WHERE state IN (:states)")
    suspend fun findByStates(states: List<String>): List<DownloadOperationEntity>

    @Query(
        "DELETE FROM download_operation " +
            "WHERE state = :state AND stable_key IN (:stableKeys)"
    )
    suspend fun deleteByStateAndStableKeys(state: String, stableKeys: List<String>)

    @Query("DELETE FROM download_operation WHERE state = :state")
    suspend fun deleteByState(state: String)

    @Query(
        "DELETE FROM download_operation WHERE operation_id IN (" +
            "SELECT operation_id FROM download_operation " +
            "WHERE state IN (:states) AND updated_at_ms < :cutoffMs " +
            "ORDER BY updated_at_ms ASC, operation_id ASC LIMIT :limit)"
    )
    suspend fun deleteTerminalBefore(
        states: List<String>,
        cutoffMs: Long,
        limit: Int
    ): Int

    @Query("SELECT * FROM download_operation ORDER BY queue_order ASC, updated_at_ms ASC")
    suspend fun findAll(): List<DownloadOperationEntity>

    @Query(
        "UPDATE download_operation SET state = :state, updated_at_ms = :updatedAtMs, " +
            "last_error_code = :errorCode WHERE operation_id = :operationId"
    )
    suspend fun updateState(
        operationId: String,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    )

    @Query(
        "UPDATE download_operation SET state = :state, " +
            "updated_at_ms = :updatedAtMs, last_error_code = :errorCode " +
            "WHERE operation_id = :operationId AND state IN (:expectedStates)"
    )
    suspend fun transitionState(
        operationId: String,
        expectedStates: List<String>,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    ): Int

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 1, " +
            "updated_at_ms = :updatedAtMs WHERE operation_id = :operationId"
    )
    suspend fun requestUserStop(operationId: String, updatedAtMs: Long): Int

    @Query(
        "SELECT stop_requested_by_user FROM download_operation " +
            "WHERE operation_id = :operationId LIMIT 1"
    )
    suspend fun isUserStopped(operationId: String): Boolean?

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 0, " +
            "updated_at_ms = :updatedAtMs WHERE stable_key IN (:stableKeys)"
    )
    suspend fun clearUserStopForStableKeys(
        stableKeys: List<String>,
        updatedAtMs: Long
    ): Int

    @Query("SELECT * FROM download_operation WHERE stop_requested_by_user = 1")
    suspend fun findUserStopped(): List<DownloadOperationEntity>

    @Query(
        "UPDATE download_operation SET state = 'CORE_COMMITTED', " +
            "stop_requested_by_user = 0, updated_at_ms = :updatedAtMs, " +
            "last_error_code = NULL WHERE operation_id = :operationId " +
            "AND state IN (:expectedStates)"
    )
    suspend fun markCoreCommitted(
        operationId: String,
        expectedStates: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'QUEUED', " +
            "updated_at_ms = :updatedAtMs, last_error_code = NULL " +
            "WHERE operation_id = :operationId AND state IN ('CANCEL_REQUESTED', 'CANCELLED')"
    )
    suspend fun clearCancellation(operationId: String, updatedAtMs: Long): Int

    @Query("DELETE FROM download_operation WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)
}
