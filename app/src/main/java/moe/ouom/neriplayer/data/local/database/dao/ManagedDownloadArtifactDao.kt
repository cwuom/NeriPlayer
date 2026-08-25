package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity

@Dao
internal interface ManagedDownloadArtifactDao {
    @Query(
        "SELECT * FROM managed_library_item " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey LIMIT 1"
    )
    suspend fun find(rootKey: String, stableKey: String): ManagedLibraryItemEntity?

    @Query(
        "SELECT * FROM managed_library_item " +
            "WHERE library_id = :rootKey"
    )
    suspend fun findAllByRootKey(rootKey: String): List<ManagedLibraryItemEntity>

    @Query(
        "SELECT * FROM managed_library_item " +
            "WHERE library_id = :rootKey AND state = :state " +
            "ORDER BY updated_at_ms ASC"
    )
    suspend fun findByState(
        rootKey: String,
        state: String
    ): List<ManagedLibraryItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ManagedLibraryItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsentAll(entities: List<ManagedLibraryItemEntity>): List<Long>

    @Query(
        "UPDATE managed_library_item SET " +
            "state = :state, lease_id = :leaseId, updated_at_ms = :updatedAtMs, " +
            "needs_reconcile = 1, last_error_code = NULL " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey " +
            "AND state = :expectedState " +
            "AND updated_at_ms = :expectedUpdatedAtMs"
    )
    suspend fun tryAcquire(
        rootKey: String,
        stableKey: String,
        expectedState: String,
        expectedUpdatedAtMs: Long,
        state: String,
        leaseId: String,
        updatedAtMs: Long
    ): Int

    @Query(
        "UPDATE managed_library_item SET " +
            "state = :repairState, lease_id = NULL, updated_at_ms = :updatedAtMs, " +
            "needs_reconcile = 1, last_error_code = :errorCode " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey " +
            "AND state = :expectedState " +
            "AND updated_at_ms = :expectedUpdatedAtMs"
    )
    suspend fun markRepairRequiredIfUnchanged(
        rootKey: String,
        stableKey: String,
        expectedState: String,
        expectedUpdatedAtMs: Long,
        repairState: String,
        updatedAtMs: Long,
        errorCode: String
    ): Int

    @Query(
        "UPDATE managed_library_item SET " +
            "state = :missingState, lease_id = NULL, updated_at_ms = :updatedAtMs, " +
            "needs_reconcile = 1, last_error_code = :errorCode " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey " +
            "AND state = :expectedState " +
            "AND updated_at_ms = :expectedUpdatedAtMs"
    )
    suspend fun markMissingIfUnchanged(
        rootKey: String,
        stableKey: String,
        expectedState: String,
        expectedUpdatedAtMs: Long,
        missingState: String,
        updatedAtMs: Long,
        errorCode: String
    ): Int

    @Query(
        "UPDATE managed_library_item SET " +
            "state = :state, updated_at_ms = :updatedAtMs, " +
            "needs_reconcile = :needsReconcile, last_error_code = :errorCode " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey " +
            "AND lease_id IS NULL AND state = :expectedState " +
            "AND updated_at_ms = :expectedUpdatedAtMs"
    )
    suspend fun updateLeaseFreeIfUnchanged(
        rootKey: String,
        stableKey: String,
        expectedState: String,
        expectedUpdatedAtMs: Long,
        state: String,
        updatedAtMs: Long,
        needsReconcile: Boolean,
        errorCode: String?
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ManagedLibraryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ManagedLibraryItemEntity>)

    @Query(
        "DELETE FROM managed_library_item " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey"
    )
    suspend fun delete(rootKey: String, stableKey: String)

    @Query(
        "DELETE FROM managed_library_item " +
            "WHERE library_id = :rootKey AND stable_key = :stableKey " +
            "AND state = :expectedState AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND ((lease_id IS NULL AND :expectedLeaseId IS NULL) " +
            "OR lease_id = :expectedLeaseId)"
    )
    suspend fun deleteIfUnchanged(
        rootKey: String,
        stableKey: String,
        expectedState: String,
        expectedLeaseId: String?,
        expectedUpdatedAtMs: Long
    ): Int
}
