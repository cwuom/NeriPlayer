package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadCancelledKeyEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadPendingQueueEntity

@Dao
internal interface DownloadRecoveryDao {
    @Query(
        "SELECT * FROM download_pending_queue " +
            "ORDER BY queue_order ASC, stable_key ASC"
    )
    suspend fun getPendingQueue(): List<DownloadPendingQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingQueue(entries: List<DownloadPendingQueueEntity>)

    @Query("DELETE FROM download_pending_queue WHERE stable_key IN (:stableKeys)")
    suspend fun deletePendingQueue(stableKeys: List<String>)

    @Query("DELETE FROM download_pending_queue")
    suspend fun clearPendingQueue()

    @Query("SELECT stable_key FROM download_cancelled_key")
    suspend fun getCancelledKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCancelledKeys(entries: List<DownloadCancelledKeyEntity>)

    @Query("DELETE FROM download_cancelled_key WHERE stable_key IN (:stableKeys)")
    suspend fun deleteCancelledKeys(stableKeys: List<String>)

    @Query("DELETE FROM download_cancelled_key")
    suspend fun clearCancelledKeys()
}
