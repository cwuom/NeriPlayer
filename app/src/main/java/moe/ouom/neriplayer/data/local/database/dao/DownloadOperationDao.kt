package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationIdentityRow

@Dao
internal interface DownloadOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: DownloadOperationEntity)

    @Query("SELECT * FROM download_operation WHERE operation_id = :operationId LIMIT 1")
    suspend fun find(operationId: String): DownloadOperationEntity?

    @Query("SELECT * FROM download_operation WHERE operation_id IN (:operationIds)")
    suspend fun findAllByOperationIds(operationIds: List<String>): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey AND state IN (:states) " +
            "AND stop_requested_by_user = 0 " +
            "ORDER BY updated_at_ms DESC, operation_id ASC LIMIT 1"
    )
    suspend fun findLatestByStableKey(
        libraryId: String,
        stableKey: String,
        states: List<String>
    ): DownloadOperationEntity?

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey AND state IN (:states) " +
            "ORDER BY updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllByStableKey(
        libraryId: String,
        stableKey: String,
        states: List<String>
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND stable_key IN (:stableKeys) AND state IN (:states) " +
            "ORDER BY stable_key ASC, updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllByStableKeys(
        libraryId: String,
        stableKeys: List<String>,
        states: List<String>
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE stable_key IN (:stableKeys) AND state IN (:states) " +
            "ORDER BY stable_key ASC, updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllByStableKeysAnyLibrary(
        stableKeys: List<String>,
        states: List<String>
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT operation_id FROM download_operation " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey AND state IN (:states) " +
            "AND stop_requested_by_user = 0 " +
            "ORDER BY updated_at_ms DESC, operation_id ASC LIMIT 1"
    )
    suspend fun findLatestOperationIdByStableKey(
        libraryId: String,
        stableKey: String,
        states: List<String>
    ): String?

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE stable_key = :stableKey AND state IN (:states) " +
            "ORDER BY updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllByStableKeyAnyLibrary(
        stableKey: String,
        states: List<String>
    ): List<DownloadOperationEntity>

    @Query("SELECT * FROM download_operation WHERE state = :state")
    suspend fun findByState(state: String): List<DownloadOperationEntity>

    @Query("SELECT * FROM download_operation WHERE state IN (:states)")
    suspend fun findByStates(states: List<String>): List<DownloadOperationEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM download_operation WHERE state IN (:states))")
    fun hasAnyByStates(states: List<String>): Boolean

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND state IN (:states)"
    )
    suspend fun findByStatesInLibrary(
        libraryId: String,
        states: List<String>
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND state IN (:states) " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun findByStatesInLibraryPage(
        libraryId: String,
        states: List<String>,
        limit: Int,
        offset: Int
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE state IN (:states) " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun findByStatesPage(
        states: List<String>,
        limit: Int,
        offset: Int
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation WHERE state IN (:states) " +
            "AND (" +
            "(state IN ('PENDING_QUEUE', 'QUEUED', 'WAITING_STORAGE_MUTATION', " +
            "'RUNNING', 'RETRYABLE') AND stop_requested_by_user = 0) " +
            "OR state = 'STOPPED' " +
            "OR (state IN ('COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0)" +
            ") " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit"
    )
    suspend fun findCancellationCandidatesPage(
        states: List<String>,
        limit: Int
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT operation_id FROM download_operation " +
            "WHERE state = :state AND stable_key IN (:stableKeys)"
    )
    suspend fun findOperationIdsByStateAndStableKeys(
        state: String,
        stableKeys: List<String>
    ): List<String>

    @Query("SELECT operation_id FROM download_operation WHERE state = :state")
    suspend fun findOperationIdsByState(state: String): List<String>

    @Query(
        "SELECT operation_id FROM download_operation " +
            "WHERE state IN (:states) AND updated_at_ms < :cutoffMs " +
            "ORDER BY updated_at_ms ASC, operation_id ASC LIMIT :limit"
    )
    suspend fun findTerminalOperationIdsBefore(
        states: List<String>,
        cutoffMs: Long,
        limit: Int
    ): List<String>

    @Query("SELECT * FROM download_operation ORDER BY queue_order ASC, updated_at_ms ASC")
    suspend fun findAll(): List<DownloadOperationEntity>

    @Query(
        "SELECT operation_id, stable_key FROM download_operation " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun findAllOperationIdentitiesPage(
        limit: Int,
        offset: Int
    ): List<DownloadOperationIdentityRow>

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
            "WHERE operation_id = :operationId AND state IN (:expectedStates) " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun transitionState(
        operationId: String,
        expectedStates: List<String>,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'INVALID', updated_at_ms = :updatedAtMs, " +
            "last_error_code = 'INVALID_OPERATION_PAYLOAD' WHERE operation_id = :operationId " +
            "AND state IN (:expectedStates)"
    )
    suspend fun invalidateMalformedPayload(
        operationId: String,
        expectedStates: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = :state, " +
            "updated_at_ms = :updatedAtMs, last_error_code = :errorCode " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state IN (:expectedStates) AND stop_requested_by_user = 0"
    )
    suspend fun transitionStateForStableKey(
        operationId: String,
        stableKey: String,
        expectedStates: List<String>,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    ): Int

    @Query(
        "UPDATE download_operation SET library_id = :libraryId, state = 'QUEUED', " +
            "updated_at_ms = :updatedAtMs, last_error_code = NULL " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state = 'WAITING_STORAGE_MUTATION' " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun promoteWaitingStorageMutation(
        operationId: String,
        libraryId: String,
        stableKey: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET library_id = :libraryId, " +
            "updated_at_ms = :updatedAtMs, " +
            "last_error_code = CASE WHEN last_error_code = 'ROOT_CHANGED' " +
            "THEN NULL ELSE last_error_code END, " +
            "host_process_token = NULL, host_admitted_at_ms = NULL " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND library_id != :libraryId AND state IN (:states) " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun rehomeOperationLibrary(
        operationId: String,
        stableKey: String,
        libraryId: String,
        states: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET library_id = :libraryId, " +
            "updated_at_ms = :updatedAtMs, " +
            "last_error_code = CASE WHEN last_error_code = 'ROOT_CHANGED' " +
            "THEN NULL ELSE last_error_code END, " +
            "host_process_token = NULL, host_admitted_at_ms = NULL " +
            "WHERE library_id != :libraryId AND state IN (:states) " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun rehomeOperationsLibrary(
        libraryId: String,
        states: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET source_hint_json = :sourceHintJson, " +
            "updated_at_ms = :updatedAtMs WHERE operation_id = :operationId " +
            "AND stable_key = :stableKey"
    )
    suspend fun updateRequestPayload(
        operationId: String,
        stableKey: String,
        sourceHintJson: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET source_hint_json = :sourceHintJson, " +
            "bytes_written = 0, total_bytes = NULL, resume_json = NULL, retry_count = 0, " +
            "next_retry_at_ms = NULL, last_error_code = NULL, updated_at_ms = :updatedAtMs " +
            "WHERE operation_id = :operationId AND library_id = :libraryId " +
            "AND stable_key = :stableKey AND state IN (:expectedStates) " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun replaceMalformedReusablePayload(
        operationId: String,
        libraryId: String,
        stableKey: String,
        expectedStates: List<String>,
        sourceHintJson: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET bytes_written = :bytesWritten, " +
            "total_bytes = :totalBytes WHERE operation_id = :operationId " +
            "AND library_id = :libraryId AND stable_key = :stableKey " +
            "AND state IN (:expectedStates) AND stop_requested_by_user = 0"
    )
    suspend fun updateProgressCheckpoint(
        operationId: String,
        libraryId: String,
        stableKey: String,
        bytesWritten: Long,
        totalBytes: Long?,
        expectedStates: List<String>
    ): Int

    @Query(
        "UPDATE download_operation SET bytes_written = :bytesWritten, " +
            "total_bytes = :totalBytes WHERE operation_id = :operationId " +
            "AND stable_key = :stableKey AND state IN (:expectedStates) " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun updateProgressCheckpointAnyLibrary(
        operationId: String,
        stableKey: String,
        bytesWritten: Long,
        totalBytes: Long?,
        expectedStates: List<String>
    ): Int

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 1, " +
            "updated_at_ms = :updatedAtMs WHERE operation_id = :operationId"
    )
    suspend fun requestUserStop(operationId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 1, " +
            "updated_at_ms = :updatedAtMs, last_error_code = 'USER_CANCELLED' " +
            "WHERE operation_id = :operationId AND state IN (:expectedStates)"
    )
    suspend fun requestCommitBoundaryStop(
        operationId: String,
        expectedStates: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'CANCEL_REQUESTED', " +
            "updated_at_ms = :updatedAtMs, last_error_code = 'USER_CANCELLED' " +
            "WHERE operation_id = :operationId AND state = 'STOPPED'"
    )
    suspend fun requestStoppedCancellation(operationId: String, updatedAtMs: Long): Int

    @Query(
        "UPDATE download_operation SET state = 'CANCEL_REQUESTED', " +
            "updated_at_ms = :updatedAtMs, last_error_code = 'USER_CANCELLED' " +
            "WHERE operation_id IN (:operationIds) AND (" +
            "(state IN ('PENDING_QUEUE', 'QUEUED', 'WAITING_STORAGE_MUTATION', " +
            "'RUNNING', 'RETRYABLE') " +
            "AND stop_requested_by_user = 0) OR state = 'STOPPED')"
    )
    suspend fun requestCancellations(operationIds: List<String>, updatedAtMs: Long): Int

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 1, " +
            "updated_at_ms = :updatedAtMs, last_error_code = 'USER_CANCELLED' " +
            "WHERE operation_id IN (:operationIds) " +
            "AND state IN ('COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0"
    )
    suspend fun requestCommitBoundaryCancellations(
        operationIds: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'CANCELLED', " +
            "updated_at_ms = :updatedAtMs WHERE operation_id IN (:operationIds) " +
            "AND state = 'CANCEL_REQUESTED' AND stop_requested_by_user = 0"
    )
    suspend fun finalizeRequestedCancellations(
        operationIds: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "SELECT stop_requested_by_user FROM download_operation " +
            "WHERE operation_id = :operationId LIMIT 1"
    )
    suspend fun isUserStopped(operationId: String): Boolean?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM download_operation " +
            "WHERE operation_id = :operationId AND stop_requested_by_user = 1 " +
            "AND last_error_code = 'USER_CANCELLED')"
    )
    suspend fun isUserCancellationRequested(operationId: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM download_operation " +
            "WHERE operation_id = :operationId AND library_id = :libraryId " +
            "AND stable_key = :stableKey " +
            "AND state IN ('RUNNING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0 " +
            "AND NOT EXISTS(SELECT 1 FROM download_operation competitor " +
            "WHERE competitor.library_id = :libraryId " +
            "AND competitor.stable_key = :stableKey " +
            "AND competitor.operation_id != :operationId " +
            "AND competitor.stop_requested_by_user = 0 " +
            "AND competitor.state IN ('PENDING_QUEUE', 'QUEUED', 'RETRYABLE', " +
            "'RUNNING', 'COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING')))"
    )
    suspend fun isExecutionOwned(
        operationId: String,
        libraryId: String,
        stableKey: String
    ): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM download_operation " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state IN ('RUNNING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0 " +
            "AND NOT EXISTS(SELECT 1 FROM download_operation competitor " +
            "WHERE competitor.stable_key = :stableKey " +
            "AND competitor.operation_id != :operationId " +
            "AND competitor.stop_requested_by_user = 0 " +
            "AND competitor.state IN ('PENDING_QUEUE', 'QUEUED', 'RETRYABLE', " +
            "'RUNNING', 'COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING')))"
    )
    suspend fun isExecutionOwnedAnyLibrary(
        operationId: String,
        stableKey: String
    ): Boolean

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 0, " +
            "last_error_code = CASE WHEN last_error_code = 'USER_CANCELLED' " +
            "THEN NULL ELSE last_error_code END, " +
            "updated_at_ms = :updatedAtMs WHERE library_id = :libraryId " +
            "AND stable_key IN (:stableKeys)"
    )
    suspend fun clearUserStopForStableKeys(
        libraryId: String,
        stableKeys: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 0, " +
            "last_error_code = CASE WHEN last_error_code = 'USER_CANCELLED' " +
            "THEN NULL ELSE last_error_code END, " +
            "updated_at_ms = :updatedAtMs WHERE stable_key IN (:stableKeys)"
    )
    suspend fun clearUserStopForStableKeysAnyLibrary(
        stableKeys: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'RETRYABLE', " +
            "stop_requested_by_user = 0, updated_at_ms = :updatedAtMs, " +
            "last_error_code = 'EXPLICIT_RESUME_PENDING' WHERE operation_id = :operationId " +
            "AND stable_key = :stableKey AND state IN (:expectedStates) " +
            "AND (last_error_code IS NULL OR last_error_code != 'USER_CANCELLED')"
    )
    suspend fun prepareExplicitResume(
        operationId: String,
        stableKey: String,
        expectedStates: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM download_operation " +
            "WHERE operation_id = :operationId " +
            "AND last_error_code = 'EXPLICIT_RESUME_PENDING')"
    )
    suspend fun isExplicitResumePending(operationId: String): Boolean

    @Query(
        "UPDATE download_operation SET state = 'STOPPED', " +
            "stop_requested_by_user = 1, updated_at_ms = :updatedAtMs, " +
            "last_error_code = :errorCode WHERE operation_id = :operationId " +
            "AND stable_key = :stableKey AND state IN (:expectedStates)"
    )
    suspend fun restoreExplicitStop(
        operationId: String,
        stableKey: String,
        expectedStates: List<String>,
        updatedAtMs: Long,
        errorCode: String
    ): Int

    @Query("SELECT * FROM download_operation WHERE stop_requested_by_user = 1")
    suspend fun findUserStopped(): List<DownloadOperationEntity>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND stop_requested_by_user = 1"
    )
    suspend fun findUserStoppedInLibrary(libraryId: String): List<DownloadOperationEntity>

    @Query(
        "UPDATE download_operation SET state = 'CORE_COMMITTED', " +
            "updated_at_ms = :updatedAtMs, " +
            "last_error_code = CASE WHEN stop_requested_by_user = 1 " +
            "AND last_error_code = 'USER_CANCELLED' THEN last_error_code ELSE NULL END " +
            "WHERE operation_id = :operationId " +
            "AND state IN (:expectedStates)"
    )
    suspend fun markCoreCommitted(
        operationId: String,
        expectedStates: List<String>,
        updatedAtMs: Long
    ): Int

    @Query("DELETE FROM download_operation WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)

    @Query("DELETE FROM download_operation WHERE operation_id IN (:operationIds)")
    suspend fun deleteOperations(operationIds: List<String>): Int

    @Query(
        "SELECT operation_id FROM download_operation WHERE operation_id IN (:operationIds) " +
            "AND updated_at_ms <= :cancelledAtMs AND state IN (" +
            "'PENDING_QUEUE', 'QUEUED', 'WAITING_STORAGE_MUTATION', 'RETRYABLE', 'STOPPED', " +
            "'CANCEL_REQUESTED', 'CANCELLED', 'INVALID', 'DEGRADED_COMPLETE')"
    )
    suspend fun findClearedOperationIds(
        operationIds: List<String>,
        cancelledAtMs: Long
    ): List<String>

    @Query(
        "SELECT COUNT(*) FROM download_operation " +
            "WHERE host_process_token = :processToken"
    )
    suspend fun countHostAdmissions(processToken: String): Int

    @Query(
        "UPDATE download_operation SET host_process_token = :processToken, " +
            "host_admitted_at_ms = :admittedAtMs WHERE operation_id = :operationId " +
            "AND host_process_token IS NULL"
    )
    suspend fun setHostAdmission(
        operationId: String,
        processToken: String,
        admittedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET host_process_token = NULL, " +
            "host_admitted_at_ms = NULL WHERE operation_id = :operationId " +
            "AND host_process_token IS NOT NULL"
    )
    suspend fun deleteHostAdmission(operationId: String): Int

    @Query(
        "UPDATE download_operation SET host_process_token = NULL, " +
            "host_admitted_at_ms = NULL WHERE operation_id IN (:operationIds) " +
            "AND host_process_token IS NOT NULL"
    )
    suspend fun deleteHostAdmissions(operationIds: List<String>): Int

    @Query(
        "UPDATE download_operation SET host_process_token = NULL, " +
            "host_admitted_at_ms = NULL WHERE host_process_token IS NOT NULL " +
            "AND host_process_token != :processToken"
    )
    suspend fun deleteHostAdmissionsFromOtherProcesses(processToken: String): Int

    @Query(
        "UPDATE download_operation SET host_process_token = NULL, " +
            "host_admitted_at_ms = NULL WHERE host_process_token = :processToken " +
            "AND host_admitted_at_ms < :cutoffMs AND state IN (:states)"
    )
    suspend fun deleteExpiredHostAdmissions(
        processToken: String,
        cutoffMs: Long,
        states: List<String>
    ): Int
}
