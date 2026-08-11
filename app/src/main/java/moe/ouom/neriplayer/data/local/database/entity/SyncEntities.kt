package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal object SyncTransportId {
    const val GITHUB = "github"
    const val WEBDAV = "webdav"
}

internal object SyncOutboxStatus {
    const val PENDING = "pending"
    const val APPLIED_TO_SECURE_STORAGE = "applied_to_secure_storage"
    const val DELIVERED = "delivered"
    const val FAILED_RETRYABLE = "failed_retryable"
    const val FAILED_PERMANENT = "failed_permanent"
}

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(
            value = ["operation_id"],
            orders = [Index.Order.ASC],
            unique = true,
            name = "index_sync_outbox_operation_id"
        ),
        Index(
            value = ["status", "sequence"],
            orders = [Index.Order.ASC, Index.Order.ASC],
            name = "index_sync_outbox_status_sequence"
        )
    ]
)
internal data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val sequence: Long = 0L,
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "expected_domain_revision")
    val expectedDomainRevision: Long,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "mutation_payload_json")
    val mutationPayloadJson: String,
    val status: String = SyncOutboxStatus.PENDING,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error_type")
    val lastErrorType: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt
)

@Entity(tableName = "sync_replica_checkpoint")
internal data class SyncReplicaCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "transport_id")
    val transportId: String,
    @ColumnInfo(name = "domain_revision")
    val domainRevision: Long,
    @ColumnInfo(name = "remote_version")
    val remoteVersion: String?,
    @ColumnInfo(name = "remote_fingerprint")
    val remoteFingerprint: String?,
    val status: String,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long,
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long
)

@Entity(tableName = "migration_metadata")
internal data class MigrationMetadataEntity(
    @PrimaryKey
    val key: String,
    val value: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
