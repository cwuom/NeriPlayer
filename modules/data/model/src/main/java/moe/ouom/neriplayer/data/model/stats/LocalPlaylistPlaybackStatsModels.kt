package moe.ouom.neriplayer.data.model.stats

import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard

data class LocalPlaylistPlayBucket(
    val dayStartAt: Long,
    val playCount: Long,
    val firstPlayedAt: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val counterBasePlayCount: Long = 0L,
    val counterShards: List<SyncPlaybackCounterShard> = emptyList()
)

data class LocalPlaylistPlaybackStat(
    val playlistId: Long,
    val totalPlayCount: Long = 0L,
    val firstPlayedAt: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val counterBasePlayCount: Long = 0L,
    val counterShards: List<SyncPlaybackCounterShard> = emptyList(),
    val dailyPlayBuckets: List<LocalPlaylistPlayBucket> = emptyList()
)

data class LocalPlaylistHotEntry(
    val playlistId: Long,
    val playCount: Long
)

data class LocalPlaylistPlaybackSyncSnapshot(
    val stats: List<SyncLocalPlaylistPlaybackStat>,
    val buckets: List<SyncLocalPlaylistPlaybackBucket>
)
