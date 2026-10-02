package moe.ouom.neriplayer.data.local.database.entity.stats

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playback_stats_snapshot")
data class PlaybackStatsSnapshotEntity(
    @PrimaryKey val id: String,
    val revision: Long,
    @ColumnInfo(name = "cleared_at") val clearedAt: Long,
    @ColumnInfo(name = "counter_epoch_started_at") val counterEpochStartedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val sealed: Boolean,
    @ColumnInfo(name = "owner_process_id") val ownerProcessId: String,
    @ColumnInfo(name = "is_diff", defaultValue = "0") val isDiff: Boolean = false
)

@Entity(tableName = "playback_stat_snapshot_deleted_track", primaryKeys = ["snapshot_id", "identity_key"])
data class PlaybackStatsSnapshotDeletedTrackEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "identity_key") val identityKey: String
)

@Entity(tableName = "playback_stat_snapshot_deleted_bucket", primaryKeys = ["snapshot_id", "day_start_at", "identity_key"])
data class PlaybackStatsSnapshotDeletedBucketEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "day_start_at") val dayStartAt: Long,
    @ColumnInfo(name = "identity_key") val identityKey: String
)

@Entity(tableName = "playback_stat_snapshot_track", primaryKeys = ["snapshot_id", "identity_key"])
data class PlaybackStatsSnapshotTrackEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @Embedded val stat: PlaybackStatsSnapshotTrackData
)

@Entity(
    tableName = "playback_stat_snapshot_bucket",
    primaryKeys = ["snapshot_id", "day_start_at", "identity_key"],
    indices = [Index(value = ["snapshot_id", "identity_key", "day_start_at"], name = "index_playback_snapshot_bucket_identity")]
)
data class PlaybackStatsSnapshotBucketEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @Embedded val bucket: PlaybackStatsSnapshotBucketData
)

@Entity(tableName = "playback_stat_snapshot_counter", primaryKeys = ["snapshot_id", "identity_key", "device_id", "epoch_started_at"])
data class PlaybackStatsSnapshotCounterEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @Embedded val shard: PlaybackStatsSnapshotCounterData
)

@Entity(tableName = "playback_stat_snapshot_daily_counter", primaryKeys = ["snapshot_id", "day_start_at", "identity_key", "device_id", "epoch_started_at"])
data class PlaybackStatsSnapshotDailyCounterEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @Embedded val shard: PlaybackStatsSnapshotDailyCounterData
)

@Entity(tableName = "playback_stats_pending_delta", indices = [Index(value = ["sequence"], unique = true, name = "index_playback_pending_sequence")])
data class PlaybackStatsPendingDeltaEntity(
    @PrimaryKey val id: String,
    val sequence: Long,
    @ColumnInfo(name = "track_json") val trackJson: String,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "play_count_increment") val playCountIncrement: Int?,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @ColumnInfo(name = "epoch_started_at") val epochStartedAt: Long,
    @ColumnInfo(name = "device_id") val deviceId: String
)

data class PlaybackStatsSnapshotTrackData(
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

fun PlaybackStatEntity.toSnapshotData() = PlaybackStatsSnapshotTrackData(
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

fun PlaybackStatsSnapshotTrackData.toEntity() = PlaybackStatEntity(
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

data class PlaybackStatsSnapshotBucketData(
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

fun PlaybackStatBucketEntity.toSnapshotData() = PlaybackStatsSnapshotBucketData(
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

fun PlaybackStatsSnapshotBucketData.toEntity() = PlaybackStatBucketEntity(
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

data class PlaybackStatsSnapshotCounterData(
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

fun PlaybackStatCounterShardEntity.toSnapshotData() = PlaybackStatsSnapshotCounterData(
    identityKey = identityKey,
    deviceId = deviceId,
    epochStartedAt = epochStartedAt,
    totalListenMs = totalListenMs,
    playCount = playCount,
    firstPlayedAt = firstPlayedAt,
    lastPlayedAt = lastPlayedAt
)

fun PlaybackStatsSnapshotCounterData.toEntity() = PlaybackStatCounterShardEntity(
    identityKey = identityKey,
    deviceId = deviceId,
    epochStartedAt = epochStartedAt,
    totalListenMs = totalListenMs,
    playCount = playCount,
    firstPlayedAt = firstPlayedAt,
    lastPlayedAt = lastPlayedAt
)

data class PlaybackStatsSnapshotDailyCounterData(
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

fun PlaybackStatDailyCounterShardEntity.toSnapshotData() = PlaybackStatsSnapshotDailyCounterData(
    dayStartAt = dayStartAt,
    identityKey = identityKey,
    deviceId = deviceId,
    epochStartedAt = epochStartedAt,
    totalListenMs = totalListenMs,
    playCount = playCount,
    firstPlayedAt = firstPlayedAt,
    lastPlayedAt = lastPlayedAt
)

fun PlaybackStatsSnapshotDailyCounterData.toEntity() = PlaybackStatDailyCounterShardEntity(
    dayStartAt = dayStartAt,
    identityKey = identityKey,
    deviceId = deviceId,
    epochStartedAt = epochStartedAt,
    totalListenMs = totalListenMs,
    playCount = playCount,
    firstPlayedAt = firstPlayedAt,
    lastPlayedAt = lastPlayedAt
)

@Entity(tableName = "playback_stats_event_receipt")
data class PlaybackStatsEventReceiptEntity(@PrimaryKey val id: String, @ColumnInfo(name = "accepted_at") val acceptedAt: Long, @ColumnInfo(name = "payload_hash") val payloadHash: String)
