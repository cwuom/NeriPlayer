package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "download_operation",
    primaryKeys = ["operation_id"],
    indices = [
        Index(
            value = ["state", "queue_order"],
            name = "index_download_operation_state_queue"
        )
    ]
)
internal data class DownloadOperationEntity(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "library_id") val libraryId: String,
    val state: String,
    @ColumnInfo(name = "queue_order") val queueOrder: Int,
    @ColumnInfo(name = "source_hint_json") val sourceHintJson: String,
    @ColumnInfo(name = "staging_dir_name") val stagingDirName: String,
    @ColumnInfo(name = "bytes_written") val bytesWritten: Long,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long?,
    @ColumnInfo(name = "resume_json") val resumeJson: String?,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
    @ColumnInfo(name = "next_retry_at_ms") val nextRetryAtMs: Long?,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long
)

@Entity(
    tableName = "managed_library_item",
    primaryKeys = ["library_id", "stable_key"],
    indices = [
        Index(
            value = ["artifact_id"],
            unique = true,
            name = "index_managed_library_item_artifact"
        )
    ]
)
internal data class ManagedLibraryItemEntity(
    @ColumnInfo(name = "library_id") val libraryId: String,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "artifact_id") val artifactId: String,
    val state: String,
    @ColumnInfo(name = "audio_name") val audioName: String,
    @ColumnInfo(name = "metadata_name") val metadataName: String,
    @ColumnInfo(name = "locator_hint") val locatorHint: String?,
    @ColumnInfo(name = "title_preview") val titlePreview: String?,
    @ColumnInfo(name = "artist_preview") val artistPreview: String?,
    @ColumnInfo(name = "cover_key_preview") val coverKeyPreview: String?,
    @ColumnInfo(name = "downloaded_at_ms") val downloadedAtMs: Long?,
    @ColumnInfo(name = "metadata_revision") val metadataRevision: Long
)
