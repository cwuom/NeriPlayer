package moe.ouom.neriplayer.data.traffic

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore
import moe.ouom.neriplayer.data.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType
import java.io.File

class TrafficStatsRepository private constructor(
    private val app: Application
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsMutex = Mutex()
    private val persistenceMutex = Mutex()
    private val dailyFile: File by lazy { File(app.filesDir, "traffic_stats_daily.json") }
    private val roomStore = TrafficStatsRoomStore(
        NeriUserDataDatabase.getInstance(app.applicationContext)
    )
    @Volatile
    private var roomStorageEnabled = true
    private var roomRecoveryBaseline: List<TrafficStatsBucket>? = null
    private val initialStats = loadInitialStats()
    private val _dailyStats = MutableStateFlow(initialStats)
    private var persistedStats = initialStats
    private var persistJob: Job? = null
    private var retryJob: Job? = null
    private var persistGeneration = 0L

    val dailyStatsFlow: StateFlow<List<TrafficStatsBucket>> = _dailyStats

    init {
        scheduleRoomRecovery()
    }

    fun currentNetworkType(): TrafficNetworkType = app.currentTrafficNetworkType()

    fun recordNetworkBytes(
        networkType: TrafficNetworkType,
        bytes: Long,
        source: TrafficUsageSource
    ) {
        if (bytes <= 0L) return
        scope.launch {
            statsMutex.withLock {
                val updated = upsertTodayBucket { bucket ->
                    val base = when (networkType) {
                        TrafficNetworkType.WIFI -> bucket.copy(wifiBytes = bucket.wifiBytes + bytes)
                        TrafficNetworkType.MOBILE -> bucket.copy(mobileBytes = bucket.mobileBytes + bytes)
                        TrafficNetworkType.ROAMING -> bucket.copy(roamingBytes = bucket.roamingBytes + bytes)
                    }
                    when (source) {
                        TrafficUsageSource.PLAYBACK -> base.copy(
                            playbackNetworkBytes = base.playbackNetworkBytes + bytes,
                            requestCount = base.requestCount + 1
                        )
                        TrafficUsageSource.DOWNLOAD -> base.copy(
                            downloadNetworkBytes = base.downloadNetworkBytes + bytes,
                            requestCount = base.requestCount + 1
                        )
                    }
                }
                publishLocked(updated)
            }
        }
    }

    fun recordCacheHitBytes(bytes: Long) {
        if (bytes <= 0L) return
        scope.launch {
            statsMutex.withLock {
                val updated = upsertTodayBucket { bucket ->
                    bucket.copy(
                        cacheHitBytes = bucket.cacheHitBytes + bytes,
                        cacheHitCount = bucket.cacheHitCount + 1
                    )
                }
                publishLocked(updated)
            }
        }
    }

    fun clearAll() {
        scope.launch {
            statsMutex.withLock {
                persistJob?.cancel()
                persistJob = null
                _dailyStats.value = emptyList()
                persistGeneration += 1L
                persistSnapshot(emptyList())
            }
        }
    }

    private fun upsertTodayBucket(
        transform: (TrafficStatsBucket) -> TrafficStatsBucket
    ): List<TrafficStatsBucket> {
        val todayStartAt = playbackStatsDayStartAt(System.currentTimeMillis())
        val current = _dailyStats.value
        val index = current.indexOfFirst { it.dayStartAt == todayStartAt }
        return if (index >= 0) {
            current.toMutableList().apply {
                this[index] = transform(this[index])
            }
        } else {
            current + transform(TrafficStatsBucket(dayStartAt = todayStartAt))
        }
    }

    private fun publishLocked(updated: List<TrafficStatsBucket>) {
        _dailyStats.value = updated
        schedulePersistLocked(updated)
    }

    private fun schedulePersistLocked(snapshot: List<TrafficStatsBucket>) {
        persistGeneration += 1L
        val generation = persistGeneration
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistSnapshot(snapshot, generation)
        }
    }

    private fun loadInitialStats(): List<TrafficStatsBucket> {
        var needsRoomRecovery = false
        val roomStats = runCatching {
            runBlocking { roomStore.readIfRoomPrimary() }
        }.onFailure {
            roomStorageEnabled = false
            needsRoomRecovery = true
            NPLogger.e(TAG, "Failed to read Room traffic stats", it)
        }.getOrNull()
        if (roomStats != null) {
            LegacyJsonCleanupScheduler.schedule(app, "traffic-stats-room-load")
            return roomStats
        }

        val legacyStats = runCatching {
            if (!dailyFile.exists()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<TrafficStatsBucket>>() {}.type
                gson.fromJson<List<TrafficStatsBucket>>(dailyFile.readText(), type).orEmpty()
                    .filter { it.dayStartAt > 0L }
                    .sortedBy { it.dayStartAt }
            }
        }.onFailure {
            NPLogger.e(TAG, "Failed to load traffic stats", it)
        }.getOrDefault(emptyList())
        if (roomStorageEnabled) {
            runCatching {
                runBlocking { roomStore.importLegacyAndPromote(legacyStats) }
                LegacyJsonCleanupScheduler.schedule(app, "traffic-stats-import")
            }.onFailure {
                roomStorageEnabled = false
                needsRoomRecovery = true
                NPLogger.e(TAG, "Failed to promote traffic stats JSON to Room", it)
            }
        }
        if (needsRoomRecovery) {
            roomRecoveryBaseline = legacyStats
        }
        return legacyStats
    }

    private suspend fun persistSnapshot(
        snapshot: List<TrafficStatsBucket>,
        expectedGeneration: Long? = null
    ) {
        persistenceMutex.withLock {
            if (roomStorageEnabled) {
                val roomSucceeded = runCatching {
                    roomStore.writeIncremental(
                        previous = persistedStats,
                        next = snapshot
                    )
                }.onFailure {
                    NPLogger.e(
                        TAG,
                        "Failed to write Room traffic stats; keeping JSON migration data read-only",
                        it
                    )
                }.isSuccess
                if (roomSucceeded) {
                    persistedStats = snapshot
                    markPersistenceClean(expectedGeneration)
                    return@withLock
                }
            }
            if (roomStorageEnabled) {
                scheduleRetry()
            } else {
                scheduleRoomRecovery()
            }
        }
    }

    private fun scheduleRetry() {
        if (!roomStorageEnabled || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(ROOM_RETRY_DELAY_MS)
            statsMutex.withLock {
                retryJob = null
                persistSnapshot(_dailyStats.value)
            }
        }
    }

    private fun scheduleRoomRecovery() {
        if (roomStorageEnabled || roomRecoveryBaseline == null || retryJob?.isActive == true) {
            return
        }
        retryJob = scope.launch {
            delay(ROOM_RETRY_DELAY_MS)
            statsMutex.withLock {
                retryJob = null
                recoverRoomStorage()
            }
        }
    }

    private suspend fun recoverRoomStorage() {
        val baseline = roomRecoveryBaseline ?: return
        val recovered = persistenceMutex.withLock {
            runCatching {
                if (roomStore.readIfRoomPrimary() == null) {
                    roomStore.importLegacyAndPromote(_dailyStats.value)
                }
                val roomSnapshot = roomStore.readIfRoomPrimary()
                    ?: return@runCatching null
                mergeTrafficStatsRoomRecovery(
                    roomSnapshot = roomSnapshot,
                    recoveryBaseline = baseline,
                    currentSnapshot = _dailyStats.value
                ).also { merged ->
                    roomStore.writeIncremental(roomSnapshot, merged)
                }
            }.onFailure { error ->
                NPLogger.e(
                    TAG,
                    "Failed to recover Room traffic stats without replaying JSON",
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
        persistedStats = recovered
        _dailyStats.value = recovered
        LegacyJsonCleanupScheduler.schedule(app, "traffic-stats-room-recovery")
    }

    private fun markPersistenceClean(expectedGeneration: Long?) {
        if (expectedGeneration == null || expectedGeneration == persistGeneration) {
            persistJob = null
        }
    }

    companion object {
        private const val TAG = "TrafficStatsRepo"
        private const val PERSIST_DEBOUNCE_MS = 5_000L
        private const val ROOM_RETRY_DELAY_MS = 15_000L

        @Volatile
        private var instance: TrafficStatsRepository? = null

        fun getInstance(app: Application): TrafficStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: TrafficStatsRepository(app).also { instance = it }
            }
        }
    }
}

internal fun mergeTrafficStatsRoomRecovery(
    roomSnapshot: List<TrafficStatsBucket>,
    recoveryBaseline: List<TrafficStatsBucket>,
    currentSnapshot: List<TrafficStatsBucket>
): List<TrafficStatsBucket> {
    if (currentSnapshot.isEmpty() && recoveryBaseline.isNotEmpty()) {
        return emptyList()
    }
    val recovered = roomSnapshot.associateBy(TrafficStatsBucket::dayStartAt).toMutableMap()
    val baselineByDay = recoveryBaseline.associateBy(TrafficStatsBucket::dayStartAt)
    val currentByDay = currentSnapshot.associateBy(TrafficStatsBucket::dayStartAt)
    (baselineByDay.keys + currentByDay.keys).forEach { dayStartAt ->
        val baseline = baselineByDay[dayStartAt]
        val current = currentByDay[dayStartAt]
        when {
            current == null && baseline != null -> recovered.remove(dayStartAt)
            current != null && current != baseline -> {
                recovered[dayStartAt] = mergeTrafficRecoveryBucket(
                    room = recovered[dayStartAt],
                    baseline = baseline,
                    current = current
                )
            }
        }
    }
    return recovered.values.sortedBy(TrafficStatsBucket::dayStartAt)
}

private fun mergeTrafficRecoveryBucket(
    room: TrafficStatsBucket?,
    baseline: TrafficStatsBucket?,
    current: TrafficStatsBucket
): TrafficStatsBucket {
    if (room == null || baseline == null) return current
    return room.copy(
        wifiBytes = room.wifiBytes.saturatingAdd(
            current.wifiBytes.positiveDeltaFrom(baseline.wifiBytes)
        ),
        mobileBytes = room.mobileBytes.saturatingAdd(
            current.mobileBytes.positiveDeltaFrom(baseline.mobileBytes)
        ),
        roamingBytes = room.roamingBytes.saturatingAdd(
            current.roamingBytes.positiveDeltaFrom(baseline.roamingBytes)
        ),
        playbackNetworkBytes = room.playbackNetworkBytes.saturatingAdd(
            current.playbackNetworkBytes.positiveDeltaFrom(baseline.playbackNetworkBytes)
        ),
        downloadNetworkBytes = room.downloadNetworkBytes.saturatingAdd(
            current.downloadNetworkBytes.positiveDeltaFrom(baseline.downloadNetworkBytes)
        ),
        cacheHitBytes = room.cacheHitBytes.saturatingAdd(
            current.cacheHitBytes.positiveDeltaFrom(baseline.cacheHitBytes)
        ),
        requestCount = room.requestCount.saturatingAdd(
            current.requestCount.positiveDeltaFrom(baseline.requestCount)
        ),
        cacheHitCount = room.cacheHitCount.saturatingAdd(
            current.cacheHitCount.positiveDeltaFrom(baseline.cacheHitCount)
        )
    )
}

private fun Long.positiveDeltaFrom(baseline: Long): Long {
    return coerceAtLeast(0L).minus(baseline.coerceAtLeast(0L)).coerceAtLeast(0L)
}

private fun Int.positiveDeltaFrom(baseline: Int): Int {
    return coerceAtLeast(0).minus(baseline.coerceAtLeast(0)).coerceAtLeast(0)
}

private fun Long.saturatingAdd(delta: Long): Long {
    val base = coerceAtLeast(0L)
    return if (delta > Long.MAX_VALUE - base) Long.MAX_VALUE else base + delta
}

private fun Int.saturatingAdd(delta: Int): Int {
    val base = coerceAtLeast(0)
    return if (delta > Int.MAX_VALUE - base) Int.MAX_VALUE else base + delta
}
