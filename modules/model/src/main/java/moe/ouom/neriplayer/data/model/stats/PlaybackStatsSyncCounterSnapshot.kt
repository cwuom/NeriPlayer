package moe.ouom.neriplayer.data.model.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard

data class PlaybackStatsSyncCounterSnapshot(
    val trackShardsByIdentity: Map<String, List<SyncPlaybackCounterShard>> = emptyMap(),
    val dailyShardsByBucketKey: Map<String, List<SyncPlaybackCounterShard>> = emptyMap()
) {
    fun trackShards(identityKey: String): List<SyncPlaybackCounterShard> {
        return trackShardsByIdentity[identityKey].orEmpty()
    }

    fun dailyShards(dayStartAt: Long, identityKey: String): List<SyncPlaybackCounterShard> {
        return dailyShardsByBucketKey[dailyCounterKey(dayStartAt, identityKey)].orEmpty()
    }

    companion object {
        fun dailyCounterKey(dayStartAt: Long, identityKey: String): String {
            return "$dayStartAt|$identityKey"
        }
    }
}
