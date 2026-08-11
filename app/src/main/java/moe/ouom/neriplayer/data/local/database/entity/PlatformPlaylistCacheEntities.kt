package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "platform_playlist_cache",
    primaryKeys = ["platform", "cache_key"],
    indices = [
        Index(
            value = ["platform", "source_id"],
            name = "index_platform_playlist_cache_source_id"
        ),
        Index(
            value = ["platform", "saved_at_ms"],
            name = "index_platform_playlist_cache_saved_at"
        )
    ]
)
internal data class PlatformPlaylistCacheEntity(
    val platform: String,
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "source_id")
    val sourceId: Long?,
    @ColumnInfo(name = "alternate_key")
    val alternateKey: String?,
    val kind: String?,
    val title: String?,
    val subtitle: String?,
    @ColumnInfo(name = "creator_name")
    val creatorName: String?,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "play_count")
    val playCount: Long?,
    @ColumnInfo(name = "track_count")
    val trackCount: Int,
    @ColumnInfo(name = "total_count")
    val totalCount: Int,
    @ColumnInfo(name = "signature_primary")
    val signaturePrimary: String?,
    @ColumnInfo(name = "signature_secondary")
    val signatureSecondary: String?,
    @ColumnInfo(name = "has_more")
    val hasMore: Boolean?,
    @ColumnInfo(name = "saved_at_ms")
    val savedAtMs: Long
)

@Entity(
    tableName = "platform_playlist_cache_track",
    primaryKeys = ["platform", "cache_key", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlatformPlaylistCacheEntity::class,
            parentColumns = ["platform", "cache_key"],
            childColumns = ["platform", "cache_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["platform", "cache_key", "item_id"],
            name = "index_platform_playlist_cache_track_item_id"
        ),
        Index(
            value = ["platform", "cache_key", "item_key"],
            name = "index_platform_playlist_cache_track_item_key"
        )
    ]
)
internal data class PlatformPlaylistCacheTrackEntity(
    val platform: String,
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    val position: Int,
    @ColumnInfo(name = "item_id")
    val itemId: Long?,
    @ColumnInfo(name = "item_key")
    val itemKey: String?,
    val name: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id")
    val albumId: Long?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "audio_id")
    val audioId: String?,
    @ColumnInfo(name = "uploader_mid")
    val uploaderMid: Long?,
    @ColumnInfo(name = "added_at")
    val addedAt: Long
)

@Entity(
    tableName = "platform_playlist_cache_track_artist",
    primaryKeys = ["platform", "cache_key", "track_position", "artist_position"],
    foreignKeys = [
        ForeignKey(
            entity = PlatformPlaylistCacheTrackEntity::class,
            parentColumns = ["platform", "cache_key", "position"],
            childColumns = ["platform", "cache_key", "track_position"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["platform", "cache_key", "track_position"],
            name = "index_platform_playlist_cache_artist_track"
        )
    ]
)
internal data class PlatformPlaylistCacheTrackArtistEntity(
    val platform: String,
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "track_position")
    val trackPosition: Int,
    @ColumnInfo(name = "artist_position")
    val artistPosition: Int,
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    val name: String
)
