package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard

@Entity(
    tableName = "local_playlist_playback_stat",
    indices = [
        Index(
            value = ["last_played_at"],
            orders = [Index.Order.DESC],
            name = "index_local_playlist_playback_stat_last_played"
        )
    ]
)
internal data class LocalPlaylistPlaybackStatEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "total_play_count")
    val totalPlayCount: Long,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long,
    @ColumnInfo(name = "counter_base_play_count")
    val counterBasePlayCount: Long
)

@Entity(
    tableName = "local_playlist_playback_bucket",
    primaryKeys = ["playlist_id", "day_start_at"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistPlaybackStatEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["playlist_id", "day_start_at"],
            orders = [Index.Order.ASC, Index.Order.ASC],
            name = "index_local_playlist_playback_bucket_day"
        )
    ]
)
internal data class LocalPlaylistPlaybackBucketEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "day_start_at")
    val dayStartAt: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Long,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long,
    @ColumnInfo(name = "counter_base_play_count")
    val counterBasePlayCount: Long
)

@Entity(
    tableName = "local_playlist_playback_counter_shard",
    primaryKeys = ["playlist_id", "day_start_at", "device_id", "epoch_started_at"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistPlaybackStatEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["playlist_id", "day_start_at"],
            name = "index_local_playlist_playback_counter_scope"
        )
    ]
)
internal data class LocalPlaylistPlaybackCounterShardEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "day_start_at")
    val dayStartAt: Long,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "epoch_started_at")
    val epochStartedAt: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    @ColumnInfo(name = "first_played_at")
    val firstPlayedAt: Long,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long
)

internal fun LocalPlaylistPlaybackStat.toEntity(): LocalPlaylistPlaybackStatEntity {
    return LocalPlaylistPlaybackStatEntity(
        playlistId = playlistId,
        totalPlayCount = totalPlayCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt,
        counterBasePlayCount = counterBasePlayCount
    )
}

internal fun LocalPlaylistPlayBucket.toEntity(
    playlistId: Long
): LocalPlaylistPlaybackBucketEntity {
    return LocalPlaylistPlaybackBucketEntity(
        playlistId = playlistId,
        dayStartAt = dayStartAt,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt,
        counterBasePlayCount = counterBasePlayCount
    )
}

internal fun SyncPlaybackCounterShard.toEntity(
    playlistId: Long,
    dayStartAt: Long
): LocalPlaylistPlaybackCounterShardEntity {
    return LocalPlaylistPlaybackCounterShardEntity(
        playlistId = playlistId,
        dayStartAt = dayStartAt,
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}
