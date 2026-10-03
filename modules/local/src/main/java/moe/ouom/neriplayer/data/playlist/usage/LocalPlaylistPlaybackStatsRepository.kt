package moe.ouom.neriplayer.data.playlist.usage

import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlayBucket
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistHotEntry
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackSyncSnapshot

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackEventConflictException
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.stats.resolvePlaybackStatsTimeRange
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.model.sync.LocalPlaylistPlaybackSyncResult
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.common.io.writeTextAtomically
import java.io.File
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest

private data class LocalPlaylistPlaybackEvent(val id: String, val playlistId: Long, val playedAt: Long) {
    val payloadHash: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$playlistId|$playedAt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class LocalPlaylistPlaybackStatsRepository private constructor(
    context: Context,
    private val roomStore: LocalPlaylistPlaybackRoomStore? = null
) {
    private val appContext = context.applicationContext

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
    private val file = File(appContext.filesDir, "local_playlist_playback_stats.json")
    private val syncStorage by lazy { SecureTokenStorage(appContext) }
    private val fallbackCounterDeviceId = "local-playlist-playback-${UUID.randomUUID()}"
    private val mutex = Mutex()
    @Volatile
    private var roomStorageEnabled = roomStore != null
    @Volatile
    private var initialized = false
    private var initialLoadFailure: Exception? = null
    private val _stats = MutableStateFlow(loadInitialStats())
    @Volatile
    private var persistedStats = _stats.value
    @Volatile
    private var baselineTrusted = initialized
    private var pendingUiChanges = false
    private val recordedEvents = linkedMapOf<String, LocalPlaylistPlaybackEvent>()
    val statsFlow: StateFlow<List<LocalPlaylistPlaybackStat>> = _stats

    private fun loadInitialStats(): List<LocalPlaylistPlaybackStat> {
        return runBlocking(Dispatchers.IO) {
            tryLoadStats()?.also { initialized = true }.orEmpty()
        }
    }

    private suspend fun loadTrustedStats(): List<LocalPlaylistPlaybackStat> {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            // 读取失败时不能把旧 JSON 当成完整主存
            val roomStats = activeRoomStore.readIfRoomPrimary()
            if (roomStats != null) {
                LegacyJsonCleanupRequests.schedule(
                    appContext,
                    "local-playlist-playback-room-load"
                )
                return normalizeLocalPlaylistPlaybackStats(roomStats)
            }
        }

        val legacyStats = loadFromDisk()
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            runCatching {
                activeRoomStore.importLegacyAndPromote(legacyStats)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                roomStorageEnabled = false
                NPLogger.e(
                    "LocalPlaylistPlaybackRepo",
                    "Failed to promote local playlist playback JSON to Room",
                    error
                )
            }
            LegacyJsonCleanupRequests.schedule(
                appContext,
                "local-playlist-playback-import"
            )
        }
        return legacyStats
    }

    private suspend fun tryLoadStats(): List<LocalPlaylistPlaybackStat>? = try {
        loadTrustedStats().also { initialLoadFailure = null }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        initialLoadFailure = error
        NPLogger.e("LocalPlaylistPlaybackRepo", "Playback stats unavailable; preserving storage for retry", error)
        null
    }

    private suspend fun ensureInitializedLocked(): Boolean {
        if (initialized) return recoverBaselineLocked()
        val loaded = tryLoadStats() ?: return false
        _stats.value = loaded
        persistedStats = loaded
        baselineTrusted = true
        pendingUiChanges = false
        initialized = true
        return true
    }

    private suspend fun recoverBaselineLocked(): Boolean = try {
        if (!baselineTrusted) {
            // 提交成功和取消通知可能交错，旧内存基线不能代替实际主存
            val roomStats = roomStore?.readIfRoomPrimary()
            val actual = roomStats?.let(::normalizeLocalPlaylistPlaybackStats) ?: loadFromDisk()
            currentCoroutineContext().ensureActive()
            persistedStats = actual
            roomStorageEnabled = roomStats != null
            baselineTrusted = true
        }
        if (!pendingUiChanges) _stats.value = persistedStats
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        initialLoadFailure = error
        NPLogger.e("LocalPlaylistPlaybackRepo", "Playback authority unavailable; refusing an uncertain baseline", error)
        false
    }

    private fun confirmPersistedStats(stats: List<LocalPlaylistPlaybackStat>) {
        persistedStats = stats
        baselineTrusted = true
        if (_stats.value == stats) pendingUiChanges = false
    }

    suspend fun awaitInitialized(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { ensureInitializedLocked() && flushPendingWritesLocked() }
    }

    private suspend fun flushPendingWritesLocked(): Boolean = try {
        persistSnapshotChecked(_stats.value)
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        NPLogger.e("LocalPlaylistPlaybackRepo", "Playback changes remain pending until persistence recovers", error)
        false
    }

    suspend fun recordPlayNow(
        playlistId: Long,
        playedAt: Long = System.currentTimeMillis(),
        eventId: String? = null
    ) {
        require(eventId == null || eventId.isNotBlank())
        mutex.withLock {
            if (!ensureInitializedLocked()) {
                if (eventId != null) throw IOException("Cannot record local playlist playback without a trusted baseline", initialLoadFailure)
                return@withLock
            }
            val event = eventId?.let { LocalPlaylistPlaybackEvent(it, playlistId, playedAt) }
            val previousEvent = eventId?.let(recordedEvents::get)
            if (previousEvent != null && previousEvent != event) throw IOException("Local playlist event identity reused with different content")
            if (event != null) {
                if (previousEvent == null) persistSnapshotChecked(_stats.value)
                ensureEventReceiptStorageLocked()
            }
            val current = _stats.value
            val previousUiChanges = pendingUiChanges
            val updated = if (previousEvent != null) current else recordLocalPlaylistPlay(
                current = current,
                playlistId = playlistId,
                playedAt = playedAt,
                deviceId = syncCounterDeviceId()
            )
            pendingUiChanges = true
            _stats.value = updated
            if (event == null) {
                persistSnapshot(updated)
            } else {
                // 已加入内存的事件在事务取消后继续重试原值，进程重启则由持久回执去重
                recordedEvents[event.id] = event
                try {
                    persistSnapshotChecked(updated, event)
                } catch (conflict: LocalPlaylistPlaybackEventConflictException) {
                    _stats.value = current
                    pendingUiChanges = previousUiChanges
                    if (previousEvent == null) recordedEvents.remove(event.id)
                    throw conflict
                }
                while (recordedEvents.size > PlaybackStatsRoomStore.RETAINED_EVENT_RECEIPTS) {
                    recordedEvents.remove(recordedEvents.keys.first())
                }
            }
        }
    }

    fun syncSnapshot(): LocalPlaylistPlaybackSyncSnapshot {
        if (!initialized || !baselineTrusted) throw IOException("Local playlist playback has no trusted snapshot", initialLoadFailure)
        val stats = _stats.value
        if (stats != persistedStats || !baselineTrusted) throw IOException("Local playlist playback still has uncommitted changes")
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
            if (!ensureInitializedLocked()) throw IOException("Cannot apply unknown local playlist playback", initialLoadFailure)
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
            persistSnapshotChecked(updated)
            currentCoroutineContext().ensureActive()
            _stats.value = updated
            pendingUiChanges = false
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
        val parsed = if (!file.exists()) {
            emptyList()
        } else {
            gson.fromJson<List<LocalPlaylistPlaybackStat>>(
                file.readText(),
                object : TypeToken<List<LocalPlaylistPlaybackStat>>() {}.type
            ) ?: throw IOException("Local playlist playback JSON has no valid list")
        }
        return normalizeLocalPlaylistPlaybackStats(parsed)
    }

    private suspend fun persistSnapshot(next: List<LocalPlaylistPlaybackStat>) {
        try {
            persistSnapshotChecked(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NPLogger.e("LocalPlaylistPlaybackRepo", "Failed to persist local playlist playback stats", error)
        }
    }

    private suspend fun ensureEventReceiptStorageLocked() {
        if (roomStorageEnabled && roomStore != null) return
        val activeRoomStore = roomStore
            ?: throw IOException("Durable playlist playback requires event receipt storage")
        val actual = activeRoomStore.readIfRoomPrimary()
        if (actual != null) {
            val normalized = normalizeLocalPlaylistPlaybackStats(actual)
            _stats.value = normalized
            pendingUiChanges = false
            confirmPersistedStats(normalized)
        } else {
            // 导入可能已提交后才取消，重试前必须重新确认实际主存
            baselineTrusted = false
            activeRoomStore.importLegacyAndPromote(persistedStats)
            confirmPersistedStats(persistedStats)
        }
        roomStorageEnabled = true
    }

    private suspend fun persistSnapshotChecked(next: List<LocalPlaylistPlaybackStat>, event: LocalPlaylistPlaybackEvent? = null) {
        currentCoroutineContext().ensureActive()
        if (!initialized || !baselineTrusted) throw IOException("Cannot persist unknown local playlist playback", initialLoadFailure)
        if (event != null && (!roomStorageEnabled || roomStore == null)) {
            throw IOException("Cannot acknowledge playlist playback without a durable event receipt")
        }
        if (next == persistedStats && event == null) {
            confirmPersistedStats(next)
            return
        }
        baselineTrusted = false
        if (event != null && roomStorageEnabled && roomStore != null) {
            val inserted = roomStore.writeIncrementalOnce(persistedStats, next, event.id, event.payloadHash, event.playedAt)
            val actual = if (inserted) next else {
                // 已有回执表示本次没有递增，确认主存失败时也不能把尝试值作为待保存的新计数
                _stats.value = persistedStats
                pendingUiChanges = false
                roomStore.readIfRoomPrimary()
                    ?: throw IOException("Local playlist event committed but primary confirmation is unavailable")
            }
            _stats.value = actual
            confirmPersistedStats(actual)
            return
        }
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            try {
                activeRoomStore.writeIncremental(persistedStats, next)
                confirmPersistedStats(next)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                roomStorageEnabled = false
                NPLogger.e(
                    "LocalPlaylistPlaybackRepo",
                    "Failed to write Room local playlist playback stats",
                    error
                )
            }
        }
        currentCoroutineContext().ensureActive()
        file.writeTextAtomically(gson.toJson(next))
        currentCoroutineContext().ensureActive()
        roomStore?.markLegacyJsonPrimary()
        confirmPersistedStats(next)
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
