package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot

import android.content.Context
import com.google.gson.Gson
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.Collections

private data class PlaybackStatsCounterState(
    val epochStartedAt: Long = 0L,
    val trackShardsByIdentity: Map<String, List<SyncPlaybackCounterShard>> = Collections.unmodifiableMap(emptyMap()),
    val dailyShardsByBucketKey: Map<String, List<SyncPlaybackCounterShard>> = Collections.unmodifiableMap(emptyMap())
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
    private var state = PlaybackStatsCounterState()

    fun loadLegacy() {
        val loaded = load()
        updateState(loaded.copy(
            trackShardsByIdentity = loaded.trackShardsByIdentity.mapValues { (_, shards) -> normalizeOwnedShards(shards) },
            dailyShardsByBucketKey = loaded.dailyShardsByBucketKey.mapValues { (_, shards) -> normalizeOwnedShards(shards) }
        ))
    }

    fun snapshot(): PlaybackStatsSyncCounterSnapshot {
        val current = synchronized(lock) { state }
        return PlaybackStatsSyncCounterSnapshot(
            trackShardsByIdentity = current.trackShardsByIdentity,
            dailyShardsByBucketKey = current.dailyShardsByBucketKey
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

        updateState(
            current.copy(
                trackShardsByIdentity = updatedTrackShards,
                dailyShardsByBucketKey = updatedDailyShards
            )
        )
    }

    fun reset(epochStartedAt: Long) {
        updateState(
            PlaybackStatsCounterState(epochStartedAt = epochStartedAt.coerceAtLeast(0L))
        )
    }

    fun removeTracks(keys: Set<String>) {
        if (keys.isEmpty()) return
        val current = synchronized(lock) { state }
        updateState(
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
                stat.identityKey to normalizeOwnedShards(stat.counterShards)
            }
            .filterValues { it.isNotEmpty() }
        val dailyShards = syncDailyStats
            .associate { bucket ->
                PlaybackStatsSyncCounterSnapshot.dailyCounterKey(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                ) to normalizeOwnedShards(bucket.counterShards)
            }
            .filterValues { it.isNotEmpty() }
        updateState(
            PlaybackStatsCounterState(
                epochStartedAt = epochStartedAt.coerceAtLeast(0L),
                trackShardsByIdentity = trackShards,
                dailyShardsByBucketKey = dailyShards
            )
        )
    }

    fun replaceFromRoom(
        snapshot: PlaybackStatsSyncCounterSnapshot,
        epochStartedAt: Long
    ) {
        val trackShards = snapshot.trackShardsByIdentity
            .mapValues { (_, shards) -> normalizeOwnedShards(shards) }
            .filterValues { it.isNotEmpty() }
        val dailyShards = snapshot.dailyShardsByBucketKey
            .mapValues { (_, shards) -> normalizeOwnedShards(shards) }
            .filterValues { it.isNotEmpty() }
        updateState(
            PlaybackStatsCounterState(
                epochStartedAt = epochStartedAt.coerceAtLeast(0L),
                trackShardsByIdentity = trackShards,
                dailyShardsByBucketKey = dailyShards
            )
        )
    }

    fun epochStartedAt(): Long {
        return synchronized(lock) { state.epochStartedAt }
    }

    fun persistLegacyProjection(
        snapshot: PlaybackStatsSyncCounterSnapshot,
        epochStartedAt: Long
    ): Boolean {
        return persistToDisk(PlaybackStatsCounterState(
            epochStartedAt = epochStartedAt,
            trackShardsByIdentity = snapshot.trackShardsByIdentity,
            dailyShardsByBucketKey = snapshot.dailyShardsByBucketKey
        ))
    }

    private fun load(): PlaybackStatsCounterState {
        if (!counterFile.exists()) return PlaybackStatsCounterState()
        return gson.fromJson(counterFile.readText(), PlaybackStatsCounterState::class.java)
            ?: throw IOException("Playback stats counter JSON has no valid state")
    }

    private fun updateState(nextState: PlaybackStatsCounterState) {
        synchronized(lock) {
            // 新状态独占其 Map，旧快照只共享不可修改的分片列表
            state = nextState.copy(
                trackShardsByIdentity = Collections.unmodifiableMap(nextState.trackShardsByIdentity),
                dailyShardsByBucketKey = Collections.unmodifiableMap(nextState.dailyShardsByBucketKey)
            )
        }
    }

    private fun persistToDisk(nextState: PlaybackStatsCounterState): Boolean {
        return synchronized(persistFileLock) {
            runCatching {
                counterFile.writeTextAtomically(gson.toJson(nextState))
                true
            }.onFailure { error ->
                NPLogger.e("PlaybackStatsRepo", "Failed to persist stats counters", error)
            }.getOrDefault(false)
        }
    }

    private val persistFileLock = Any()

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
        }.let(::normalizeOwnedShards)
    }

    private fun normalizeOwnedShards(shards: List<SyncPlaybackCounterShard?>?): List<SyncPlaybackCounterShard> {
        return Collections.unmodifiableList(SyncPlaybackStatMapper.normalizeCounterShards(shards))
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
