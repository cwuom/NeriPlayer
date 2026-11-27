package moe.ouom.neriplayer.data.local.database.entity.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val MAX_PLAYBACK_LISTENING_INTERVAL_MS = 3_600_000L

@Entity(
    tableName = "playback_listening_interval",
    indices = [Index(value = ["start_at"]), Index(value = ["identity_key", "end_at"])]
)
data class PlaybackListeningIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    @ColumnInfo(name = "local_hour") val localHour: Int,
    @ColumnInfo(name = "epoch_started_at") val epochStartedAt: Long,
    @ColumnInfo(name = "recorded_local_date") val recordedLocalDate: String? = null
)
