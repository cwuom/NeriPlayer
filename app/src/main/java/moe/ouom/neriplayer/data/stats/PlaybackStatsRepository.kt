package moe.ouom.neriplayer.data.stats

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlaybackStatsRoomSnapshot
import moe.ouom.neriplayer.data.local.database.store.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import java.io.File

data class TrackStat(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val coverUrl: String?,
    val durationMs: Long,
    val totalListenMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long,
    val mediaUri: String?,
    val localFilePath: String?,
    val localFileName: String?,
    val customName: String?,
    val customArtist: String?,
    val customCoverUrl: String?,
    val identityKey: String
)

internal data class PlaybackStatsPersistenceSnapshot(
    val stats: List<TrackStat>,
    val dailyStats: List<PlaybackStatBucket>,
    val counterSnapshot: PlaybackStatsSyncCounterSnapshot,
    val counterEpochStartedAt: Long,
    val clearedAt: Long
)

private fun PlaybackStatsRoomSnapshot.toPersistenceSnapshot():
    PlaybackStatsPersistenceSnapshot {
    return PlaybackStatsPersistenceSnapshot(
        stats = stats,
        dailyStats = dailyStats,
        counterSnapshot = counterSnapshot,
        counterEpochStartedAt = counterEpochStartedAt,
        clearedAt = clearedAt
    )
}

private data class PlaybackStatsMetadata(
    val clearedAt: Long = 0L
)

internal fun mergePlaybackStatsRoomRecovery(
    roomSnapshot: PlaybackStatsPersistenceSnapshot,
    recoveryBaseline: PlaybackStatsPersistenceSnapshot,
    currentSnapshot: PlaybackStatsPersistenceSnapshot
): PlaybackStatsPersistenceSnapshot {
    val effectiveClearedAt = maxOf(roomSnapshot.clearedAt, currentSnapshot.clearedAt)
    val baselineStatsByKey = recoveryBaseline.stats.associateBy(TrackStat::identityKey)
    val currentStatsByKey = currentSnapshot.stats.associateBy(TrackStat::identityKey)
    val baselineDailyByKey = recoveryBaseline.dailyStats.associateBy { bucket ->
        bucket.dayStartAt to bucket.identityKey
    }
    val currentDailyByKey = currentSnapshot.dailyStats.associateBy { bucket ->
        bucket.dayStartAt to bucket.identityKey
    }
    val changedTrackKeys = (
        baselineStatsByKey.keys + currentStatsByKey.keys +
            recoveryBaseline.counterSnapshot.trackShardsByIdentity.keys +
            currentSnapshot.counterSnapshot.trackShardsByIdentity.keys
        ).filter { key ->
            baselineStatsByKey[key] != currentStatsByKey[key] ||
                recoveryBaseline.counterSnapshot.trackShards(key) !=
                    currentSnapshot.counterSnapshot.trackShards(key)
        }.toSet()
    val changedDailyKeys = (
        baselineDailyByKey.keys + currentDailyByKey.keys +
            recoveryBaseline.counterSnapshot.dailyShardsByBucketKey.keys.map(
                ::playbackStatsDailyKey
            ) +
            currentSnapshot.counterSnapshot.dailyShardsByBucketKey.keys.map(
                ::playbackStatsDailyKey
            )
        ).filter { key ->
            baselineDailyByKey[key] != currentDailyByKey[key] ||
                recoveryBaseline.counterSnapshot.dailyShards(
                    dayStartAt = key.first,
                    identityKey = key.second
                ) != currentSnapshot.counterSnapshot.dailyShards(
                    dayStartAt = key.first,
                    identityKey = key.second
                )
        }.toSet()
    val removedTrackKeys = baselineStatsByKey.keys - currentStatsByKey.keys
    val removedDailyKeys = baselineDailyByKey.keys - currentDailyByKey.keys
    val roomStatsByKey = roomSnapshot.stats
        .filter { stat ->
            stat.identityKey !in removedTrackKeys &&
                shouldKeepTrackStatAfterClear(stat, effectiveClearedAt)
        }
        .associateBy(TrackStat::identityKey)
    val roomDailyByKey = roomSnapshot.dailyStats
        .filter { bucket ->
            bucket.identityKey !in removedTrackKeys &&
                (bucket.dayStartAt to bucket.identityKey) !in removedDailyKeys &&
                shouldKeepDailyBucketAfterClear(bucket, effectiveClearedAt)
        }
        .associateBy { bucket -> bucket.dayStartAt to bucket.identityKey }
    val changedCurrentStats = currentSnapshot.stats.filter { stat ->
        stat.identityKey in changedTrackKeys &&
            shouldKeepTrackStatAfterClear(stat, effectiveClearedAt)
    }
    val changedCurrentDailyStats = currentSnapshot.dailyStats.filter { bucket ->
        (bucket.dayStartAt to bucket.identityKey) in changedDailyKeys &&
            bucket.identityKey !in removedTrackKeys &&
            shouldKeepDailyBucketAfterClear(bucket, effectiveClearedAt)
    }
    val mergedStats = SyncPlaybackStatsMergePolicy.merge(
        local = roomStatsByKey.values.map { stat ->
            SyncPlaybackStatMapper.fromTrackStat(
                stat = stat,
                counterShards = roomSnapshot.counterSnapshot.trackShards(stat.identityKey)
            )
        },
        remote = changedCurrentStats.map { stat ->
            SyncPlaybackStatMapper.fromTrackStat(
                stat = stat,
                counterShards = currentSnapshot.counterSnapshot.trackShards(stat.identityKey)
            )
        },
        playbackStatsClearedAt = effectiveClearedAt
    )
    val mergedDailyStats = SyncPlaybackStatsMergePolicy.mergeBuckets(
        local = roomDailyByKey.values.map { bucket ->
            SyncPlaybackStatMapper.fromPlaybackStatBucket(
                bucket = bucket,
                counterShards = roomSnapshot.counterSnapshot.dailyShards(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                )
            )
        },
        remote = changedCurrentDailyStats.map { bucket ->
            SyncPlaybackStatMapper.fromPlaybackStatBucket(
                bucket = bucket,
                counterShards = currentSnapshot.counterSnapshot.dailyShards(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                )
            )
        },
        playbackStatsClearedAt = effectiveClearedAt
    )
    val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
        mergedStats = mergedStats,
        mergedBuckets = mergedDailyStats
    )
    val recoveredStats = finalized.stats.map { merged ->
        val source = if (merged.identityKey in changedTrackKeys) {
            currentStatsByKey[merged.identityKey]
        } else {
            roomStatsByKey[merged.identityKey]
        }
        source?.applyRecoveredSyncCounters(merged) ?: merged.toRecoveredTrackStat()
    }
    val recoveredDailyStats = finalized.buckets.map { merged ->
        val key = merged.dayStartAt to merged.identityKey
        val source = if (key in changedDailyKeys) {
            currentDailyByKey[key]
        } else {
            roomDailyByKey[key]
        }
        source?.let { bucket -> mergeDailyBucket(bucket, merged) }
            ?: merged.toPlaybackStatBucket()
    }
    val recoveredCounterSnapshot = PlaybackStatsSyncCounterSnapshot(
        trackShardsByIdentity = finalized.stats.mapNotNull { stat ->
            val shards = SyncPlaybackStatMapper.normalizeCounterShards(stat.counterShards)
            stat.identityKey.takeIf { it.isNotBlank() && shards.isNotEmpty() }?.let { key ->
                key to shards
            }
        }.toMap(),
        dailyShardsByBucketKey = finalized.buckets.mapNotNull { bucket ->
            val shards = SyncPlaybackStatMapper.normalizeCounterShards(bucket.counterShards)
            if (bucket.identityKey.isBlank() || shards.isEmpty()) {
                null
            } else {
                PlaybackStatsSyncCounterSnapshot.dailyCounterKey(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                ) to shards
            }
        }.toMap()
    )
    val localEpochChanged = currentSnapshot.counterEpochStartedAt !=
        recoveryBaseline.counterEpochStartedAt
    return PlaybackStatsPersistenceSnapshot(
        stats = recoveredStats,
        dailyStats = recoveredDailyStats,
        counterSnapshot = recoveredCounterSnapshot,
        counterEpochStartedAt = maxOf(
            roomSnapshot.counterEpochStartedAt,
            if (localEpochChanged) currentSnapshot.counterEpochStartedAt else 0L,
            effectiveClearedAt
        ),
        clearedAt = effectiveClearedAt
    )
}

private fun playbackStatsDailyKey(counterKey: String): Pair<Long, String> {
    val dayStartAt = counterKey.substringBefore('|').toLongOrNull() ?: 0L
    return dayStartAt to counterKey.substringAfter('|', missingDelimiterValue = counterKey)
}

private fun SyncTrackStat.toRecoveredTrackStat(): TrackStat {
    return TrackStat(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = null,
        localFileName = null,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        identityKey = identityKey
    )
}

private fun TrackStat.applyRecoveredSyncCounters(remote: SyncTrackStat): TrackStat {
    val useRemoteMetadata = remote.lastPlayedAt > lastPlayedAt
    return copy(
        totalListenMs = remote.totalListenMs,
        playCount = remote.playCount,
        lastPlayedAt = remote.lastPlayedAt,
        firstPlayedAt = remote.firstPlayedAt,
        name = if (useRemoteMetadata) remote.name else name,
        artist = if (useRemoteMetadata) remote.artist else artist,
        coverUrl = if (useRemoteMetadata) remote.coverUrl else coverUrl
    )
}

private const val MIN_LISTEN_MS_FOR_PLAY_COUNT = 30_000L

class PlaybackStatsRepository private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "playback_stats.json") }
    private val dailyFile: File by lazy { File(app.filesDir, "playback_stats_daily.json") }
    private val metadataFile: File by lazy { File(app.filesDir, "playback_stats_meta.json") }
    private val mutex = Mutex()
    private val roomPersistenceMutex = Mutex()
    private var persistJob: Job? = null
    private var retryJob: Job? = null
    private var persistGeneration = 0L
    private var pendingPersistence: PlaybackStatsPersistenceSnapshot? = null
    private var persistenceDirty = false
    private var pendingSyncAfterPersistence = false
    private val counterStore = PlaybackStatsCounterStore(app, gson)
    private val roomStore = PlaybackStatsRoomStore(
        NeriUserDataDatabase.getInstance(app.applicationContext)
    )
    @Volatile
    private var roomStorageEnabled = true
    private var roomRecoveryBaseline: PlaybackStatsPersistenceSnapshot? = null
    private val initialState = loadInitialState()
    private val _stats = MutableStateFlow(initialState.stats)
    private val _statsClearedAt = MutableStateFlow(initialState.clearedAt)
    private val _dailyStats = MutableStateFlow(initialState.dailyStats)
    private var persistedSnapshot = initialState
    val statsFlow: StateFlow<List<TrackStat>> = _stats
    val dailyStatsFlow: StateFlow<List<PlaybackStatBucket>> = _dailyStats
    val statsClearedAtFlow: StateFlow<Long> = _statsClearedAt

    init {
        reconcileLoadedStats()
        scheduleRoomRecovery()
    }

    private fun loadInitialState(): PlaybackStatsPersistenceSnapshot {
        var needsRoomRecovery = false
        val roomSnapshot = runCatching {
            runBlocking { roomStore.readIfRoomPrimary() }
        }.onFailure { error ->
            roomStorageEnabled = false
            needsRoomRecovery = true
            NPLogger.e(
                "PlaybackStatsRepo",
                "Failed to read Room playback stats",
                error
            )
        }.getOrNull()
        if (roomSnapshot != null) {
            counterStore.replaceFromRoom(
                snapshot = roomSnapshot.counterSnapshot,
                epochStartedAt = roomSnapshot.counterEpochStartedAt
            )
            LegacyJsonCleanupScheduler.schedule(app, "playback-stats-room-load")
            return roomSnapshot.toPersistenceSnapshot()
        }

        val clearedAt = loadMetadata().clearedAt
        val stats = loadFromDisk()
        val dailyStats = loadDailyStatsFromDisk(
            stats = stats,
            clearedAt = clearedAt
        )
        val legacyState = PlaybackStatsPersistenceSnapshot(
            stats = stats,
            dailyStats = dailyStats,
            counterSnapshot = counterStore.snapshot(),
            counterEpochStartedAt = counterStore.epochStartedAt(),
            clearedAt = clearedAt
        )
        if (roomStorageEnabled) {
            runCatching {
                runBlocking {
                    roomStore.importLegacyAndPromote(
                        stats = legacyState.stats,
                        dailyStats = legacyState.dailyStats,
                        counterSnapshot = legacyState.counterSnapshot,
                        counterEpochStartedAt = legacyState.counterEpochStartedAt,
                        clearedAt = legacyState.clearedAt
                    )
                }
                LegacyJsonCleanupScheduler.schedule(app, "playback-stats-import")
            }.onFailure { error ->
                roomStorageEnabled = false
                needsRoomRecovery = true
                NPLogger.e(
                    "PlaybackStatsRepo",
                    "Failed to promote playback stats JSON to Room",
                    error
                )
            }
        }
        if (needsRoomRecovery) {
            roomRecoveryBaseline = legacyState
        }
        return legacyState
    }

    private fun reconcileLoadedStats() {
        if (_stats.value.isEmpty() && _dailyStats.value.isEmpty()) return

        val counterSnapshot = counterStore.snapshot()
        val reconciled = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(
            stats = _stats.value.map { stat ->
                SyncPlaybackStatMapper.fromTrackStat(
                    stat = stat,
                    counterShards = counterSnapshot.trackShards(stat.identityKey)
                )
            },
            buckets = _dailyStats.value.map { bucket ->
                SyncPlaybackStatMapper.fromPlaybackStatBucket(
                    bucket = bucket,
                    counterShards = counterSnapshot.dailyShards(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    )
                )
            }
        )
        val localStats = _stats.value.associateBy { it.identityKey }
        val updated = reconciled.map { stat ->
            localStats[stat.identityKey]?.applySyncedCounters(stat) ?: stat.toTrackStat()
        }
        if (updated == _stats.value) return

        _stats.value = updated
        persistenceDirty = true
        scope.launch {
            mutex.withLock {
                persistSnapshot(
                    currentPersistenceSnapshot()
                )
            }
        }
    }

    private fun SyncTrackStat.toTrackStat(): TrackStat {
        return TrackStat(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            coverUrl = coverUrl,
            durationMs = durationMs,
            totalListenMs = totalListenMs,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            mediaUri = mediaUri,
            localFilePath = null,
            localFileName = null,
            customName = null,
            customArtist = null,
            customCoverUrl = null,
            identityKey = identityKey
        )
    }

    private fun TrackStat.applySyncedCounters(remote: SyncTrackStat): TrackStat {
        val useRemoteMetadata = remote.lastPlayedAt > lastPlayedAt
        return copy(
            totalListenMs = remote.totalListenMs,
            playCount = remote.playCount,
            lastPlayedAt = remote.lastPlayedAt,
            firstPlayedAt = remote.firstPlayedAt,
            name = if (useRemoteMetadata) remote.name else name,
            artist = if (useRemoteMetadata) remote.artist else artist,
            coverUrl = if (useRemoteMetadata) remote.coverUrl else coverUrl
        )
    }

    private fun loadFromDisk(): List<TrackStat> {
        return try {
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            val type = object : TypeToken<List<TrackStat>>() {}.type
            gson.fromJson<List<TrackStat>>(raw, type).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun loadMetadata(): PlaybackStatsMetadata {
        return try {
            if (!metadataFile.exists()) return PlaybackStatsMetadata()
            gson.fromJson(metadataFile.readText(), PlaybackStatsMetadata::class.java)
                ?: PlaybackStatsMetadata()
        } catch (_: Throwable) {
            PlaybackStatsMetadata()
        }
    }

    private fun loadDailyStatsFromDisk(
        stats: List<TrackStat>,
        clearedAt: Long
    ): List<PlaybackStatBucket> {
        return try {
            if (!dailyFile.exists()) {
                return buildLegacyDailyStats(
                    stats = stats,
                    clearedAt = clearedAt
                )
            }
            val raw = dailyFile.readText()
            val type = object : TypeToken<List<PlaybackStatBucket>>() {}.type
            trimPlaybackStatBuckets(gson.fromJson<List<PlaybackStatBucket>>(raw, type).orEmpty())
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun currentPersistenceSnapshot(): PlaybackStatsPersistenceSnapshot {
        return PlaybackStatsPersistenceSnapshot(
            stats = _stats.value,
            dailyStats = _dailyStats.value,
            counterSnapshot = counterStore.snapshot(),
            counterEpochStartedAt = counterStore.epochStartedAt(),
            clearedAt = _statsClearedAt.value
        )
    }

    private fun schedulePersistenceLocked() {
        pendingPersistence = currentPersistenceSnapshot()
        persistenceDirty = true
        persistGeneration += 1L
        val generation = persistGeneration
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            val snapshot = synchronized(this@PlaybackStatsRepository) {
                if (generation != persistGeneration) {
                    null
                } else {
                    pendingPersistence.also {
                        pendingPersistence = null
                        persistJob = null
                    }
                }
            }
            snapshot?.let { persistSnapshot(it, generation) }
        }
    }

    private fun cancelScheduledPersistenceLocked() {
        persistGeneration += 1L
        persistJob?.cancel()
        persistJob = null
        pendingPersistence = null
    }

    private suspend fun persistSnapshot(
        snapshot: PlaybackStatsPersistenceSnapshot,
        expectedGeneration: Long? = null
    ) {
        roomPersistenceMutex.withLock {
            if (roomStorageEnabled) {
                val roomSucceeded = runCatching {
                    roomStore.writeIncremental(
                        previousStats = persistedSnapshot.stats,
                        nextStats = snapshot.stats,
                        previousDailyStats = persistedSnapshot.dailyStats,
                        nextDailyStats = snapshot.dailyStats,
                        previousCounterSnapshot = persistedSnapshot.counterSnapshot,
                        counterSnapshot = snapshot.counterSnapshot,
                        counterEpochStartedAt = snapshot.counterEpochStartedAt,
                        clearedAt = snapshot.clearedAt
                    )
                }.onFailure { error ->
                    NPLogger.e(
                        "PlaybackStatsRepo",
                        "Failed to write Room playback stats; keeping JSON migration data read-only",
                        error
                    )
                }.isSuccess
                if (roomSucceeded) {
                    persistedSnapshot = snapshot
                    markPersistenceClean(expectedGeneration)
                    cancelPendingRetry()
                    triggerPendingSyncAfterPersistence()
                    return@withLock
                }
            }
            if (roomStorageEnabled) {
                scheduleRoomRetry()
            } else {
                scheduleRoomRecovery()
            }
        }
    }

    private fun scheduleRoomRetry() {
        if (!roomStorageEnabled || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(ROOM_RETRY_DELAY_MS)
            mutex.withLock {
                retryJob = null
                persistSnapshot(currentPersistenceSnapshot())
            }
        }
    }

    private fun scheduleRoomRecovery() {
        if (roomStorageEnabled || roomRecoveryBaseline == null || retryJob?.isActive == true) {
            return
        }
        retryJob = scope.launch {
            delay(ROOM_RETRY_DELAY_MS)
            mutex.withLock {
                retryJob = null
                recoverRoomStorage()
            }
        }
    }

    private suspend fun recoverRoomStorage() {
        val baseline = roomRecoveryBaseline ?: return
        val current = currentPersistenceSnapshot()
        val recovered = roomPersistenceMutex.withLock {
            runCatching {
                if (roomStore.readIfRoomPrimary() == null) {
                    roomStore.importLegacyAndPromote(
                        stats = current.stats,
                        dailyStats = current.dailyStats,
                        counterSnapshot = current.counterSnapshot,
                        counterEpochStartedAt = current.counterEpochStartedAt,
                        clearedAt = current.clearedAt
                    )
                }
                val roomSnapshot = roomStore.readIfRoomPrimary()
                    ?.toPersistenceSnapshot()
                    ?: return@runCatching null
                mergePlaybackStatsRoomRecovery(
                    roomSnapshot = roomSnapshot,
                    recoveryBaseline = baseline,
                    currentSnapshot = current
                ).also { merged ->
                    roomStore.writeIncremental(
                        previousStats = roomSnapshot.stats,
                        nextStats = merged.stats,
                        previousDailyStats = roomSnapshot.dailyStats,
                        nextDailyStats = merged.dailyStats,
                        previousCounterSnapshot = roomSnapshot.counterSnapshot,
                        counterSnapshot = merged.counterSnapshot,
                        counterEpochStartedAt = merged.counterEpochStartedAt,
                        clearedAt = merged.clearedAt
                    )
                }
            }.onFailure { error ->
                NPLogger.e(
                    "PlaybackStatsRepo",
                    "Failed to recover Room playback stats without replaying JSON",
                    error
                )
            }.getOrNull()
        }
        if (recovered == null) {
            scheduleRoomRecovery()
            return
        }

        roomStorageEnabled = true
        roomRecoveryBaseline = null
        persistedSnapshot = recovered
        _stats.value = recovered.stats
        _dailyStats.value = recovered.dailyStats
        _statsClearedAt.value = recovered.clearedAt
        counterStore.replaceFromRoom(
            snapshot = recovered.counterSnapshot,
            epochStartedAt = recovered.counterEpochStartedAt
        )
        persistenceDirty = false
        markPersistenceClean(expectedGeneration = null)
        LegacyJsonCleanupScheduler.schedule(app, "playback-stats-room-recovery")
        triggerPendingSyncAfterPersistence()
    }

    private fun markPersistenceClean(expectedGeneration: Long?) {
        synchronized(this) {
            if (expectedGeneration == null || expectedGeneration == persistGeneration) {
                persistenceDirty = false
            }
        }
    }

    private fun requestSyncAfterPersistence() {
        synchronized(this) {
            pendingSyncAfterPersistence = true
        }
    }

    private fun triggerPendingSyncAfterPersistence() {
        val shouldTrigger = synchronized(this) {
            pendingSyncAfterPersistence.also {
                pendingSyncAfterPersistence = false
            }
        }
        if (shouldTrigger) {
            triggerSync()
        }
    }

    private fun cancelPendingRetry() {
        synchronized(this) {
            retryJob?.cancel()
            retryJob = null
        }
    }

    fun hasPendingWrites(): Boolean {
        return synchronized(this) {
            persistenceDirty || pendingPersistence != null || persistJob?.isActive == true
                || retryJob?.isActive == true
        }
    }

    suspend fun flushPendingWrites() {
        mutex.withLock<Unit> {
            val shouldPersist = synchronized(this@PlaybackStatsRepository) {
                persistenceDirty || pendingPersistence != null || persistJob?.isActive == true
                    || retryJob?.isActive == true
            }
            cancelScheduledPersistenceLocked()
            if (shouldPersist) {
                persistSnapshot(currentPersistenceSnapshot())
            }
        }
    }

    private fun triggerSync() {
        runCatching {
            GitHubSyncWorker.scheduleDelayedSync(
                app,
                triggerByUserAction = false,
                markMutation = true
            )
            WebDavSyncWorker.scheduleDelayedSync(
                app,
                triggerByUserAction = false,
                markMutation = true
            )
        }
    }

    fun syncCounterSnapshot(): PlaybackStatsSyncCounterSnapshot {
        return counterStore.snapshot()
    }

    fun recordSession(song: SongItem, listenedMs: Long) {
        if (listenedMs <= 0) return
        scope.launch {
            recordSessionInternal(
                song = song,
                listenedMs = listenedMs,
                playCountIncrement = null,
                scheduleSync = true
            )
        }
    }

    suspend fun recordListenDeltaNow(
        song: SongItem,
        listenedMs: Long,
        playCountIncrement: Int,
        scheduleSync: Boolean = true
    ) {
        if (listenedMs <= 0 && playCountIncrement <= 0) return
        recordSessionInternal(
            song = song,
            listenedMs = listenedMs,
            playCountIncrement = playCountIncrement.coerceAtLeast(0),
            scheduleSync = scheduleSync
        )
    }

    private suspend fun recordSessionInternal(
        song: SongItem,
        listenedMs: Long,
        playCountIncrement: Int?,
        scheduleSync: Boolean
    ) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val key = song.stableKey()
            val current = _stats.value
            val existingIndex = current.indexOfFirst { it.identityKey == key }
            val existing = current.getOrNull(existingIndex)
            val shouldStartNewStatsEpoch = existing?.let {
                shouldStartNewStatsEpoch(it, _statsClearedAt.value)
            } == true

            val safeListenedMs = listenedMs.coerceAtLeast(0L)
            val sessionCountIncrement: Int
            val sessionStat: TrackStat

            val updated = if (existing != null && !shouldStartNewStatsEpoch) {
                val newTotalMs = existing.totalListenMs + safeListenedMs
                val countIncrement = playCountIncrement ?: calculatePlayCountIncrement(
                    existing = existing,
                    song = song,
                    listenedMs = listenedMs,
                    newTotalMs = newTotalMs
                )
                sessionCountIncrement = countIncrement

                val updatedStat = existing.copy(
                    name = song.name,
                    artist = song.artist,
                    coverUrl = song.coverUrl,
                    durationMs = song.durationMs.takeIf { it > 0 } ?: existing.durationMs,
                    totalListenMs = newTotalMs,
                    playCount = existing.playCount + countIncrement,
                    lastPlayedAt = now,
                    mediaUri = song.mediaUri,
                    localFilePath = song.localFilePath,
                    localFileName = song.localFileName,
                    customName = song.customName,
                    customArtist = song.customArtist,
                    customCoverUrl = song.customCoverUrl
                )
                sessionStat = updatedStat
                current.toMutableList().apply {
                    this[existingIndex] = updatedStat
                }
            } else {
                val freshStat = createTrackStat(
                    song = song,
                    listenedMs = listenedMs,
                    playCountIncrement = playCountIncrement,
                    now = now,
                    key = key
                )
                sessionCountIncrement = freshStat.playCount
                sessionStat = freshStat
                if (existingIndex >= 0) {
                    current.toMutableList().apply {
                        this[existingIndex] = freshStat
                    }
                } else {
                    current + freshStat
                }
            }

            _stats.value = updated
            val dailyStats = trimPlaybackStatBuckets(recordPlaybackStatBucket(
                current = _dailyStats.value,
                stat = sessionStat,
                listenedMs = safeListenedMs,
                playCountIncrement = sessionCountIncrement,
                playedAt = now
            ))
            _dailyStats.value = dailyStats
            counterStore.recordLocalDelta(
                identityKey = key,
                dayStartAt = playbackStatsDayStartAt(now),
                listenedMs = safeListenedMs,
                playCountIncrement = sessionCountIncrement,
                playedAt = now,
                epochStartedAt = _statsClearedAt.value.coerceAtLeast(0L)
            )
            schedulePersistenceLocked()
            if (scheduleSync) {
                requestSyncAfterPersistence()
            }
        }
    }

    private fun createTrackStat(
        song: SongItem,
        listenedMs: Long,
        playCountIncrement: Int?,
        now: Long,
        key: String
    ): TrackStat {
        val countIncrement = playCountIncrement
            ?: if (listenedMs >= MIN_LISTEN_MS_FOR_PLAY_COUNT) 1 else 0
        return TrackStat(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            coverUrl = song.coverUrl,
            durationMs = song.durationMs,
            totalListenMs = listenedMs.coerceAtLeast(0L),
            playCount = countIncrement,
            lastPlayedAt = now,
            firstPlayedAt = now,
            mediaUri = song.mediaUri,
            localFilePath = song.localFilePath,
            localFileName = song.localFileName,
            customName = song.customName,
            customArtist = song.customArtist,
            customCoverUrl = song.customCoverUrl,
            identityKey = key
        )
    }

    private fun calculatePlayCountIncrement(
        existing: TrackStat,
        song: SongItem,
        listenedMs: Long,
        newTotalMs: Long
    ): Int {
        val durationMs = song.durationMs.takeIf { it > 0 } ?: existing.durationMs
        val prevFullPlays = existing.totalListenMs / maxOf(existing.durationMs, 1L)
        val newFullPlays = newTotalMs / maxOf(durationMs, 1L)
        return if (listenedMs >= MIN_LISTEN_MS_FOR_PLAY_COUNT || newFullPlays > prevFullPlays) {
            1
        } else {
            0
        }
    }

    fun clearAll() {
        scope.launch {
            mutex.withLock {
                val clearedAt = System.currentTimeMillis()
                _stats.value = emptyList()
                _dailyStats.value = emptyList()
                _statsClearedAt.value = clearedAt
                cancelScheduledPersistenceLocked()
                counterStore.reset(clearedAt)
                persistenceDirty = true
                requestSyncAfterPersistence()
                persistSnapshot(currentPersistenceSnapshot())
            }
        }
    }

    fun removeTracks(keys: Set<String>) {
        if (keys.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val updated = _stats.value.filterNot { it.identityKey in keys }
                val updatedDailyStats = _dailyStats.value.filterNot { it.identityKey in keys }
                _stats.value = updated
                _dailyStats.value = updatedDailyStats
                cancelScheduledPersistenceLocked()
                counterStore.removeTracks(keys)
                persistenceDirty = true
                requestSyncAfterPersistence()
                persistSnapshot(currentPersistenceSnapshot())
            }
        }
    }

    suspend fun applyMergedStats(
        syncStats: List<SyncTrackStat>,
        playbackStatsClearedAt: Long,
        respectLocalClear: Boolean = true,
        syncDailyStats: List<SyncPlaybackStatBucket> = emptyList()
    ) {
        mutex.withLock {
            val counterSnapshot = syncCounterSnapshot()
            val effectiveClearedAt = if (respectLocalClear) {
                maxOf(_statsClearedAt.value, playbackStatsClearedAt)
            } else {
                playbackStatsClearedAt
            }
            val currentStats = _stats.value
                .filter { shouldKeepTrackStatAfterClear(it, effectiveClearedAt) }
                .associateBy { it.identityKey }
            val normalizedRemoteStats = SyncPlaybackStatsMergePolicy.merge(
                local = currentStats.values.map { stat ->
                    SyncPlaybackStatMapper.fromTrackStat(
                        stat = stat,
                        counterShards = counterSnapshot.trackShards(stat.identityKey)
                    )
                },
                remote = syncStats,
                playbackStatsClearedAt = effectiveClearedAt
            )

            val currentDailyStats = _dailyStats.value
                .filter { shouldKeepDailyBucketAfterClear(it, effectiveClearedAt) }
                .associateBy { it.dayStartAt to it.identityKey }
            val normalizedRemoteDailyStats = SyncPlaybackStatsMergePolicy.mergeBuckets(
                local = currentDailyStats.values.map { bucket ->
                    SyncPlaybackStatMapper.fromPlaybackStatBucket(
                        bucket = bucket,
                        counterShards = counterSnapshot.dailyShards(
                            dayStartAt = bucket.dayStartAt,
                            identityKey = bucket.identityKey
                        )
                    )
                },
                remote = syncDailyStats,
                playbackStatsClearedAt = effectiveClearedAt
            )

            val finalized = SyncPlaybackStatsMergePolicy.finalizeMergedStats(
                mergedStats = normalizedRemoteStats,
                mergedBuckets = normalizedRemoteDailyStats
            )
            val updated = finalized.stats.map { remote ->
                currentStats[remote.identityKey]?.applySyncedCounters(remote)
                    ?: remote.toTrackStat()
            }
            val updatedDailyStats = if (
                currentDailyStats.isEmpty() &&
                normalizedRemoteDailyStats.isEmpty() &&
                _dailyStats.value.isEmpty() &&
                updated.isNotEmpty()
            ) {
                buildLegacyDailyStats(updated, effectiveClearedAt)
            } else {
                finalized.buckets.map { remote ->
                    currentDailyStats[remote.dayStartAt to remote.identityKey]?.let { local ->
                        mergeDailyBucket(local, remote)
                    } ?: remote.toPlaybackStatBucket()
                }
            }
            _stats.value = updated
            _dailyStats.value = updatedDailyStats
            val shouldUpdateClearBarrier = if (respectLocalClear) {
                effectiveClearedAt > _statsClearedAt.value
            } else {
                syncStats.isNotEmpty() && effectiveClearedAt != _statsClearedAt.value
            }
            if (shouldUpdateClearBarrier) {
                _statsClearedAt.value = effectiveClearedAt
            }
            cancelScheduledPersistenceLocked()
            counterStore.replaceFromSync(
                syncStats = finalized.stats,
                syncDailyStats = finalized.buckets,
                epochStartedAt = effectiveClearedAt
            )
            persistenceDirty = true
            persistSnapshot(currentPersistenceSnapshot())
        }
    }

    fun getStatForTrack(identityKey: String): TrackStat? {
        return _stats.value.firstOrNull { it.identityKey == identityKey }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 5_000L
        private const val ROOM_RETRY_DELAY_MS = 15_000L

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: PlaybackStatsRepository? = null

        fun getInstance(context: Context): PlaybackStatsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackStatsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
