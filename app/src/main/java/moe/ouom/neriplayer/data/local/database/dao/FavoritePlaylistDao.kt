package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongEntity

@Dao
internal interface FavoritePlaylistDao {
    @Query(
        "SELECT * FROM favorite_playlist " +
            "ORDER BY sort_order DESC, modified_at DESC, source ASC, playlist_id ASC"
    )
    suspend fun getPlaylists(): List<FavoritePlaylistEntity>

    @Query(
        "SELECT * FROM favorite_playlist_song " +
            "ORDER BY playlist_id ASC, source ASC, display_position ASC"
    )
    suspend fun getSongs(): List<FavoritePlaylistSongEntity>

    @Upsert
    suspend fun upsertPlaylists(playlists: List<FavoritePlaylistEntity>)

    @Upsert
    suspend fun upsertSongs(songs: List<FavoritePlaylistSongEntity>)

    @Query("DELETE FROM favorite_playlist_song")
    suspend fun deleteAllSongs()

    @Query("DELETE FROM favorite_playlist")
    suspend fun deleteAllPlaylists()

    @Query(
        "DELETE FROM favorite_playlist_song " +
            "WHERE playlist_id = :playlistId AND source = :source"
    )
    suspend fun deleteSongs(playlistId: Long, source: String)

    @Query(
        "DELETE FROM favorite_playlist " +
            "WHERE playlist_id = :playlistId AND source = :source"
    )
    suspend fun deletePlaylist(playlistId: Long, source: String)
}
