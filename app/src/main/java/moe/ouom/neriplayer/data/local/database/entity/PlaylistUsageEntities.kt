package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.usageKey
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard

@Entity(
    tableName = "playlist_usage",
    indices = [
        Index(
            value = ["last_opened"],
            orders = [Index.Order.DESC],
            name = "index_playlist_usage_last_opened"
        ),
        Index(value = ["source", "playlist_id"], name = "index_playlist_usage_source_id")
    ]
)
internal data class PlaylistUsageEntity(
    @PrimaryKey
    @ColumnInfo(name = "usage_key")
    val usageKey: String,
    val id: Long,
    val name: String,
    @ColumnInfo(name = "pic_url")
    val picUrl: String?,
    @ColumnInfo(name = "track_count")
    val trackCount: Int,
    val source: String,
    @ColumnInfo(name = "last_opened")
    val lastOpened: Long,
    @ColumnInfo(name = "open_count")
    val openCount: Int,
    @ColumnInfo(name = "first_opened")
    val firstOpened: Long,
    @ColumnInfo(name = "counter_base_open_count")
    val counterBaseOpenCount: Long,
    val fid: Long?,
    val mid: Long?,
    @ColumnInfo(name = "browse_id")
    val browseId: String?,
    @ColumnInfo(name = "playlist_id")
    val playlistId: String?,
    val subtype: String?,
    val subtitle: String?
)

@Entity(
    tableName = "playlist_usage_counter_shard",
    primaryKeys = ["usage_key", "device_id", "epoch_started_at"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = PlaylistUsageEntity::class,
            parentColumns = ["usage_key"],
            childColumns = ["usage_key"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["usage_key"], name = "index_playlist_usage_counter_usage_key")
    ]
)
internal data class PlaylistUsageCounterShardEntity(
    @ColumnInfo(name = "usage_key")
    val usageKey: String,
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

internal fun UsageEntry.toEntity(): PlaylistUsageEntity {
    return PlaylistUsageEntity(
        usageKey = usageKey(),
        id = id,
        name = name,
        picUrl = picUrl,
        trackCount = trackCount,
        source = source,
        lastOpened = lastOpened,
        openCount = openCount,
        firstOpened = firstOpened,
        counterBaseOpenCount = counterBaseOpenCount,
        fid = fid,
        mid = mid,
        browseId = browseId,
        playlistId = playlistId,
        subtype = subtype,
        subtitle = subtitle
    )
}

internal fun SyncPlaybackCounterShard.toEntity(usageKey: String): PlaylistUsageCounterShardEntity {
    return PlaylistUsageCounterShardEntity(
        usageKey = usageKey,
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}
