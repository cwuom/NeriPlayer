package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import moe.ouom.neriplayer.data.local.database.entity.BiliCollectionBindingEntity

@Dao
interface BiliCollectionBindingDao {
    @Query("SELECT * FROM bili_collection_binding ORDER BY updated_at DESC")
    suspend fun getAll(): List<BiliCollectionBindingEntity>

    @Query("SELECT * FROM bili_collection_binding WHERE target_key = :targetKey LIMIT 1")
    suspend fun getByKey(targetKey: String): BiliCollectionBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: BiliCollectionBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bindings: List<BiliCollectionBindingEntity>)

    @Query("DELETE FROM bili_collection_binding WHERE target_key = :targetKey")
    suspend fun deleteByKey(targetKey: String)

    @Query("DELETE FROM bili_collection_binding")
    suspend fun deleteAll()

    /** 整表替换必须原子：中途失败或进程被杀时不能留下空表 */
    @Transaction
    suspend fun replaceAll(bindings: List<BiliCollectionBindingEntity>) {
        deleteAll()
        if (bindings.isNotEmpty()) upsertAll(bindings)
    }
}
