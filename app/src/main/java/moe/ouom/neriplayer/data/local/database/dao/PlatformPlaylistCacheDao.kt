package moe.ouom.neriplayer.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackArtistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackEntity

@Dao
internal interface PlatformPlaylistCacheDao {
    @Query(
        "SELECT * FROM platform_playlist_cache " +
            "WHERE platform = :platform AND cache_key = :cacheKey"
    )
    suspend fun getCache(
        platform: String,
        cacheKey: String
    ): PlatformPlaylistCacheEntity?

    @Query(
        "SELECT * FROM platform_playlist_cache_track " +
            "WHERE platform = :platform AND cache_key = :cacheKey " +
            "ORDER BY position ASC"
    )
    suspend fun getTracks(
        platform: String,
        cacheKey: String
    ): List<PlatformPlaylistCacheTrackEntity>

    @Query(
        "SELECT * FROM platform_playlist_cache_track_artist " +
            "WHERE platform = :platform AND cache_key = :cacheKey " +
            "ORDER BY track_position ASC, artist_position ASC"
    )
    suspend fun getArtists(
        platform: String,
        cacheKey: String
    ): List<PlatformPlaylistCacheTrackArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(cache: PlatformPlaylistCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<PlatformPlaylistCacheTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<PlatformPlaylistCacheTrackArtistEntity>)

    @Query(
        "DELETE FROM platform_playlist_cache_track_artist " +
            "WHERE platform = :platform AND cache_key = :cacheKey"
    )
    suspend fun deleteArtists(
        platform: String,
        cacheKey: String
    )

    @Query(
        "DELETE FROM platform_playlist_cache_track " +
            "WHERE platform = :platform AND cache_key = :cacheKey"
    )
    suspend fun deleteTracks(
        platform: String,
        cacheKey: String
    )

    @Query(
        "DELETE FROM platform_playlist_cache " +
            "WHERE platform = :platform AND cache_key = :cacheKey"
    )
    suspend fun deleteCache(
        platform: String,
        cacheKey: String
    )
}
