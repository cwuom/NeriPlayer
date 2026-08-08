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

    @Query("DELETE FROM download_snapshot_entry WHERE root_key = :rootKey")
    suspend fun clearEntries(rootKey: String)

    @Query("DELETE FROM download_snapshot_metadata WHERE root_key = :rootKey")
    suspend fun clearMetadata(rootKey: String)
}
