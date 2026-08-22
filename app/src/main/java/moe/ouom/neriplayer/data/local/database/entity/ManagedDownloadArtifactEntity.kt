package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "managed_download_artifact",
    primaryKeys = ["root_key", "stable_key"],
    indices = [
        Index(
            value = ["root_key", "state"],
            name = "index_managed_download_artifact_root_state"
        ),
        Index(
            value = ["artifact_id"],
            unique = true,
            name = "index_managed_download_artifact_artifact_id"
        )
    ]
)
internal data class ManagedDownloadArtifactEntity(
    @ColumnInfo(name = "root_key")
    val rootKey: String,
    @ColumnInfo(name = "stable_key")
    val stableKey: String,
    @ColumnInfo(name = "artifact_id")
    val artifactId: String,
    val state: String,
    @ColumnInfo(name = "lease_id")
    val leaseId: String?,
    @ColumnInfo(name = "audio_reference")
    val audioReference: String?,
    @ColumnInfo(name = "audio_name")
    val audioName: String?,
    @ColumnInfo(name = "file_size")
    val fileSize: Long?,
    @ColumnInfo(name = "content_hash")
    val contentHash: String?,
    @ColumnInfo(name = "library_added_at_ms")
    val libraryAddedAtMs: Long?,
    @ColumnInfo(name = "source_created_at_ms")
    val sourceCreatedAtMs: Long?,
    @ColumnInfo(name = "source_modified_at_ms")
    val sourceModifiedAtMs: Long?,
    @ColumnInfo(name = "downloaded_at_ms")
    val downloadedAtMs: Long?,
    @ColumnInfo(name = "migrated_at_ms")
    val migratedAtMs: Long?,
    @ColumnInfo(name = "finalized_at_ms")
    val finalizedAtMs: Long?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "needs_reconcile")
    val needsReconcile: Boolean,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?
)
