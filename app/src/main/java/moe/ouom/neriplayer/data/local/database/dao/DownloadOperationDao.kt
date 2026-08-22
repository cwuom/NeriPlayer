package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity

@Dao
internal interface DownloadOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: DownloadOperationEntity)

    @Query("SELECT * FROM download_operation WHERE operation_id = :operationId LIMIT 1")
    suspend fun find(operationId: String): DownloadOperationEntity?

    @Query(
        "UPDATE download_operation SET state = :state, updated_at_ms = :updatedAtMs, " +
            "last_error_code = :errorCode WHERE operation_id = :operationId"
    )
    suspend fun updateState(
        operationId: String,
        state: String,
        updatedAtMs: Long,
        errorCode: String?
    )

    @Query("DELETE FROM download_operation WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)
}
