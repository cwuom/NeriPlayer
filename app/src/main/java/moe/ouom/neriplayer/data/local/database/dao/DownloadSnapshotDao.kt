package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotEntryEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadSnapshotMetadataEntity

@Dao
internal interface DownloadSnapshotDao {
    @Query(
        "SELECT * FROM download_snapshot_entry " +
            "WHERE root_key = :rootKey AND bucket = :bucket " +
            "ORDER BY display_position ASC, entry_key ASC"
    )
    suspend fun getEntries(rootKey: String, bucket: String): List<DownloadSnapshotEntryEntity>

    @Query(
        "SELECT * FROM download_snapshot_metadata " +
            "WHERE root_key = :rootKey " +
            "ORDER BY audio_name ASC"
    )
    suspend fun getMetadata(rootKey: String): List<DownloadSnapshotMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<DownloadSnapshotEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: List<DownloadSnapshotMetadataEntity>)

    @Query(
        "SELECT entry_key FROM download_snapshot_entry " +
            "WHERE root_key = :rootKey AND bucket = :bucket"
    )
    suspend fun getEntryKeys(rootKey: String, bucket: String): List<String>

    @Query(
        "DELETE FROM download_snapshot_entry " +
            "WHERE root_key = :rootKey AND bucket = :bucket " +
            "AND entry_key IN (:entryKeys)"
    )
    suspend fun deleteEntries(
        rootKey: String,
        bucket: String,
        entryKeys: List<String>
    )

    @Query(
        "SELECT audio_name FROM download_snapshot_metadata " +
            "WHERE root_key = :rootKey"
    )
    suspend fun getMetadataAudioNames(rootKey: String): List<String>

    @Query(
        "DELETE FROM download_snapshot_metadata " +
            "WHERE root_key = :rootKey AND audio_name IN (:audioNames)"
    )
    suspend fun deleteMetadata(rootKey: String, audioNames: List<String>)

    @Query(
        "DELETE FROM download_snapshot_entry " +
            "WHERE root_key = :rootKey AND bucket = :bucket"
    )
    suspend fun deleteEntriesForRootBucket(rootKey: String, bucket: String)

    @Query(
        "DELETE FROM download_snapshot_metadata WHERE root_key = :rootKey"
    )
    suspend fun deleteMetadataForRoot(rootKey: String)

    @Query("DELETE FROM download_snapshot_entry")
    suspend fun clearEntries()

    @Query("DELETE FROM download_snapshot_metadata")
    suspend fun clearMetadata()
}
