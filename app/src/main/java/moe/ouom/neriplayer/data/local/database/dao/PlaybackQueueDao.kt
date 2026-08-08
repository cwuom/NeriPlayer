package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueSongEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueStateEntity

@Dao
internal interface PlaybackQueueDao {
    @Query("SELECT * FROM playback_queue_state WHERE id = 1")
    suspend fun getState(): PlaybackQueueStateEntity?

    @Query(
        "SELECT * FROM playback_queue_song WHERE queue_id = :queueId " +
            "ORDER BY position ASC"
    )
    suspend fun getSongs(queueId: String): List<PlaybackQueueSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: PlaybackQueueStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<PlaybackQueueSongEntity>)

    @Query("DELETE FROM playback_queue_state")
    suspend fun deleteState()

    @Query("DELETE FROM playback_queue_song")
    suspend fun deleteSongs()

    @Query("DELETE FROM playback_queue_song WHERE queue_id = :queueId")
    suspend fun deleteSongs(queueId: String)
}
