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
    @ColumnInfo(name = "stop_requested_by_user", defaultValue = "0")
    val stopRequestedByUser: Boolean = false,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long
)

internal data class DownloadOperationIdentityRow(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "stable_key") val stableKey: String
)

/**
 * represents the bounded OS-host handoff for one operation in the current app process
 *
 * Queued operations are deliberately not represented here.  A row is created only
 * immediately before handing an operation to WorkManager or UIDT, so a large batch
 * cannot make every durable QUEUED row consume a host slot.
 */
@Entity(
    tableName = "download_host_admission",
    primaryKeys = ["operation_id"],
    indices = [
        Index(
            value = ["process_token", "library_id"],
            name = "index_download_host_admission_process_library"
        )
    ]
)
internal data class DownloadHostAdmissionEntity(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "library_id") val libraryId: String,
    @ColumnInfo(name = "process_token") val processToken: String,
    @ColumnInfo(name = "admitted_at_ms") val admittedAtMs: Long
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
    @ColumnInfo(name = "library_id") val rootKey: String,
    @ColumnInfo(name = "stable_key") val stableKey: String,
    @ColumnInfo(name = "artifact_id") val artifactId: String,
    val state: String,
    @ColumnInfo(name = "lease_id") val leaseId: String? = null,
    @ColumnInfo(name = "audio_reference") val audioReference: String? = null,
    @ColumnInfo(name = "audio_name") val audioName: String? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "library_added_at_ms") val libraryAddedAtMs: Long? = null,
    @ColumnInfo(name = "source_created_at_ms") val sourceCreatedAtMs: Long? = null,
    @ColumnInfo(name = "source_modified_at_ms") val sourceModifiedAtMs: Long? = null,
    @ColumnInfo(name = "downloaded_at_ms") val downloadedAtMs: Long? = null,
    @ColumnInfo(name = "migrated_at_ms") val migratedAtMs: Long? = null,
    @ColumnInfo(name = "finalized_at_ms") val finalizedAtMs: Long? = null,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long = 0L,
    @ColumnInfo(name = "needs_reconcile") val needsReconcile: Boolean = false,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "metadata_name") val metadataName: String? = null,
    @ColumnInfo(name = "locator_hint") val locatorHint: String? = null,
    @ColumnInfo(name = "title_preview") val titlePreview: String? = null,
    @ColumnInfo(name = "artist_preview") val artistPreview: String? = null,
    @ColumnInfo(name = "cover_key_preview") val coverKeyPreview: String? = null,
    @ColumnInfo(name = "metadata_revision") val metadataRevision: Long = 0L
) {
    val libraryId: String
        get() = rootKey
}
