package moe.ouom.neriplayer.data.stats

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatsMergePolicy
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.github.SyncTrackStat
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
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

private data class PlaybackStatsMetadata(
    val clearedAt: Long = 0L
)

private const val MIN_LISTEN_MS_FOR_PLAY_COUNT = 30_000L

class PlaybackStatsRepository private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "playback_stats.json") }
    private val dailyFile: File by lazy { File(app.filesDir, "playback_stats_daily.json") }
    private val metadataFile: File by lazy { File(app.filesDir, "playback_stats_meta.json") }
    private val mutex = Mutex()
    private val _stats = MutableStateFlow(loadFromDisk())
    private val _statsClearedAt = MutableStateFlow(loadMetadata().clearedAt)
    private val _dailyStats = MutableStateFlow(loadDailyStatsFromDisk())
    val statsFlow: StateFlow<List<TrackStat>> = _stats
    val dailyStatsFlow: StateFlow<List<PlaybackStatBucket>> = _dailyStats
    val statsClearedAtFlow: StateFlow<Long> = _statsClearedAt

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

    private fun loadDailyStatsFromDisk(): List<PlaybackStatBucket> {
        return try {
            if (!dailyFile.exists()) {
                val migrated = buildLegacyDailyStats(
                    stats = _stats.value,
                    clearedAt = _statsClearedAt.value
                )
                if (migrated.isNotEmpty()) {
                    persistDailyStatsToDisk(migrated)
                }
                return migrated
            }
            val raw = dailyFile.readText()
            val type = object : TypeToken<List<PlaybackStatBucket>>() {}.type
            gson.fromJson<List<PlaybackStatBucket>>(raw, type).orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun persistToDisk(list: List<TrackStat>) {
        runCatching {
            file.writeText(gson.toJson(list))
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist stats", error)
        }
    }

    private fun persistDailyStatsToDisk(list: List<PlaybackStatBucket>) {
        runCatching {
            dailyFile.writeText(gson.toJson(list))
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist daily stats", error)
        }
    }

    private fun persistMetadata(clearedAt: Long) {
        runCatching {
            metadataFile.writeText(gson.toJson(PlaybackStatsMetadata(clearedAt)))
        }.onFailure { error ->
            NPLogger.e("PlaybackStatsRepo", "Failed to persist stats metadata", error)
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
            persistToDisk(updated)
            val dailyStats = recordPlaybackStatBucket(
                current = _dailyStats.value,
                stat = sessionStat,
                listenedMs = safeListenedMs,
                playCountIncrement = sessionCountIncrement,
                playedAt = now
            )
            _dailyStats.value = dailyStats
            persistDailyStatsToDisk(dailyStats)
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
            mutex.withLock {
                val clearedAt = System.currentTimeMillis()
                _stats.value = emptyList()
                _dailyStats.value = emptyList()
                _statsClearedAt.value = clearedAt
                persistToDisk(emptyList())
                persistDailyStatsToDisk(emptyList())
                persistMetadata(clearedAt)
                triggerSync()
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
                persistToDisk(updated)
                persistDailyStatsToDisk(updatedDailyStats)
                triggerSync()
            }
        }
    }

    fun applyMergedStats(
        syncStats: List<SyncTrackStat>,
        playbackStatsClearedAt: Long,
        respectLocalClear: Boolean = true,
        syncDailyStats: List<SyncPlaybackStatBucket> = emptyList()
    ) {
        scope.launch {
            mutex.withLock {
                val effectiveClearedAt = if (respectLocalClear) {
                    maxOf(_statsClearedAt.value, playbackStatsClearedAt)
                } else {
                    playbackStatsClearedAt
                }
                val current = _stats.value
                    .filter { shouldKeepLocalAfterClear(it, effectiveClearedAt) }
                    .associateBy { it.identityKey }
                    .toMutableMap()
                val normalizedRemoteStats = SyncPlaybackStatsMergePolicy.merge(
                    local = emptyList(),
                    remote = syncStats,
                    playbackStatsClearedAt = effectiveClearedAt
                )
                for (remote in normalizedRemoteStats) {
                    val local = current[remote.identityKey]
                    if (local == null) {
                        current[remote.identityKey] = TrackStat(
                            id = remote.id,
                            name = remote.name,
                            artist = remote.artist,
                            album = remote.album,
                            albumId = remote.albumId,
                            coverUrl = remote.coverUrl,
                            durationMs = remote.durationMs,
                            totalListenMs = remote.totalListenMs,
                            playCount = remote.playCount,
                            lastPlayedAt = remote.lastPlayedAt,
                            firstPlayedAt = remote.firstPlayedAt,
                            mediaUri = remote.mediaUri,
                            localFilePath = null,
                            localFileName = null,
                            customName = null,
                            customArtist = null,
                            customCoverUrl = null,
                            identityKey = remote.identityKey
                        )
                    } else {
                        current[remote.identityKey] = local.copy(
                            totalListenMs = maxOf(local.totalListenMs, remote.totalListenMs),
                            playCount = maxOf(local.playCount, remote.playCount),
                            lastPlayedAt = maxOf(local.lastPlayedAt, remote.lastPlayedAt),
                            firstPlayedAt = minOf(local.firstPlayedAt, remote.firstPlayedAt),
                            name = if (remote.lastPlayedAt > local.lastPlayedAt) remote.name else local.name,
                            artist = if (remote.lastPlayedAt > local.lastPlayedAt) remote.artist else local.artist,
                            coverUrl = if (remote.lastPlayedAt > local.lastPlayedAt) remote.coverUrl else local.coverUrl
                        )
                    }
                }
                val updated = current.values.toList()
                _stats.value = updated
                val shouldUpdateClearBarrier = if (respectLocalClear) {
                    effectiveClearedAt > _statsClearedAt.value
                } else {
                    syncStats.isNotEmpty() && effectiveClearedAt != _statsClearedAt.value
                }
                if (shouldUpdateClearBarrier) {
                    _statsClearedAt.value = effectiveClearedAt
                    persistMetadata(effectiveClearedAt)
                }

                val currentDailyStats = _dailyStats.value
                    .filter { shouldKeepDailyAfterClear(it, effectiveClearedAt) }
                    .associateBy { it.dayStartAt to it.identityKey }
                    .toMutableMap()
                val normalizedRemoteDailyStats = SyncPlaybackStatsMergePolicy.mergeBuckets(
                    local = emptyList(),
                    remote = syncDailyStats,
                    playbackStatsClearedAt = effectiveClearedAt
                )
                for (remote in normalizedRemoteDailyStats) {
                    val key = remote.dayStartAt to remote.identityKey
                    val local = currentDailyStats[key]
                    currentDailyStats[key] = if (local == null) {
                        remote.toPlaybackStatBucket()
                    } else {
                        mergeDailyBucket(local, remote)
                    }
                }

                val updatedDailyStats = if (
                    currentDailyStats.isEmpty() &&
                    normalizedRemoteDailyStats.isEmpty() &&
                    _dailyStats.value.isEmpty() &&
                    updated.isNotEmpty()
                ) {
                    buildLegacyDailyStats(updated, effectiveClearedAt)
                } else {
                    currentDailyStats.values.toList()
                }
                _dailyStats.value = updatedDailyStats
                persistDailyStatsToDisk(updatedDailyStats)
                persistToDisk(updated)
            }
        }
    }

    fun getStatForTrack(identityKey: String): TrackStat? {
        return _stats.value.firstOrNull { it.identityKey == identityKey }
    }

    private fun shouldKeepLocalAfterClear(stat: TrackStat, playbackStatsClearedAt: Long): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return stat.lastPlayedAt >= playbackStatsClearedAt
    }

    private fun shouldKeepDailyAfterClear(
        bucket: PlaybackStatBucket,
        playbackStatsClearedAt: Long
    ): Boolean {
        if (playbackStatsClearedAt <= 0L) return true
        return bucket.lastPlayedAt >= playbackStatsClearedAt
    }

    private fun shouldStartNewStatsEpoch(stat: TrackStat, playbackStatsClearedAt: Long): Boolean {
        if (playbackStatsClearedAt <= 0L) return false
        val firstPlayedAt = stat.firstPlayedAt.takeIf { it > 0L } ?: stat.lastPlayedAt
        return firstPlayedAt < playbackStatsClearedAt || stat.lastPlayedAt < playbackStatsClearedAt
    }

    private fun mergeDailyBucket(
        local: PlaybackStatBucket,
        remote: SyncPlaybackStatBucket
    ): PlaybackStatBucket {
        val remoteBucket = remote.toPlaybackStatBucket()
        val useRemoteMetadata = remote.lastPlayedAt > local.lastPlayedAt
        return local.copy(
            id = if (useRemoteMetadata) remoteBucket.id else local.id,
            name = if (useRemoteMetadata) remoteBucket.name else local.name,
            artist = if (useRemoteMetadata) remoteBucket.artist else local.artist,
            album = if (useRemoteMetadata) remoteBucket.album else local.album,
            albumId = if (useRemoteMetadata) remoteBucket.albumId else local.albumId,
            coverUrl = if (useRemoteMetadata) remoteBucket.coverUrl else local.coverUrl,
            durationMs = if (useRemoteMetadata) remoteBucket.durationMs else local.durationMs,
            totalListenMs = maxOf(local.totalListenMs, remoteBucket.totalListenMs),
            playCount = maxOf(local.playCount, remoteBucket.playCount),
            lastPlayedAt = maxOf(local.lastPlayedAt, remoteBucket.lastPlayedAt),
            firstPlayedAt = minPositivePlayedAt(local.firstPlayedAt, remoteBucket.firstPlayedAt),
            mediaUri = if (useRemoteMetadata) remoteBucket.mediaUri else local.mediaUri
        )
    }

    private fun SyncPlaybackStatBucket.toPlaybackStatBucket(): PlaybackStatBucket {
        return PlaybackStatBucket(
            dayStartAt = dayStartAt,
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

    private fun minPositivePlayedAt(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }

    companion object {
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
