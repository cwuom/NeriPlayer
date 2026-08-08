package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.CoverUrlMappingEntity

@Dao
internal interface CoverUrlMappingDao {
    @Query("SELECT * FROM cover_url_mapping")
    suspend fun getAll(): List<CoverUrlMappingEntity>

    @Upsert
    suspend fun upsert(entity: CoverUrlMappingEntity)

    @Upsert
    suspend fun upsert(entities: List<CoverUrlMappingEntity>)

    @Query("DELETE FROM cover_url_mapping WHERE local_url IN (:localUrls)")
    suspend fun delete(localUrls: List<String>)

    @Query("DELETE FROM cover_url_mapping")
    suspend fun deleteAll()
}
