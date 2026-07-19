package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.writeTextAtomically
import java.io.File

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

private data class PlaybackStatsCounterState(
    val epochStartedAt: Long = 0L,
    val trackShardsByIdentity: Map<String, List<SyncPlaybackCounterShard>> = emptyMap(),
    val dailyShardsByBucketKey: Map<String, List<SyncPlaybackCounterShard>> = emptyMap()
)

internal class PlaybackStatsCounterStore(
    private val context: Context,
    private val gson: Gson
) {
    private val counterFile: File by lazy {
        File(context.filesDir, "playback_stats_counters.json")
    }
    private val lock = Any()
    private val syncStorage by lazy { SecureTokenStorage(context) }
    private var state = load()

    fun snapshot(): PlaybackStatsSyncCounterSnapshot {
        val current = synchronized(lock) { state }
        return PlaybackStatsSyncCounterSnapshot(
            trackShardsByIdentity = current.trackShardsByIdentity.mapValues { (_, shards) ->
                SyncPlaybackStatMapper.normalizeCounterShards(shards)
            },
            dailyShardsByBucketKey = current.dailyShardsByBucketKey.mapValues { (_, shards) ->
                SyncPlaybackStatMapper.normalizeCounterShards(shards)
            }
        )
    }

    fun recordLocalDelta(
        identityKey: String,
        dayStartAt: Long,
        listenedMs: Long,
        playCountIncrement: Int,
        playedAt: Long,
        epochStartedAt: Long
    ) {
        if (identityKey.isBlank()) return
        if (listenedMs <= 0L && playCountIncrement <= 0) return

        val deviceId = syncCounterDeviceId()
        val current = ensureEpoch(synchronized(lock) { state }, epochStartedAt)
        val updatedTrackShards = current.trackShardsByIdentity.toMutableMap()
        updatedTrackShards[identityKey] = updateShardList(
            shards = updatedTrackShards[identityKey].orEmpty(),
            deviceId = deviceId,
            epochStartedAt = epochStartedAt,
            listenedMs = listenedMs,
            playCountIncrement = playCountIncrement,
            playedAt = playedAt
        )

        val dailyKey = PlaybackStatsSyncCounterSnapshot.dailyCounterKey(dayStartAt, identityKey)
        val updatedDailyShards = current.dailyShardsByBucketKey.toMutableMap()
        updatedDailyShards[dailyKey] = updateShardList(
            shards = updatedDailyShards[dailyKey].orEmpty(),
            deviceId = deviceId,
            epochStartedAt = epochStartedAt,
            listenedMs = listenedMs,
            playCountIncrement = playCountIncrement,
            playedAt = playedAt
        )

        persist(
            current.copy(
                trackShardsByIdentity = updatedTrackShards,
                dailyShardsByBucketKey = updatedDailyShards
            )
        )
    }

    fun reset(epochStartedAt: Long) {
        persist(PlaybackStatsCounterState(epochStartedAt = epochStartedAt.coerceAtLeast(0L)))
    }

    fun removeTracks(keys: Set<String>) {
        if (keys.isEmpty()) return
        val current = synchronized(lock) { state }
        persist(
            current.copy(
                trackShardsByIdentity = current.trackShardsByIdentity - keys,
                dailyShardsByBucketKey = current.dailyShardsByBucketKey.filterKeys { key ->
                    val identityKey = key.substringAfter('|', missingDelimiterValue = key)
                    identityKey !in keys
                }
            )
        )
    }

    fun replaceFromSync(
        syncStats: List<SyncTrackStat>,
        syncDailyStats: List<SyncPlaybackStatBucket>,
        epochStartedAt: Long
    ) {
        val trackShards = syncStats
            .associate { stat ->
                stat.identityKey to SyncPlaybackStatMapper.normalizeCounterShards(stat.counterShards)
            }
            .filterValues { it.isNotEmpty() }
        val dailyShards = syncDailyStats
            .associate { bucket ->
                PlaybackStatsSyncCounterSnapshot.dailyCounterKey(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                ) to SyncPlaybackStatMapper.normalizeCounterShards(bucket.counterShards)
            }
            .filterValues { it.isNotEmpty() }
        persist(
            PlaybackStatsCounterState(
                epochStartedAt = epochStartedAt.coerceAtLeast(0L),
                trackShardsByIdentity = trackShards,
                dailyShardsByBucketKey = dailyShards
            )
        )
    }

    private fun load(): PlaybackStatsCounterState {
        return try {
            if (!counterFile.exists()) return PlaybackStatsCounterState()
            gson.fromJson(counterFile.readText(), PlaybackStatsCounterState::class.java)
                ?: PlaybackStatsCounterState()
        } catch (_: Throwable) {
            PlaybackStatsCounterState()
        }
    }

    private fun persist(nextState: PlaybackStatsCounterState) {
        synchronized(lock) {
            state = nextState
        }
        runCatching {
            counterFile.writeTextAtomically(gson.toJson(nextState))
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist stats counters", error)
        }
    }

    private fun updateShardList(
        shards: List<SyncPlaybackCounterShard>,
        deviceId: String,
        epochStartedAt: Long,
        listenedMs: Long,
        playCountIncrement: Int,
        playedAt: Long
    ): List<SyncPlaybackCounterShard> {
        val normalized = SyncPlaybackStatMapper.normalizeCounterShards(shards)
        val index = normalized.indexOfFirst {
            it.deviceId == deviceId && it.epochStartedAt == epochStartedAt
        }
        val existing = normalized.getOrNull(index)
        val updated = if (existing == null) {
            SyncPlaybackCounterShard(
                deviceId = deviceId,
                epochStartedAt = epochStartedAt,
                totalListenMs = listenedMs.coerceAtLeast(0L),
                playCount = playCountIncrement.coerceAtLeast(0),
                firstPlayedAt = playedAt,
                lastPlayedAt = playedAt
            )
        } else {
            existing.copy(
                totalListenMs = existing.totalListenMs + listenedMs.coerceAtLeast(0L),
                playCount = existing.playCount + playCountIncrement.coerceAtLeast(0),
                firstPlayedAt = minPositivePlayedAt(existing.firstPlayedAt, playedAt),
                lastPlayedAt = maxOf(existing.lastPlayedAt, playedAt)
            )
        }
        return normalized.toMutableList().apply {
            if (index >= 0) {
                this[index] = updated
            } else {
                add(updated)
            }
        }.let(SyncPlaybackStatMapper::normalizeCounterShards)
    }

    private fun syncCounterDeviceId(): String {
        return runCatching { syncStorage.getOrCreateDeviceId() }
            .getOrElse { error ->
                NPLogger.w("PlaybackStatsRepo", "Failed to read sync device id", error)
                "local"
            }
    }

    private fun ensureEpoch(
        state: PlaybackStatsCounterState,
        epochStartedAt: Long
    ): PlaybackStatsCounterState {
        if (state.epochStartedAt == epochStartedAt) return state
        return PlaybackStatsCounterState(epochStartedAt = epochStartedAt)
    }

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }
}
