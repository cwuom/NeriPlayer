package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.PlayHistoryEntity

@Dao
internal interface PlayHistoryDao {
    @Query("SELECT * FROM play_history ORDER BY played_at DESC, identity_key ASC")
    suspend fun getAll(): List<PlayHistoryEntity>

    @Upsert
    suspend fun upsert(entries: List<PlayHistoryEntity>)

    @Query("DELETE FROM play_history WHERE identity_key IN (:identityKeys)")
    suspend fun deleteByIdentityKeys(identityKeys: List<String>)

    @Query("DELETE FROM play_history")
    suspend fun deleteAll()
}
