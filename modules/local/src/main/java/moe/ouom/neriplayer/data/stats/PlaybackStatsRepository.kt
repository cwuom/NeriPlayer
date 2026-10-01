package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot

import moe.ouom.neriplayer.data.model.stats.TrackStat

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlaybackStatsRoomSnapshot
import moe.ouom.neriplayer.data.local.database.store.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.common.io.writeTextAtomically
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

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
    val clearedAt: Long = 0L,
    val snapshot: PlaybackStatsPersistenceSnapshot? = null
)

private const val MIN_LISTEN_MS_FOR_PLAY_COUNT = 30_000L
internal const val PLAYBACK_STATS_SYNC_DELAY_MS = 60_000L

class PlaybackStatsRepository internal constructor(
    private val app: Context,
    private val roomStore: PlaybackStatsRoomStore = PlaybackStatsRoomStore(
        NeriUserDataDatabase.getInstance(app.applicationContext)
    ),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "playback_stats.json") }
    private val dailyFile: File by lazy { File(app.filesDir, "playback_stats_daily.json") }
    private val metadataFile: File by lazy { File(app.filesDir, "playback_stats_meta.json") }
    private val mutex = Mutex()
    private val persistFileMutex = Mutex()
    private val roomPersistenceMutex = Mutex()
    private var persistJob: Job? = null
    private var persistGeneration = 0L
    private var pendingPersistence: PlaybackStatsPersistenceSnapshot? = null
    private var persistenceDirty = false
    private var legacyCommitSeeded = false
    private val counterStore = PlaybackStatsCounterStore(app, gson)
    @Volatile
    private var roomStorageEnabled = true
    @Volatile
    private var initialized = false
    private var initialLoadFailure: Exception? = null
    private val initialLoad = CompletableDeferred<Unit>()
    private val _stats = MutableStateFlow<List<TrackStat>>(emptyList())
    private val _statsClearedAt = MutableStateFlow(0L)
    private val _dailyStats = MutableStateFlow<List<PlaybackStatBucket>>(emptyList())
    @Volatile
    private var persistedSnapshot = PlaybackStatsPersistenceSnapshot(
        emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0L, 0L
    )
    val statsFlow: StateFlow<List<TrackStat>> = _stats
    val dailyStatsFlow: StateFlow<List<PlaybackStatBucket>> = _dailyStats
    val statsClearedAtFlow: StateFlow<Long> = _statsClearedAt

    init {
        scope.launch {
            try {
                mutex.withLock { ensureInitializedLocked() }
            } finally {
                initialLoad.complete(Unit)
            }
        }
    }

    private suspend fun loadInitialState(): PlaybackStatsPersistenceSnapshot {
        // 读取失败不能被当成未迁移，否则旧文件会覆盖 Room 主存
        val roomSnapshot = roomStore.readIfRoomPrimary()
        if (roomSnapshot != null) {
            roomStorageEnabled = true
            counterStore.replaceFromRoom(
                snapshot = roomSnapshot.counterSnapshot,
                epochStartedAt = roomSnapshot.counterEpochStartedAt
            )
            LegacyJsonCleanupRequests.schedule(app, "playback-stats-room-load")
            return roomSnapshot.toPersistenceSnapshot()
        }

        val metadata = loadMetadata()
        val legacyState = metadata.snapshot?.also { snapshot ->
            counterStore.replaceFromRoom(snapshot.counterSnapshot, snapshot.counterEpochStartedAt)
        } ?: run {
            val stats = loadFromDisk()
            val dailyStats = loadDailyStatsFromDisk(stats, metadata.clearedAt)
            counterStore.loadLegacy()
            PlaybackStatsPersistenceSnapshot(
                stats = stats,
                dailyStats = dailyStats,
                counterSnapshot = counterStore.snapshot(),
                counterEpochStartedAt = counterStore.epochStartedAt(),
                clearedAt = metadata.clearedAt
            )
        }
        runCatching {
            roomStore.importLegacyAndPromote(
                stats = legacyState.stats,
                dailyStats = legacyState.dailyStats,
                counterSnapshot = legacyState.counterSnapshot,
                counterEpochStartedAt = legacyState.counterEpochStartedAt,
                clearedAt = legacyState.clearedAt
            )
            LegacyJsonCleanupRequests.schedule(app, "playback-stats-import")
            roomStorageEnabled = true
        }.onFailure { error ->
            if (error is CancellationException) throw error
            roomStorageEnabled = false
            NPLogger.e(
                "PlaybackStatsRepo",
                "Failed to promote playback stats JSON to Room",
                error
            )
        }
        return legacyState
    }

    private suspend fun ensureInitializedLocked(): Boolean {
        if (initialized) return true
        try {
            val loaded = loadInitialState()
            val reconciled = reconcileLoadedStats(loaded)
            _stats.value = reconciled
            _dailyStats.value = loaded.dailyStats
            _statsClearedAt.value = loaded.clearedAt
            persistedSnapshot = loaded
            initialLoadFailure = null
            persistenceDirty = reconciled != loaded.stats
            initialized = true
            if (persistenceDirty) {
                scope.launch {
                    mutex.withLock { persistSnapshot(currentPersistenceSnapshot()) }
                }
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            initialLoadFailure = error
            NPLogger.e("PlaybackStatsRepo", "Playback stats unavailable; preserving storage for retry", error)
            return false
        }
    }

    suspend fun awaitInitialized(): Boolean = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock { ensureInitializedLocked() }
    }

    private fun reconcileLoadedStats(loaded: PlaybackStatsPersistenceSnapshot): List<TrackStat> {
        if (loaded.stats.isEmpty() && loaded.dailyStats.isEmpty()) return loaded.stats

        val counterSnapshot = loaded.counterSnapshot
        val reconciled = SyncPlaybackStatsMergePolicy.liftStatsToBucketTotals(
            stats = loaded.stats.map { stat ->
                SyncPlaybackStatMapper.fromTrackStat(
                    stat = stat,
                    counterShards = counterSnapshot.trackShards(stat.identityKey)
                )
            },
            buckets = loaded.dailyStats.map { bucket ->
                SyncPlaybackStatMapper.fromPlaybackStatBucket(
                    bucket = bucket,
                    counterShards = counterSnapshot.dailyShards(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    )
                )
            }
        )
        val localStats = loaded.stats.associateBy { it.identityKey }
        return reconciled.map { stat ->
            localStats[stat.identityKey]?.applySyncedCounters(stat) ?: stat.toTrackStat()
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
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<TrackStat>>() {}.type
        return gson.fromJson<List<TrackStat>>(file.readText(), type)
            ?: throw IOException("Playback stats JSON has no valid list")
    }

    private fun loadMetadata(): PlaybackStatsMetadata {
        if (!metadataFile.exists()) return PlaybackStatsMetadata()
        return gson.fromJson(metadataFile.readText(), PlaybackStatsMetadata::class.java)
            ?: throw IOException("Playback stats metadata JSON has no valid state")
    }

    private fun loadDailyStatsFromDisk(
        stats: List<TrackStat>,
        clearedAt: Long
    ): List<PlaybackStatBucket> {
        if (!dailyFile.exists()) return buildLegacyDailyStats(stats, clearedAt)
        val type = object : TypeToken<List<PlaybackStatBucket>>() {}.type
        val loaded = gson.fromJson<List<PlaybackStatBucket>>(dailyFile.readText(), type)
            ?: throw IOException("Playback stats daily JSON has no valid list")
        return trimPlaybackStatBuckets(loaded)
    }

    private fun persistToDisk(list: List<TrackStat>): Boolean {
        return runCatching {
            file.writeTextAtomically(gson.toJson(list))
            true
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist stats", error)
        }.getOrDefault(false)
    }

    private fun persistDailyStatsToDisk(list: List<PlaybackStatBucket>): Boolean {
        return runCatching {
            dailyFile.writeTextAtomically(gson.toJson(list))
            true
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist daily stats", error)
        }.getOrDefault(false)
    }

    private fun persistMetadata(snapshot: PlaybackStatsPersistenceSnapshot): Boolean {
        return runCatching {
            metadataFile.writeTextAtomically(gson.toJson(PlaybackStatsMetadata(snapshot.clearedAt, snapshot)))
            true
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist stats metadata", error)
        }.getOrDefault(false)
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
            delay(PERSIST_DEBOUNCE_MS.milliseconds)
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
    ): Boolean {
        return roomPersistenceMutex.withLock {
            if (expectedGeneration != null && synchronized(this) {
                    expectedGeneration != persistGeneration
                }
            ) return@withLock false
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
                    if (error is CancellationException) throw error
                    roomStorageEnabled = false
                    NPLogger.e(
                        "PlaybackStatsRepo",
                        "Failed to write Room playback stats",
                        error
                    )
                }.isSuccess
                if (roomSucceeded) {
                    persistedSnapshot = snapshot
                    markPersistenceClean(expectedGeneration)
                    return@withLock true
                }
            }
            val legacySucceeded = runCatching {
                roomStore.commitLegacyFallback { persistLegacySnapshot(snapshot) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                NPLogger.e("PlaybackStatsRepo", "Failed to commit playback stats JSON fallback", error)
            }.getOrDefault(false)
            if (legacySucceeded) {
                persistedSnapshot = snapshot
                markPersistenceClean(expectedGeneration)
            }
            legacySucceeded
        }
    }

    private fun markPersistenceClean(expectedGeneration: Long?) {
        synchronized(this) {
            if (expectedGeneration == null || expectedGeneration == persistGeneration) {
                persistenceDirty = false
            }
        }
    }

    private fun persistLegacySnapshot(
        snapshot: PlaybackStatsPersistenceSnapshot
    ): Boolean {
        return synchronized(persistFileMutex) {
            if (!legacyCommitSeeded) {
                // 先保留旧投影对应的完整快照，首轮保存中断后也能恢复
                if (!persistMetadata(persistedSnapshot)) return@synchronized false
                legacyCommitSeeded = true
            }
            persistToDisk(snapshot.stats) &&
                persistDailyStatsToDisk(snapshot.dailyStats) &&
                counterStore.persistLegacyProjection(
                    snapshot.counterSnapshot,
                    snapshot.counterEpochStartedAt
                ) &&
                persistMetadata(snapshot)
        }
    }

    fun hasPendingWrites(): Boolean {
        return synchronized(this) {
            persistenceDirty || pendingPersistence != null || persistJob?.isActive == true
        }
    }

    suspend fun flushPendingWrites() {
        initialLoad.await()
        mutex.withLock {
            if (!ensureInitializedLocked()) return@withLock
            val shouldPersist = synchronized(this@PlaybackStatsRepository) {
                persistenceDirty || pendingPersistence != null || persistJob?.isActive == true
            }
            cancelScheduledPersistenceLocked()
            if (shouldPersist) {
                persistSnapshot(currentPersistenceSnapshot())
            }
        }
    }

    private fun triggerSync() {
        // 播放统计先本地落盘, 延后同步给切歌首帧和重缓冲留出资源
        runCatching {
            SecureTokenStorage(app).markSyncMutation()
            GitHubSyncWorker.scheduleDelayedSync(
                app,
                triggerByUserAction = false,
                markMutation = false,
                initialDelayMs = PLAYBACK_STATS_SYNC_DELAY_MS
            )
            WebDavSyncWorker.scheduleDelayedSync(
                app,
                triggerByUserAction = false,
                markMutation = false,
                initialDelayMs = PLAYBACK_STATS_SYNC_DELAY_MS
            )
        }
    }

    internal fun syncSnapshot(): PlaybackStatsPersistenceSnapshot {
        if (!initialized || hasPendingWrites()) {
            throw IOException("Playback stats have no complete persisted snapshot", initialLoadFailure)
        }
        return persistedSnapshot
    }

    fun syncCounterSnapshot(): PlaybackStatsSyncCounterSnapshot = syncSnapshot().counterSnapshot

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
        initialLoad.await()
        mutex.withLock {
            if (!ensureInitializedLocked()) return@withLock
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
                triggerSync()
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
            initialLoad.await()
            mutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                val clearedAt = System.currentTimeMillis()
                _stats.value = emptyList()
                _dailyStats.value = emptyList()
                _statsClearedAt.value = clearedAt
                cancelScheduledPersistenceLocked()
                counterStore.reset(clearedAt)
                persistenceDirty = true
                if (persistSnapshot(currentPersistenceSnapshot())) triggerSync()
            }
        }
    }

    fun removeTracks(keys: Set<String>) {
        if (keys.isEmpty()) return
        scope.launch {
            initialLoad.await()
            mutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                val updated = _stats.value.filterNot { it.identityKey in keys }
                val updatedDailyStats = _dailyStats.value.filterNot { it.identityKey in keys }
                _stats.value = updated
                _dailyStats.value = updatedDailyStats
                cancelScheduledPersistenceLocked()
                counterStore.removeTracks(keys)
                persistenceDirty = true
                if (persistSnapshot(currentPersistenceSnapshot())) triggerSync()
            }
        }
    }

    suspend fun applyMergedStats(
        syncStats: List<SyncTrackStat>,
        playbackStatsClearedAt: Long,
        respectLocalClear: Boolean = true,
        syncDailyStats: List<SyncPlaybackStatBucket> = emptyList()
    ) {
        initialLoad.await()
        mutex.withLock {
            if (!ensureInitializedLocked()) {
                throw IOException("Playback stats initialization failed", initialLoadFailure)
            }
            val counterSnapshot = counterStore.snapshot()
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
            if (!persistSnapshot(currentPersistenceSnapshot())) {
                throw IOException("Merged playback stats could not be persisted")
            }
        }
    }

    fun getStatForTrack(identityKey: String): TrackStat? {
        return _stats.value.firstOrNull { it.identityKey == identityKey }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 5_000L

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
