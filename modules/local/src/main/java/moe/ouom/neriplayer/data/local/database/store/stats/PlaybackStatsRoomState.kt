package moe.ouom.neriplayer.data.local.database.store.stats

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity

internal data class PlaybackStatsRoomState(val revision: Long, val clearedAt: Long, val counterEpochStartedAt: Long)

internal data class PlaybackStatsDeltaRows(
    val track: PlaybackStatEntity?, val bucket: PlaybackStatBucketEntity?,
    val counter: PlaybackStatCounterShardEntity?, val dailyCounter: PlaybackStatDailyCounterShardEntity?
)
