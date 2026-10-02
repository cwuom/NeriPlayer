package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat

internal object SyncPlaybackStatsMetadataPolicy {
    private val statOrder = compareBy(SyncTrackStat::lastPlayedAt)
        .thenBy(SyncTrackStat::name)
        .thenBy(SyncTrackStat::artist)
        .thenBy(SyncTrackStat::album)
        .thenBy(SyncTrackStat::coverUrl)
        .thenBy(SyncTrackStat::durationMs)
        .thenBy(SyncTrackStat::mediaUri)
        .thenBy(SyncTrackStat::id)
        .thenBy(SyncTrackStat::albumId)

    private val bucketOrder = compareBy(SyncPlaybackStatBucket::lastPlayedAt)
        .thenBy(SyncPlaybackStatBucket::name)
        .thenBy(SyncPlaybackStatBucket::artist)
        .thenBy(SyncPlaybackStatBucket::album)
        .thenBy(SyncPlaybackStatBucket::coverUrl)
        .thenBy(SyncPlaybackStatBucket::durationMs)
        .thenBy(SyncPlaybackStatBucket::mediaUri)
        .thenBy(SyncPlaybackStatBucket::id)
        .thenBy(SyncPlaybackStatBucket::albumId)

    // 同时播放时也必须选择固定元数据，否则两个设备会随合并方向交替上传
    fun select(left: SyncTrackStat, right: SyncTrackStat): SyncTrackStat =
        if (statOrder.compare(left, right) >= 0) left else right

    fun select(left: SyncPlaybackStatBucket, right: SyncPlaybackStatBucket): SyncPlaybackStatBucket =
        if (bucketOrder.compare(left, right) >= 0) left else right
}
