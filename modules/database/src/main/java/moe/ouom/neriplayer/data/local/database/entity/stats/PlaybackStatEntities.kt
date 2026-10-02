package moe.ouom.neriplayer.data.local.database.entity.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat

@Entity(
    tableName = "playback_stat",
    indices = [
        Index(
            value = ["last_played_at"],
            orders = [Index.Order.DESC],
            name = "index_playback_stat_last_played"
        ),
        Index(value = ["media_uri"], name = "index_playback_stat_media_uri"),
        Index(value = ["play_count", "identity_key"], orders = [Index.Order.DESC, Index.Order.ASC], name = "index_playback_stat_play_count"),
        Index(value = ["total_listen_ms", "identity_key"], orders = [Index.Order.DESC, Index.Order.ASC], name = "index_playback_stat_listen_time"),
        Index(value = ["last_played_at", "identity_key"], orders = [Index.Order.DESC, Index.Order.ASC], name = "index_playback_stat_recent_identity"),
        Index(value = ["first_played_at", "identity_key"], name = "index_playback_stat_first_identity")
    ]
)
data class PlaybackStatEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "total_listen_ms")
    val totalListenMs: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String?,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String?,
    @ColumnInfo(name = "local_file_name")
    val localFileName: String?,
    @ColumnInfo(name = "custom_name")
    val customName: String?,
    @ColumnInfo(name = "custom_artist")
    val customArtist: String?,
    @ColumnInfo(name = "custom_cover_url")
    val customCoverUrl: String?
)

@Entity(
    tableName = "playback_stat_bucket",
    primaryKeys = ["day_start_at", "identity_key"],
    foreignKeys = [
        ForeignKey(
            entity = PlaybackStatEntity::class,
            parentColumns = ["identity_key"],
            childColumns = ["identity_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["day_start_at", "identity_key"],
            orders = [Index.Order.DESC, Index.Order.ASC],
            name = "index_playback_stat_bucket_day"
        ),
        Index(value = ["identity_key"], name = "index_playback_stat_bucket_identity"),
        Index(value = ["identity_key", "day_start_at"], name = "index_playback_stat_bucket_identity_day"),
        Index(value = ["identity_key", "last_played_at", "day_start_at"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC], name = "index_playback_stat_bucket_latest")
    ]
)
data class PlaybackStatBucketEntity(
    @ColumnInfo(name = "day_start_at")
    val dayStartAt: Long,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "total_listen_ms")
    val totalListenMs: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String?,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String?,
    @ColumnInfo(name = "local_file_name")
    val localFileName: String?,
    @ColumnInfo(name = "custom_name")
    val customName: String?,
    @ColumnInfo(name = "custom_artist")
    val customArtist: String?,
    @ColumnInfo(name = "custom_cover_url")
    val customCoverUrl: String?
)

@Entity(
    tableName = "playback_stat_counter_shard",
    primaryKeys = ["identity_key", "device_id", "epoch_started_at"],
    foreignKeys = [
        ForeignKey(
            entity = PlaybackStatEntity::class,
            parentColumns = ["identity_key"],
            childColumns = ["identity_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["identity_key"], name = "index_playback_stat_counter_identity")
    ]
)
data class PlaybackStatCounterShardEntity(
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "epoch_started_at")
    val epochStartedAt: Long,
    @ColumnInfo(name = "total_listen_ms")
    val totalListenMs: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long
)

@Entity(
    tableName = "playback_stat_daily_counter_shard",
    primaryKeys = ["day_start_at", "identity_key", "device_id", "epoch_started_at"],
    foreignKeys = [
        ForeignKey(
            entity = PlaybackStatBucketEntity::class,
            parentColumns = ["day_start_at", "identity_key"],
            childColumns = ["day_start_at", "identity_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["day_start_at", "identity_key"],
            name = "index_playback_stat_daily_counter_scope"
        )
    ]
)
data class PlaybackStatDailyCounterShardEntity(
    @ColumnInfo(name = "day_start_at")
    val dayStartAt: Long,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "epoch_started_at")
    val epochStartedAt: Long,
    @ColumnInfo(name = "total_listen_ms")
    val totalListenMs: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long
)

fun TrackStat.toEntity(): PlaybackStatEntity {
    return PlaybackStatEntity(
        identityKey = identityKey,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl
    )
}

fun PlaybackStatBucket.toEntity(): PlaybackStatBucketEntity {
    return PlaybackStatBucketEntity(
        dayStartAt = dayStartAt,
        identityKey = identityKey,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl
    )
}
