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

    @Query("SELECT * FROM managed_library_item WHERE library_id = :libraryId")
    suspend fun findAll(libraryId: String): List<ManagedLibraryItemEntity>

    @Query(
        "DELETE FROM managed_library_item " +
            "WHERE library_id = :libraryId AND stable_key = :stableKey"
    )
    suspend fun delete(libraryId: String, stableKey: String)

    @Query("DELETE FROM managed_library_item WHERE library_id = :libraryId")
    suspend fun clear(libraryId: String)

    @Query(
        "DELETE FROM managed_library_item " +
            "WHERE library_id = :libraryId AND stable_key NOT IN (:stableKeys)"
    )
    suspend fun deleteExcept(libraryId: String, stableKeys: List<String>)
}
