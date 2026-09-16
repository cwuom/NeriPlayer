package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.DownloadCancellationIdentityRow
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationIdentityRow
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationNetworkPolicyRow

@Dao
internal interface DownloadOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: DownloadOperationEntity)

    @Query("SELECT * FROM download_operation WHERE operation_id = :operationId LIMIT 1")
    suspend fun find(operationId: String): DownloadOperationEntity?

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE operation_id = :operationId LIMIT 1"
    )
    suspend fun findHeader(operationId: String): DownloadOperationHeaderRow?

    @Query(
        "SELECT substr(source_hint_json, :startOffset, :chunkLength) " +
            "FROM download_operation WHERE operation_id = :operationId " +
            "AND updated_at_ms = :updatedAtMs LIMIT 1"
    )
    suspend fun findSourceHintJsonChunk(
        operationId: String,
        startOffset: Int,
        chunkLength: Int,
        updatedAtMs: Long
    ): String?

    @Query(
        "SELECT length(source_hint_json) FROM download_operation " +
            "WHERE operation_id = :operationId AND updated_at_ms = :updatedAtMs " +
            "LIMIT 1"
    )
    suspend fun findSourceHintJsonLength(
        operationId: String,
        updatedAtMs: Long
    ): Int?

    @Query(
        "SELECT substr(resume_json, :startOffset, :chunkLength) " +
            "FROM download_operation WHERE operation_id = :operationId " +
            "AND updated_at_ms = :updatedAtMs LIMIT 1"
    )
    suspend fun findResumeJsonChunk(
        operationId: String,
        startOffset: Int,
        chunkLength: Int,
        updatedAtMs: Long
    ): String?

    @Query(
        "SELECT length(resume_json) FROM download_operation " +
            "WHERE operation_id = :operationId AND updated_at_ms = :updatedAtMs " +
            "LIMIT 1"
    )
    suspend fun findResumeJsonLength(
        operationId: String,
        updatedAtMs: Long
    ): Int?

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey " +
            "AND state IN (:states) " +
            "ORDER BY updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllHeadersByStableKey(
        libraryId: String,
        stableKey: String,
        states: List<String>
    ): List<DownloadOperationHeaderRow>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE stable_key = :stableKey " +
            "AND state IN (:states) " +
            "ORDER BY updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllHeadersByStableKeyAnyLibrary(
        stableKey: String,
        states: List<String>
    ): List<DownloadOperationHeaderRow>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE operation_id IN (:operationIds)"
    )
    suspend fun findAllHeadersByOperationIds(
        operationIds: List<String>
    ): List<DownloadOperationHeaderRow>

    /**
     * 旧 payload 没有 requiresWifiNetwork 时与解码器一致，按仅 WIFI 保守处理
     * JSONObject.toString() 不会在冒号后插空格；同时匹配根字段两侧逗号，
     * 避免歌曲文本里出现同名片段时误放行移动网络
     */
    @Query(
        "SELECT operation_id, stable_key, " +
            "CASE WHEN instr(source_hint_json, ',\"requiresWifiNetwork\":false,') > 0 " +
            "THEN 0 ELSE 1 END AS requires_wifi_network " +
            "FROM download_operation WHERE operation_id IN (:operationIds)"
    )
    suspend fun findNetworkPoliciesByOperationIds(
        operationIds: List<String>
    ): List<DownloadOperationNetworkPolicyRow>

    @Query(
        "UPDATE download_operation SET library_id = :libraryId, state = 'QUEUED', " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = NULL " +
            "WHERE operation_id IN (:operationIds) " +
            "AND state = 'WAITING_STORAGE_MUTATION' " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun promoteWaitingStorageMutations(
        operationIds: List<String>,
        libraryId: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation " +
            "WHERE library_id = :libraryId AND stable_key IN (:stableKeys) " +
            "AND state IN (:states) " +
            "ORDER BY stable_key ASC, updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllHeadersByStableKeys(
        libraryId: String,
        stableKeys: List<String>,
        states: List<String>
    ): List<DownloadOperationHeaderRow>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE stable_key IN (:stableKeys) " +
            "AND state IN (:states) " +
            "ORDER BY stable_key ASC, updated_at_ms DESC, created_at_ms DESC, operation_id ASC"
    )
    suspend fun findAllHeadersByStableKeysAnyLibrary(
        stableKeys: List<String>,
        states: List<String>
    ): List<DownloadOperationHeaderRow>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation " +
            "WHERE library_id = :libraryId AND state IN (:states) " +
            "AND operation_id > :afterOperationId " +
            "ORDER BY operation_id ASC LIMIT :limit"
    )
    suspend fun findByStatesInLibraryAfterOperationIdHeaders(
        libraryId: String,
        states: List<String>,
        afterOperationId: String,
        limit: Int
    ): List<DownloadOperationHeaderRow>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE state IN (:states) " +
            "AND operation_id > :afterOperationId " +
            "ORDER BY operation_id ASC LIMIT :limit"
    )
    suspend fun findByStatesAfterOperationIdHeaders(
        states: List<String>,
        afterOperationId: String,
        limit: Int
    ): List<DownloadOperationHeaderRow>

    @Query("SELECT state FROM download_operation WHERE operation_id = :operationId LIMIT 1")
    suspend fun findState(operationId: String): String?

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
        "SELECT COUNT(*) FROM download_operation " +
            "WHERE library_id = :libraryId AND state IN (:states)"
    )
    suspend fun countByStatesInLibrary(
        libraryId: String,
        states: List<String>
    ): Int

    @Query(
        "SELECT MAX(queue_order) FROM download_operation " +
            "WHERE library_id = :libraryId AND state IN (:states)"
    )
    suspend fun findMaxQueueOrderByStates(
        libraryId: String,
        states: List<String>
    ): Int?

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

    /** 用不可变 operation_id 做游标，避免更新 updated_at_ms 时分页漂移 */
    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND state IN (:states) " +
            "AND operation_id > :afterOperationId " +
            "ORDER BY operation_id ASC LIMIT :limit"
    )
    suspend fun findByStatesInLibraryAfterOperationId(
        libraryId: String,
        states: List<String>,
        afterOperationId: String,
        limit: Int
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
        "SELECT * FROM download_operation " +
            "WHERE state IN (:states) AND stop_requested_by_user = 0 " +
            "AND (next_retry_at_ms IS NULL OR next_retry_at_ms <= :nowMs) " +
            "AND ((batch_id IS NULL AND batch_generation IS NULL) OR EXISTS (" +
            "SELECT 1 FROM download_batch batch WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & 1) != 0 AND (batch.state_bits & 2) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) = 0)) " +
            "AND (:afterQueueOrder IS NULL " +
            "OR queue_order > :afterQueueOrder " +
            "OR (queue_order = :afterQueueOrder AND updated_at_ms > :afterUpdatedAtMs) " +
            "OR (queue_order = :afterQueueOrder AND updated_at_ms = :afterUpdatedAtMs " +
            "AND operation_id > :afterOperationId)) " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit"
    )
    suspend fun findSchedulableForPumpAfterCursor(
        states: List<String>,
        afterQueueOrder: Int?,
        afterUpdatedAtMs: Long?,
        afterOperationId: String?,
        limit: Int,
        nowMs: Long
    ): List<DownloadOperationEntity>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE state IN (:states) " +
            "AND stop_requested_by_user = 0 " +
            "AND (next_retry_at_ms IS NULL OR next_retry_at_ms <= :nowMs) " +
            "AND ((batch_id IS NULL AND batch_generation IS NULL) OR EXISTS (" +
            "SELECT 1 FROM download_batch batch WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & 1) != 0 AND (batch.state_bits & 2) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) = 0)) " +
            "AND (:afterQueueOrder IS NULL OR queue_order > :afterQueueOrder OR " +
            "(queue_order = :afterQueueOrder AND updated_at_ms > :afterUpdatedAtMs) OR " +
            "(queue_order = :afterQueueOrder AND updated_at_ms = :afterUpdatedAtMs " +
            "AND operation_id > :afterOperationId)) " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit"
    )
    suspend fun findSchedulableForPumpAfterCursorHeaders(
        states: List<String>,
        afterQueueOrder: Int?,
        afterUpdatedAtMs: Long?,
        afterOperationId: String?,
        limit: Int,
        nowMs: Long
    ): List<DownloadOperationHeaderRow>

    /** 只有没有 ready 行时才读取此值，避免把正常 pump 变成第二次全量查询 */
    @Query(
        "SELECT MIN(next_retry_at_ms) FROM download_operation " +
            "WHERE state IN (:states) AND stop_requested_by_user = 0 " +
            "AND next_retry_at_ms > :nowMs"
    )
    suspend fun findEarliestFutureRetryDeadlineForPump(
        states: List<String>,
        nowMs: Long
    ): Long?

    /** 跨目录恢复也必须使用稳定游标，不能依赖会变化的更新时间排序 */
    @Query(
        "SELECT * FROM download_operation " +
            "WHERE state IN (:states) AND operation_id > :afterOperationId " +
            "ORDER BY operation_id ASC LIMIT :limit"
    )
    suspend fun findByStatesAfterOperationId(
        states: List<String>,
        afterOperationId: String,
        limit: Int
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
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE state IN (:states) AND (" +
            "(state IN ('PENDING_QUEUE', 'QUEUED', 'WAITING_STORAGE_MUTATION', " +
            "'RUNNING', 'RETRYABLE') AND stop_requested_by_user = 0) OR " +
            "state = 'STOPPED' OR " +
            "(state IN ('COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0)) " +
            "ORDER BY queue_order ASC, updated_at_ms ASC, operation_id ASC " +
            "LIMIT :limit"
    )
    suspend fun findCancellationCandidatesPageHeaders(
        states: List<String>,
        limit: Int
    ): List<DownloadOperationHeaderRow>

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
        "SELECT operation_id, stable_key FROM download_operation " +
            "WHERE operation_id > :afterOperationId " +
            "ORDER BY operation_id ASC LIMIT :limit"
    )
    suspend fun findAllOperationIdentitiesAfterOperationId(
        afterOperationId: String,
        limit: Int
    ): List<DownloadOperationIdentityRow>

    /** owner 快照必须包含快速阶段已标记的取消态和提交边界 stop 行 */
    @Query(
        "SELECT operation_id, stable_key, state, created_at_ms, " +
            "stop_requested_by_user FROM download_operation " +
            "WHERE state IN (:states) AND operation_id > :afterOperationId " +
            "ORDER BY operation_id ASC LIMIT :limit"
    )
    suspend fun findCancellationIdentitiesAfterOperationId(
        states: List<String>,
        afterOperationId: String,
        limit: Int
    ): List<DownloadCancellationIdentityRow>

    @Query(
        "UPDATE download_operation SET state = :state, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
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
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = :errorCode, " +
            "next_retry_at_ms = CASE WHEN :state IN ('RETRYABLE', " +
            "'ASSETS_ENRICHING', 'DEGRADED_COMPLETE') " +
            "THEN next_retry_at_ms ELSE NULL END, " +
            "retry_count = CASE WHEN :state IN ('CORE_COMMITTED', 'COMPLETED', 'FINALIZED', " +
            "'METADATA_ACTION_REQUIRED', 'CANCELLED', 'INVALID') THEN 0 ELSE retry_count END " +
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
        "UPDATE download_operation SET state = 'RETRYABLE', " +
            "retry_count = :retryCount, next_retry_at_ms = :nextRetryAtMs, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = :errorCode " +
            "WHERE operation_id = :operationId AND state IN (:expectedStates) " +
            "AND retry_count = :expectedRetryCount " +
            "AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun transitionToRetryable(
        operationId: String,
        expectedStates: List<String>,
        expectedRetryCount: Int,
        expectedUpdatedAtMs: Long,
        retryCount: Int,
        nextRetryAtMs: Long?,
        updatedAtMs: Long,
        errorCode: String?
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'DEGRADED_COMPLETE', " +
            "retry_count = :retryCount, next_retry_at_ms = :nextRetryAtMs, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = :errorCode " +
            "WHERE operation_id = :operationId AND state = :expectedState " +
            "AND retry_count = :expectedRetryCount " +
            "AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun recordPostCoreRetryFailure(
        operationId: String,
        expectedState: String,
        expectedRetryCount: Int,
        expectedUpdatedAtMs: Long,
        retryCount: Int,
        nextRetryAtMs: Long?,
        updatedAtMs: Long,
        errorCode: String
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'INVALID', retry_count = 0, " +
            "next_retry_at_ms = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = :errorCode " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state = 'DEGRADED_COMPLETE' AND retry_count >= :minimumRetryCount " +
            "AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun transitionPostCoreRetryExhausted(
        operationId: String,
        stableKey: String,
        minimumRetryCount: Int,
        expectedUpdatedAtMs: Long,
        updatedAtMs: Long,
        errorCode: String
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'INVALID', " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = 'INVALID_OPERATION_PAYLOAD' WHERE operation_id = :operationId " +
            "AND state IN (:expectedStates)"
    )
    suspend fun invalidateMalformedPayload(
        operationId: String,
        expectedStates: List<String>,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = 'INVALID', " +
            "updated_at_ms = MAX(updated_at_ms + 1, :invalidatedAtMs), " +
            "last_error_code = 'INVALID_OPERATION_PAYLOAD' WHERE operation_id = :operationId " +
            "AND state = :expectedState AND updated_at_ms = :expectedUpdatedAtMs"
    )
    suspend fun invalidateMalformedPayloadAtVersion(
        operationId: String,
        expectedState: String,
        expectedUpdatedAtMs: Long,
        invalidatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET state = :state, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = :errorCode, " +
            "next_retry_at_ms = CASE WHEN :state IN ('RETRYABLE', " +
            "'ASSETS_ENRICHING', 'DEGRADED_COMPLETE') " +
            "THEN next_retry_at_ms ELSE NULL END, " +
            "retry_count = CASE WHEN :state IN ('CORE_COMMITTED', 'COMPLETED', 'FINALIZED', " +
            "'METADATA_ACTION_REQUIRED', 'CANCELLED', 'INVALID') THEN 0 ELSE retry_count END " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state IN (:expectedStates) AND stop_requested_by_user = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM download_batch batch " +
            "WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) != 0)"
    )
    suspend fun transitionStateForStableKey(
        operationId: String,
        stableKey: String,
        expectedStates: List<String>,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    ): Int

    /** Direct-cache settlement must not overwrite a newer payload/attempt. */
    @Query(
        "UPDATE download_operation SET state = :state, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = :errorCode, " +
            "next_retry_at_ms = NULL, retry_count = 0 " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND state IN (:expectedStates) AND stop_requested_by_user = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM download_batch batch " +
            "WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) != 0)"
    )
    suspend fun transitionDirectCachedStateAtVersion(
        operationId: String,
        stableKey: String,
        expectedStates: List<String>,
        expectedUpdatedAtMs: Long,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    ): Int

    @Query(
        "UPDATE download_operation SET library_id = :libraryId, state = 'QUEUED', " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
            "last_error_code = NULL " +
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
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
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
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs), " +
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
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
            "WHERE operation_id = :operationId " +
            "AND stable_key = :stableKey"
    )
    suspend fun updateRequestPayload(
        operationId: String,
        stableKey: String,
        sourceHintJson: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET batch_id = :batchId, " +
            "batch_generation = :batchGeneration, source_hint_json = :sourceHintJson, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND ((batch_id IS NULL AND batch_generation IS NULL) " +
            "OR (batch_id = :batchId AND batch_generation = :batchGeneration)) " +
            "AND EXISTS (SELECT 1 FROM download_batch batch " +
            "WHERE batch.batch_id = :batchId AND batch.generation = :batchGeneration " +
            "AND (batch.state_bits & 1) != 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.TERMINAL_MASK}) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) = 0)"
    )
    suspend fun bindBatchIdentityIfUnbound(
        operationId: String,
        stableKey: String,
        batchId: String,
        batchGeneration: Long,
        sourceHintJson: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE download_operation SET source_hint_json = :sourceHintJson, " +
            "bytes_written = 0, total_bytes = NULL, resume_json = NULL, retry_count = 0, " +
            "next_retry_at_ms = NULL, last_error_code = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
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
        "UPDATE download_operation SET bytes_written = MAX(bytes_written, :bytesWritten), " +
            "total_bytes = CASE " +
            "WHEN :totalBytes IS NULL OR :totalBytes <= 0 THEN total_bytes " +
            "WHEN total_bytes IS NULL OR total_bytes <= 0 THEN :totalBytes " +
            "WHEN :totalBytes > total_bytes THEN :totalBytes ELSE total_bytes END " +
            "WHERE operation_id = :operationId " +
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
        "UPDATE download_operation SET bytes_written = MAX(bytes_written, :bytesWritten), " +
            "total_bytes = CASE " +
            "WHEN :totalBytes IS NULL OR :totalBytes <= 0 THEN total_bytes " +
            "WHEN total_bytes IS NULL OR total_bytes <= 0 THEN :totalBytes " +
            "WHEN :totalBytes > total_bytes THEN :totalBytes ELSE total_bytes END " +
            "WHERE operation_id = :operationId " +
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

    /** 进程被系统用户结束后，把尚未提交的 operation 重新交给共享下载泵 */
    @Query(
        "UPDATE download_operation SET state = 'RETRYABLE', " +
            "stop_requested_by_user = 0, last_error_code = 'PROCESS_EXIT_RECOVERY', " +
            "host_process_token = NULL, host_admitted_at_ms = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state IN ('PENDING_QUEUE', 'QUEUED', 'RUNNING', 'RETRYABLE') " +
            "AND stop_requested_by_user = 0"
    )
    suspend fun requeueAfterProcessExit(
        operationId: String,
        stableKey: String,
        updatedAtMs: Long
    ): Int

    /** 已跨过提交边界的 operation 只释放旧宿主租约，保留其持久状态 */
    @Query(
        "UPDATE download_operation SET host_process_token = NULL, " +
            "host_admitted_at_ms = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
            "WHERE operation_id = :operationId AND stable_key = :stableKey " +
            "AND state IN ('COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0"
    )
    suspend fun releaseAfterProcessExit(
        operationId: String,
        stableKey: String,
        updatedAtMs: Long
    ): Int

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

    /** 直接批量标记可取消 operation，不先把所有行加载到内存 */
    @Query(
        "UPDATE download_operation SET state = 'CANCEL_REQUESTED', " +
            "updated_at_ms = :updatedAtMs, last_error_code = 'USER_CANCELLED' " +
            "WHERE ((state IN ('PENDING_QUEUE', 'QUEUED', " +
            "'WAITING_STORAGE_MUTATION', 'RUNNING', 'RETRYABLE') " +
            "AND stop_requested_by_user = 0) OR state = 'STOPPED')"
    )
    suspend fun requestAllCancellationsFast(updatedAtMs: Long): Int

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

    /** 不读取请求 JSON 就记录跨过提交边界的取消 */
    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 1, " +
            "updated_at_ms = :updatedAtMs, last_error_code = 'USER_CANCELLED' " +
            "WHERE state IN ('COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 0"
    )
    suspend fun requestAllCommitBoundaryCancellationsFast(updatedAtMs: Long): Int

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
            "WHERE operation_id = :operationId AND (" +
            "state = 'CANCEL_REQUESTED' OR " +
            "(stop_requested_by_user = 1 AND last_error_code = 'USER_CANCELLED')))"
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
            "updated_at_ms = :updatedAtMs WHERE stable_key IN (:stableKeys) " +
            "AND (last_error_code IS NULL OR last_error_code != 'USER_CANCELLED')"
    )
    suspend fun clearUserStopForStableKeysAnyLibrary(
        stableKeys: List<String>,
        updatedAtMs: Long
    ): Int

    /** 用户明确发起新下载时，只解除可安全续跑的旧取消栅栏，已取消批次保留旧身份 */
    @Query(
        "UPDATE download_operation SET stop_requested_by_user = 0, " +
            "last_error_code = CASE WHEN last_error_code = 'USER_CANCELLED' " +
            "THEN NULL ELSE last_error_code END, " +
            "updated_at_ms = :updatedAtMs WHERE stable_key IN (:stableKeys) " +
            "AND state IN ('COMMITTING', 'CORE_COMMITTED', 'ASSETS_ENRICHING', " +
            "'DEGRADED_COMPLETE') AND stop_requested_by_user = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM download_batch_member AS member " +
            "WHERE member.operation_id = download_operation.operation_id AND (" +
            "member.terminal_bits = ${DownloadBatchMemberTerminal.CANCELLED} OR EXISTS (" +
            "SELECT 1 FROM download_batch AS batch WHERE batch.batch_id = member.batch_id " +
            "AND batch.state_bits & (${DownloadBatchState.CLEARING} | " +
            "${DownloadBatchState.CANCELLED}) != 0)))"
    )
    suspend fun clearUserStopForFreshStartAnyLibrary(
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
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation WHERE stop_requested_by_user = 1"
    )
    suspend fun findUserStoppedHeaders(): List<DownloadOperationHeaderRow>

    @Query(
        "SELECT * FROM download_operation " +
            "WHERE library_id = :libraryId AND stop_requested_by_user = 1"
    )
    suspend fun findUserStoppedInLibrary(libraryId: String): List<DownloadOperationEntity>

    @Query(
        "SELECT operation_id, stable_key, library_id, state, queue_order, " +
            "staging_dir_name, bytes_written, total_bytes, retry_count, " +
            "next_retry_at_ms, last_error_code, stop_requested_by_user, created_at_ms, " +
            "updated_at_ms, host_process_token, host_admitted_at_ms, batch_id, batch_generation " +
            "FROM download_operation " +
            "WHERE library_id = :libraryId AND stop_requested_by_user = 1"
    )
    suspend fun findUserStoppedInLibraryHeaders(
        libraryId: String
    ): List<DownloadOperationHeaderRow>

    @Query(
        "UPDATE download_operation SET state = 'CORE_COMMITTED', " +
            "updated_at_ms = :updatedAtMs, " +
            "retry_count = 0, next_retry_at_ms = NULL, " +
            "last_error_code = CASE WHEN stop_requested_by_user = 1 " +
            "AND last_error_code = 'USER_CANCELLED' THEN last_error_code ELSE NULL END " +
            "WHERE operation_id = :operationId " +
            "AND state IN (:expectedStates) " +
            "AND NOT EXISTS (SELECT 1 FROM download_batch batch " +
            "WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) != 0)"
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
        "UPDATE download_operation SET stop_requested_by_user = 1, " +
            "last_error_code = 'USER_CANCELLED', next_retry_at_ms = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :nowMs), " +
            "host_process_token = NULL, host_admitted_at_ms = NULL " +
            "WHERE operation_id IN (:operationIds) AND state IN (:states)"
    )
    suspend fun retainClearedArtifactRecoveryStops(
        operationIds: List<String>,
        states: List<String>,
        nowMs: Long
    ): Int

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
        "SELECT operation_id, stable_key FROM download_operation " +
            "WHERE state = 'RUNNING' AND stop_requested_by_user = 0 " +
            "AND (host_process_token IS NULL OR host_process_token != :processToken)"
    )
    suspend fun findOrphanedRunningOperationIdentities(
        processToken: String
    ): List<DownloadOperationIdentityRow>

    /** 新进程只接管没有当前进程宿主的传输态，提交态仍交给专用恢复链路 */
    @Query(
        "UPDATE download_operation SET state = 'RETRYABLE', " +
            "next_retry_at_ms = NULL, last_error_code = 'PROCESS_RESTART_RECOVERY', " +
            "host_process_token = NULL, host_admitted_at_ms = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
            "WHERE state = 'RUNNING' AND stop_requested_by_user = 0 " +
            "AND (host_process_token IS NULL OR host_process_token != :processToken)"
    )
    suspend fun requeueOrphanedRunningOperations(
        processToken: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "SELECT operation_id, stable_key FROM download_operation " +
            "WHERE state = 'RETRYABLE' AND stop_requested_by_user = 0 " +
            "AND (host_process_token IS NULL OR host_process_token != :processToken) " +
            "AND ((batch_id IS NULL AND batch_generation IS NULL) OR EXISTS (" +
            "SELECT 1 FROM download_batch batch WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & ${DownloadBatchState.OPEN}) != 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.NETWORK_WAIT}) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.TERMINAL_MASK}) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) = 0))"
    )
    suspend fun findRestartRearmableRetryOperationIdentities(
        processToken: String
    ): List<DownloadOperationIdentityRow>

    /** 新进程立即接管普通重试队列，但保留累计重试次数和最后失败原因 */
    @Query(
        "UPDATE download_operation SET state = 'QUEUED', next_retry_at_ms = NULL, " +
            "host_process_token = NULL, host_admitted_at_ms = NULL, " +
            "updated_at_ms = MAX(updated_at_ms + 1, :updatedAtMs) " +
            "WHERE state = 'RETRYABLE' AND stop_requested_by_user = 0 " +
            "AND (host_process_token IS NULL OR host_process_token != :processToken) " +
            "AND ((batch_id IS NULL AND batch_generation IS NULL) OR EXISTS (" +
            "SELECT 1 FROM download_batch batch WHERE batch.batch_id = download_operation.batch_id " +
            "AND batch.generation = download_operation.batch_generation " +
            "AND (batch.state_bits & ${DownloadBatchState.OPEN}) != 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.NETWORK_WAIT}) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.TERMINAL_MASK}) = 0 " +
            "AND (batch.state_bits & ${DownloadBatchState.CLEARING}) = 0))"
    )
    suspend fun rearmRetryableOperationsAfterProcessRestart(
        processToken: String,
        updatedAtMs: Long
    ): Int

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
