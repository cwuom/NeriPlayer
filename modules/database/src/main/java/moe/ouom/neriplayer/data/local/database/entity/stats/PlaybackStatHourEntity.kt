package moe.ouom.neriplayer.data.local.database.entity.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playback_stat_hour",
    primaryKeys = ["hour_start_at", "identity_key", "local_hour"],
    indices = [Index(value = ["identity_key"])]
)
data class PlaybackStatHourEntity(
    @ColumnInfo(name = "hour_start_at") val hourStartAt: Long,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "local_hour") val localHour: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "epoch_started_at") val epochStartedAt: Long,
    @ColumnInfo(name = "recorded_local_date") val recordedLocalDate: String? = null
)
