package moe.ouom.neriplayer.data.sync.github

import android.content.Context
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.host.AndroidSyncSanitizationHost
import moe.ouom.neriplayer.data.sync.mapping.stats.SyncPlaybackStatMapping
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist

internal object SyncPlaybackStatMapper {
    fun bind(context: Context, localAlbumNames: Set<String> = LocalFilesPlaylist.candidateNames(context)): SyncPlaybackStatProjection {
        val aliases = localAlbumNames.toSet()
        return SyncPlaybackStatProjection(AndroidSyncSanitizationHost(context) { aliases })
    }
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

internal class SyncPlaybackStatProjection(private val host: AndroidSyncSanitizationHost) {
    fun shouldSync(stat: TrackStat): Boolean = SyncPlaybackStatMapping.shouldSync(stat, host)
    fun shouldSync(bucket: PlaybackStatBucket): Boolean = SyncPlaybackStatMapping.shouldSync(bucket, host)
    fun sanitize(stat: SyncTrackStat): SyncTrackStat? = SyncPlaybackStatMapping.sanitize(stat, host)
    fun sanitize(bucket: SyncPlaybackStatBucket): SyncPlaybackStatBucket? = SyncPlaybackStatMapping.sanitize(bucket, host)
}
