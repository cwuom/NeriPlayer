package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.ManagedLibraryItemEntity

@Dao
internal interface ManagedLibraryItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ManagedLibraryItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: ManagedLibraryItemEntity): Long

    @Query(
        "UPDATE managed_library_item SET " +
            "audio_reference = COALESCE(audio_reference, :audioReference), " +
            "audio_name = COALESCE(audio_name, :audioName), " +
            "file_size = COALESCE(file_size, :fileSize), " +
            "downloaded_at_ms = COALESCE(downloaded_at_ms, :downloadedAtMs), " +
            "metadata_name = CASE WHEN metadata_revision <= :metadataRevision " +
            "THEN COALESCE(:metadataName, metadata_name) ELSE metadata_name END, " +
            "locator_hint = CASE WHEN metadata_revision <= :metadataRevision " +
            "THEN COALESCE(:locatorHint, locator_hint) ELSE locator_hint END, " +
            "title_preview = CASE WHEN metadata_revision <= :metadataRevision " +
            "THEN :titlePreview ELSE title_preview END, " +
            "artist_preview = CASE WHEN metadata_revision <= :metadataRevision " +
            "THEN :artistPreview ELSE artist_preview END, " +
            "cover_key_preview = CASE WHEN metadata_revision <= :metadataRevision " +
            "THEN COALESCE(:coverKeyPreview, cover_key_preview) " +
            "ELSE cover_key_preview END, " +
            "metadata_revision = CASE " +
            "WHEN metadata_revision < :metadataRevision THEN :metadataRevision " +
            "ELSE metadata_revision END " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey"
    )
    suspend fun updatePreview(
        libraryId: String,
        stableKey: String,
        audioReference: String?,
        audioName: String?,
        fileSize: Long?,
        downloadedAtMs: Long?,
        metadataName: String?,
        locatorHint: String?,
        titlePreview: String,
        artistPreview: String,
        coverKeyPreview: String?,
        metadataRevision: Long
    ): Int

    @Query("SELECT * FROM managed_library_item WHERE library_id = :libraryId")
    suspend fun findAll(libraryId: String): List<ManagedLibraryItemEntity>

    @Query(
        "DELETE FROM managed_library_item " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey"
    )
    suspend fun delete(libraryId: String, stableKey: String)

}
