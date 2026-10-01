package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.host.AndroidSyncSanitizationHost
import moe.ouom.neriplayer.data.sync.mapping.stats.SyncPlaybackStatMapping

internal object SyncPlaybackStatMapper {
    fun shouldSync(stat: TrackStat, context: Context): Boolean =
        SyncPlaybackStatMapping.shouldSync(stat, AndroidSyncSanitizationHost(context))

    fun shouldSync(bucket: PlaybackStatBucket, context: Context): Boolean =
        SyncPlaybackStatMapping.shouldSync(bucket, AndroidSyncSanitizationHost(context))

    fun fromTrackStat(stat: TrackStat, counterShards: List<SyncPlaybackCounterShard> = emptyList()): SyncTrackStat =
        SyncPlaybackStatMapping.fromTrackStat(stat, counterShards)

    fun fromPlaybackStatBucket(bucket: PlaybackStatBucket, counterShards: List<SyncPlaybackCounterShard> = emptyList()): SyncPlaybackStatBucket =
        SyncPlaybackStatMapping.fromPlaybackStatBucket(bucket, counterShards)

    fun sanitize(stat: SyncTrackStat, context: Context): SyncTrackStat? =
        SyncPlaybackStatMapping.sanitize(stat, AndroidSyncSanitizationHost(context))

    fun sanitize(bucket: SyncPlaybackStatBucket, context: Context): SyncPlaybackStatBucket? =
        SyncPlaybackStatMapping.sanitize(bucket, AndroidSyncSanitizationHost(context))

    fun normalizeCounterShards(shards: List<SyncPlaybackCounterShard?>?): List<SyncPlaybackCounterShard> =
        SyncPlaybackStatMapping.normalizeCounterShards(shards)
}
