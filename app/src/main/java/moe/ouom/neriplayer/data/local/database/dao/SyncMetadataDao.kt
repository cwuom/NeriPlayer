package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncReplicaCheckpointEntity

@Dao
internal interface SyncMetadataDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(entry: SyncOutboxEntity): Long

    @Query(
        "SELECT * FROM sync_outbox WHERE status IN (:statuses) " +
            "ORDER BY sequence ASC LIMIT :limit"
    )
    suspend fun getOutbox(statuses: List<String>, limit: Int): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE status = :status")
    suspend fun deleteOutboxByStatus(status: String)

    @Query(
        "UPDATE sync_outbox SET status = :status, attempt_count = :attemptCount, " +
            "last_error_type = :lastErrorType, updated_at = :updatedAt " +
            "WHERE sequence = :sequence"
    )
    suspend fun updateOutbox(
        sequence: Long,
        status: String,
        attemptCount: Int,
        lastErrorType: String?,
        updatedAt: Long
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: SyncReplicaCheckpointEntity)

    @Query(
        "SELECT * FROM sync_replica_checkpoint WHERE transport_id = :transportId"
    )
    suspend fun getCheckpoint(transportId: String): SyncReplicaCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMigrationMetadata(metadata: MigrationMetadataEntity)

    @Query("SELECT * FROM migration_metadata WHERE key = :key")
    suspend fun getMigrationMetadata(key: String): MigrationMetadataEntity?
}
