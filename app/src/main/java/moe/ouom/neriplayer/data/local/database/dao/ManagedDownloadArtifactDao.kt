package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity

@Dao
internal interface ManagedDownloadArtifactDao {
    @Query(
        "SELECT * FROM managed_download_artifact " +
            "WHERE root_key = :rootKey AND stable_key = :stableKey LIMIT 1"
    )
    suspend fun find(rootKey: String, stableKey: String): ManagedDownloadArtifactEntity?

    @Query(
        "SELECT * FROM managed_download_artifact " +
            "WHERE root_key = :rootKey"
    )
    suspend fun findAllByRootKey(rootKey: String): List<ManagedDownloadArtifactEntity>

    @Query(
        "SELECT * FROM managed_download_artifact " +
            "WHERE root_key = :rootKey AND state = :state " +
            "ORDER BY updated_at_ms ASC"
    )
    suspend fun findByState(
        rootKey: String,
        state: String
    ): List<ManagedDownloadArtifactEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: ManagedDownloadArtifactEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsentAll(entities: List<ManagedDownloadArtifactEntity>): List<Long>

    @Query(
        "UPDATE managed_download_artifact SET " +
            "state = :state, lease_id = :leaseId, updated_at_ms = :updatedAtMs, " +
            "needs_reconcile = 1, last_error_code = NULL " +
            "WHERE root_key = :rootKey AND stable_key = :stableKey " +
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
        "UPDATE managed_download_artifact SET " +
            "state = :repairState, lease_id = NULL, updated_at_ms = :updatedAtMs, " +
            "needs_reconcile = 1, last_error_code = :errorCode " +
            "WHERE root_key = :rootKey AND stable_key = :stableKey " +
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ManagedDownloadArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ManagedDownloadArtifactEntity>)

    @Query(
        "DELETE FROM managed_download_artifact " +
            "WHERE root_key = :rootKey AND stable_key = :stableKey"
    )
    suspend fun delete(rootKey: String, stableKey: String)
}
