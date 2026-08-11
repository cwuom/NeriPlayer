package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.DownloadedSongCatalogEntity

@Dao
internal interface DownloadedSongCatalogDao {
    @Query(
        "SELECT * FROM downloaded_song_catalog " +
            "WHERE root_key = :rootKey " +
            "ORDER BY display_position ASC, catalog_key ASC"
    )
    suspend fun getSongs(rootKey: String): List<DownloadedSongCatalogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<DownloadedSongCatalogEntity>)

    @Query("DELETE FROM downloaded_song_catalog")
    suspend fun clearSongs()
}
