package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.stats.resolvePlaybackStatsTimeRange
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.LocalPlaylistPlaybackSyncResult
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.sync.model.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.util.io.writeTextAtomically
import java.io.File
import java.util.UUID

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

class LocalPlaylistPlaybackStatsRepository private constructor(
    private val app: Context,
    private val roomStore: LocalPlaylistPlaybackRoomStore? = null
) {
    companion object {
        @Volatile
        private var instance: LocalPlaylistPlaybackStatsRepository? = null

        fun getInstance(context: Context): LocalPlaylistPlaybackStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: LocalPlaylistPlaybackStatsRepository(
                    context.applicationContext,
                    LocalPlaylistPlaybackRoomStore(
                        NeriUserDataDatabase.getInstance(context.applicationContext)
                    )
                ).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(app.filesDir, "local_playlist_playback_stats.json")
    private val syncStorage by lazy { SecureTokenStorage(app) }
    private val fallbackCounterDeviceId = "local-playlist-playback-${UUID.randomUUID()}"
    private val mutex = Mutex()
    @Volatile
    private var roomStorageEnabled = roomStore != null
    private val legacyProjectionMutex = Mutex()
    private var legacyProjectionGeneration = 0L
    private val _stats = MutableStateFlow(loadInitialStats())
    val statsFlow: StateFlow<List<LocalPlaylistPlaybackStat>> = _stats

    private fun loadInitialStats(): List<LocalPlaylistPlaybackStat> {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            val roomStats = runCatching {
                runBlocking { activeRoomStore.readIfRoomPrimary() }
            }.onFailure { error ->
                roomStorageEnabled = false
                NPLogger.e(
                    "LocalPlaylistPlaybackRepo",
                    "Failed to read Room local playlist playback stats",
                    error
                )
            }.getOrNull()
            if (roomStats != null) {
                return normalizeLocalPlaylistPlaybackStats(roomStats)
            }
        }

        val legacyStats = loadFromDisk()
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            runCatching {
                runBlocking { activeRoomStore.importLegacyAndPromote(legacyStats) }
            }.onFailure { error ->
                roomStorageEnabled = false
                NPLogger.e(
                    "LocalPlaylistPlaybackRepo",
                    "Failed to promote local playlist playback JSON to Room",
                    error
                )
            }
        }
        return legacyStats
    }

    suspend fun recordPlayNow(
        playlistId: Long,
        playedAt: Long = System.currentTimeMillis()
    ) {
        mutex.withLock {
            val current = _stats.value
            val updated = recordLocalPlaylistPlay(
                current = current,
                playlistId = playlistId,
                playedAt = playedAt,
                deviceId = syncCounterDeviceId()
            )
            _stats.value = updated
            persistSnapshot(current, updated)
        }
    }

    fun syncSnapshot(): LocalPlaylistPlaybackSyncSnapshot {
        val stats = _stats.value
        return LocalPlaylistPlaybackSyncSnapshot(
            stats = stats.map(LocalPlaylistPlaybackStat::toSyncStat),
            buckets = stats.flatMap { stat ->
                stat.dailyPlayBuckets.orEmpty().map { bucket ->
                    bucket.toSyncBucket(stat.playlistId)
                }
            }
        )
    }

    suspend fun applyMergedStats(
        stats: List<SyncLocalPlaylistPlaybackStat>,
        buckets: List<SyncLocalPlaylistPlaybackBucket>
    ) {
        mutex.withLock {
            val currentStats = _stats.value
            val currentSyncStats = currentStats.map(LocalPlaylistPlaybackStat::toSyncStat)
            val currentSyncBuckets = currentStats.flatMap { stat ->
                stat.dailyPlayBuckets.orEmpty().map { bucket ->
                    bucket.toSyncBucket(stat.playlistId)
                }
            }
            val finalized = SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
                stats = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackStats(
                    local = currentSyncStats,
                    remote = stats
                ),
                buckets = SyncPlaylistUsageStatsMergePolicy.mergeLocalPlaylistPlaybackBuckets(
                    local = currentSyncBuckets,
                    remote = buckets
                )
            )
            val updated = finalized.toLocalPlaybackStats()
            _stats.value = updated
            persistSnapshot(currentStats, updated)
        }
    }

    fun playCountFor(playlistId: Long): Long {
        return _stats.value
            .firstOrNull { stat -> stat.playlistId == playlistId }
            ?.totalPlayCount
            ?: 0L
    }

    fun hotLocalPlaylists(
        period: PlaybackStatsPeriod,
        nowMillis: Long = System.currentTimeMillis()
    ): List<LocalPlaylistHotEntry> {
        return localPlaylistHotEntriesForPeriod(_stats.value, period, nowMillis)
    }

    private fun loadFromDisk(): List<LocalPlaylistPlaybackStat> {
        val parsed = runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                gson.fromJson<List<LocalPlaylistPlaybackStat>>(
                    file.readText(),
                    object : TypeToken<List<LocalPlaylistPlaybackStat>>() {}.type
                ).orEmpty()
            }
        }.getOrDefault(emptyList())
        return normalizeLocalPlaylistPlaybackStats(parsed)
    }

    private fun persist(stats: List<LocalPlaylistPlaybackStat>) {
        runCatching {
            file.writeTextAtomically(gson.toJson(stats))
        }
    }

    private suspend fun persistSnapshot(
        previous: List<LocalPlaylistPlaybackStat>,
        next: List<LocalPlaylistPlaybackStat>
    ) {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            val roomWriteSucceeded = runCatching {
                activeRoomStore.writeIncremental(previous, next)
            }.onFailure { error ->
                roomStorageEnabled = false
                NPLogger.e(
                    "LocalPlaylistPlaybackRepo",
                    "Failed to write Room local playlist playback stats",
                    error
                )
            }.isSuccess
            if (roomWriteSucceeded) {
                enqueueLegacyProjection(next)
                return
            }
        }
        persist(next)
        roomStore?.let { fallbackStore ->
            runCatching { fallbackStore.markLegacyJsonPrimary() }
                .onFailure { error ->
                    NPLogger.e(
                        "LocalPlaylistPlaybackRepo",
                        "Failed to mark local playlist playback JSON fallback state",
                        error
                    )
                }
        }
    }

    private fun enqueueLegacyProjection(
        stats: List<LocalPlaylistPlaybackStat>
    ) {
        val generation = synchronized(this) {
            legacyProjectionGeneration += 1L
            legacyProjectionGeneration
        }
        scope.launch {
            legacyProjectionMutex.withLock {
                val isLatest = synchronized(this@LocalPlaylistPlaybackStatsRepository) {
                    generation == legacyProjectionGeneration
                }
                if (!isLatest) return@withLock
                persist(stats)
            }
        }
    }

    private fun syncCounterDeviceId(): String {
        return runCatching { syncStorage.getOrCreateDeviceId() }
            .getOrDefault(fallbackCounterDeviceId)
    }
}

internal fun recordLocalPlaylistPlay(
    current: List<LocalPlaylistPlaybackStat>,
    playlistId: Long,
    playedAt: Long,
    deviceId: String = "local"
): List<LocalPlaylistPlaybackStat> {
    if (playlistId == 0L) return normalizeLocalPlaylistPlaybackStats(current)
    val normalized = normalizeLocalPlaylistPlaybackStats(current)
    val index = normalized.indexOfFirst { stat -> stat.playlistId == playlistId }
    val next = normalized.toMutableList()
    if (index < 0) {
        next += LocalPlaylistPlaybackStat(playlistId = playlistId).recordPlay(
            playedAt = playedAt,
            deviceId = deviceId
        )
    } else {
        next[index] = next[index].recordPlay(
            playedAt = playedAt,
            deviceId = deviceId
        )
    }
    return normalizeLocalPlaylistPlaybackStats(next)
}

internal fun normalizeLocalPlaylistPlaybackStats(
    stats: List<LocalPlaylistPlaybackStat>
): List<LocalPlaylistPlaybackStat> {
    val sanitizedStats = stats
        .filterNotNull()
        .map(LocalPlaylistPlaybackStat::withNormalizedLegacyCollections)
    val finalized = SyncPlaylistUsageStatsMergePolicy.finalizeLocalPlaylistPlaybackStats(
        stats = sanitizedStats.map(LocalPlaylistPlaybackStat::toSyncStat),
        buckets = sanitizedStats.flatMap { stat ->
            stat.dailyPlayBuckets.map { bucket ->
                bucket.toSyncBucket(stat.playlistId)
            }
        }
    )
    return finalized.toLocalPlaybackStats()
}

internal fun localPlaylistHotEntriesForPeriod(
    stats: List<LocalPlaylistPlaybackStat>,
    period: PlaybackStatsPeriod,
    nowMillis: Long
): List<LocalPlaylistHotEntry> {
    val range = period.resolvePlaybackStatsTimeRange(nowMillis)
    return stats.asSequence()
        .map { stat ->
            LocalPlaylistHotEntry(
                playlistId = stat.playlistId,
                playCount = stat.playCountIn(range.startInclusive, range.endExclusive)
            )
        }
        .filter { entry -> entry.playCount > 0L }
        .sortedWith(
            compareByDescending<LocalPlaylistHotEntry> { entry -> entry.playCount }
                .thenBy { entry -> entry.playlistId }
        )
        .toList()
}

private fun LocalPlaylistPlaybackStat.playCountIn(
    startInclusive: Long?,
    endExclusive: Long
): Long {
    if (startInclusive == null) return totalPlayCount.coerceAtLeast(0L)
    return dailyPlayBuckets.orEmpty().asSequence()
        .filter { bucket ->
            bucket.dayStartAt in startInclusive..<endExclusive
        }
        .sumOf { bucket -> bucket.playCount.coerceAtLeast(0L) }
}

private fun LocalPlaylistPlaybackStat.recordPlay(
    playedAt: Long,
    deviceId: String
): LocalPlaylistPlaybackStat {
    val totalCounter = updateLocalPlaylistCounter(
        totalCount = totalPlayCount,
        firstOccurredAt = firstPlayedAt,
        lastOccurredAt = lastPlayedAt,
        counterBaseCount = counterBasePlayCount,
        counterShards = counterShards.orEmpty(),
        deviceId = deviceId,
        occurredAt = playedAt
    )
    val dayStartAt = playbackStatsDayStartAt(playedAt)
    val buckets = dailyPlayBuckets.orEmpty().toMutableList()
    val index = buckets.indexOfFirst { it.dayStartAt == dayStartAt }
    val currentBucket = buckets.getOrNull(index) ?: LocalPlaylistPlayBucket(
        dayStartAt = dayStartAt,
        playCount = 0L
    )
    val updatedBucket = currentBucket.updateWithPlay(
        playedAt = playedAt,
        deviceId = deviceId
    )
    if (index >= 0) {
        buckets[index] = updatedBucket
    } else {
        buckets += updatedBucket
    }
    return copy(
        totalPlayCount = totalCounter.totalCount,
        firstPlayedAt = totalCounter.firstOccurredAt,
        lastPlayedAt = totalCounter.lastOccurredAt,
        counterBasePlayCount = totalCounter.counterBaseCount,
        counterShards = totalCounter.counterShards,
        dailyPlayBuckets = buckets
    )
}

private fun LocalPlaylistPlayBucket.updateWithPlay(
    playedAt: Long,
    deviceId: String
): LocalPlaylistPlayBucket {
    val counter = updateLocalPlaylistCounter(
        totalCount = playCount,
        firstOccurredAt = firstPlayedAt,
        lastOccurredAt = lastPlayedAt,
        counterBaseCount = counterBasePlayCount,
        counterShards = counterShards.orEmpty(),
        deviceId = deviceId,
        occurredAt = playedAt
    )
    return copy(
        playCount = counter.totalCount,
        firstPlayedAt = counter.firstOccurredAt,
        lastPlayedAt = counter.lastOccurredAt,
        counterBasePlayCount = counter.counterBaseCount,
        counterShards = counter.counterShards
    )
}

private fun updateLocalPlaylistCounter(
    totalCount: Long,
    firstOccurredAt: Long,
    lastOccurredAt: Long,
    counterBaseCount: Long,
    counterShards: List<SyncPlaybackCounterShard>,
    deviceId: String,
    occurredAt: Long
): LocalPlaylistCounter {
    val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(counterShards)
    val existingShardCount = normalizedShards.fold(0L) { total, shard ->
        total.saturatingAdd(shard.playCount.toLong().coerceAtLeast(0L))
    }
    val baseCount = if (normalizedShards.isEmpty()) {
        totalCount.coerceAtLeast(0L)
    } else {
        maxOf(
            counterBaseCount.coerceAtLeast(0L),
            totalCount.minus(existingShardCount).coerceAtLeast(0L)
        )
    }
    val index = normalizedShards.indexOfFirst { shard ->
        shard.deviceId == deviceId && shard.epochStartedAt == 0L
    }
    val existing = normalizedShards.getOrNull(index)
    val updatedShard = if (existing == null) {
        SyncPlaybackCounterShard(
            deviceId = deviceId,
            epochStartedAt = 0L,
            playCount = 1,
            firstPlayedAt = occurredAt,
            lastPlayedAt = occurredAt
        )
    } else {
        existing.copy(
            playCount = existing.playCount.saturatingIncrement(),
            firstPlayedAt = minPositiveTimestamp(existing.firstPlayedAt, occurredAt),
            lastPlayedAt = maxOf(existing.lastPlayedAt, occurredAt)
        )
    }
    val nextShards = normalizedShards.toMutableList().apply {
        if (index >= 0) {
            this[index] = updatedShard
        } else {
            add(updatedShard)
        }
    }.let(SyncPlaybackStatMapper::normalizeCounterShards)
    val nextShardCount = nextShards.fold(0L) { total, shard ->
        total.saturatingAdd(shard.playCount.toLong().coerceAtLeast(0L))
    }
    return LocalPlaylistCounter(
        totalCount = maxOf(totalCount.coerceAtLeast(0L), baseCount.saturatingAdd(nextShardCount)),
        firstOccurredAt = minPositiveTimestamp(firstOccurredAt, occurredAt),
        lastOccurredAt = maxOf(lastOccurredAt, occurredAt),
        counterBaseCount = baseCount,
        counterShards = nextShards
    )
}

private fun LocalPlaylistPlaybackStat.toSyncStat(): SyncLocalPlaylistPlaybackStat {
    return SyncLocalPlaylistPlaybackStat(
        playlistId = playlistId,
        totalPlayCount = totalPlayCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt,
        counterBasePlayCount = counterBasePlayCount,
        counterShards = counterShards.orEmpty().filterNotNull()
    )
}

private fun LocalPlaylistPlayBucket.toSyncBucket(
    playlistId: Long
): SyncLocalPlaylistPlaybackBucket {
    return SyncLocalPlaylistPlaybackBucket(
        dayStartAt = dayStartAt,
        playlistId = playlistId,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt,
        counterBasePlayCount = counterBasePlayCount,
        counterShards = counterShards.orEmpty().filterNotNull()
    )
}

private fun LocalPlaylistPlaybackStat.withNormalizedLegacyCollections(): LocalPlaylistPlaybackStat {
    return copy(
        counterShards = counterShards.orEmpty().filterNotNull(),
        dailyPlayBuckets = dailyPlayBuckets.orEmpty()
            .filterNotNull()
            .map(LocalPlaylistPlayBucket::withNormalizedLegacyCollections)
    )
}

private fun LocalPlaylistPlayBucket.withNormalizedLegacyCollections(): LocalPlaylistPlayBucket {
    return copy(counterShards = counterShards.orEmpty().filterNotNull())
}

private fun LocalPlaylistPlaybackSyncResult.toLocalPlaybackStats(): List<LocalPlaylistPlaybackStat> {
    val bucketsByPlaylistId = buckets.groupBy(SyncLocalPlaylistPlaybackBucket::playlistId)
    return stats.map { stat ->
        LocalPlaylistPlaybackStat(
            playlistId = stat.playlistId,
            totalPlayCount = stat.totalPlayCount,
            firstPlayedAt = stat.firstPlayedAt,
            lastPlayedAt = stat.lastPlayedAt,
            counterBasePlayCount = stat.counterBasePlayCount,
            counterShards = stat.counterShards,
            dailyPlayBuckets = bucketsByPlaylistId[stat.playlistId]
                .orEmpty()
                .map { bucket ->
                    LocalPlaylistPlayBucket(
                        dayStartAt = bucket.dayStartAt,
                        playCount = bucket.playCount,
                        firstPlayedAt = bucket.firstPlayedAt,
                        lastPlayedAt = bucket.lastPlayedAt,
                        counterBasePlayCount = bucket.counterBasePlayCount,
                        counterShards = bucket.counterShards
                    )
                }
                .sortedBy(LocalPlaylistPlayBucket::dayStartAt)
        )
    }.filter { stat ->
        stat.totalPlayCount > 0L || stat.dailyPlayBuckets.isNotEmpty()
    }.sortedBy(LocalPlaylistPlaybackStat::playlistId)
}

private data class LocalPlaylistCounter(
    val totalCount: Long,
    val firstOccurredAt: Long,
    val lastOccurredAt: Long,
    val counterBaseCount: Long,
    val counterShards: List<SyncPlaybackCounterShard>
)

private fun Int.saturatingIncrement(): Int {
    return if (this == Int.MAX_VALUE) Int.MAX_VALUE else this + 1
}

private fun Long.saturatingAdd(other: Long): Long {
    return if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}

private fun minPositiveTimestamp(left: Long, right: Long): Long {
    return when {
        left <= 0L -> right.coerceAtLeast(0L)
        right <= 0L -> left.coerceAtLeast(0L)
        else -> minOf(left, right)
    }
}
