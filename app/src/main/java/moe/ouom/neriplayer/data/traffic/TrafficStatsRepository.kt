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
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore
import moe.ouom.neriplayer.data.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.writeTextAtomically
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
    private val initialStats = loadInitialStats()
    private val _dailyStats = MutableStateFlow(initialStats)
    private var persistedStats = initialStats
    private var persistJob: Job? = null
    private var persistGeneration = 0L

    val dailyStatsFlow: StateFlow<List<TrafficStatsBucket>> = _dailyStats

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
        val roomStats = runCatching {
            runBlocking { roomStore.readIfRoomPrimary() }
        }.onFailure {
            roomStorageEnabled = false
            NPLogger.e(TAG, "Failed to read Room traffic stats", it)
        }.getOrNull()
        if (roomStats != null) return roomStats

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
        runCatching {
            runBlocking { roomStore.importLegacyAndPromote(legacyStats) }
            roomStorageEnabled = true
        }.onFailure {
            roomStorageEnabled = false
            NPLogger.e(TAG, "Failed to promote traffic stats JSON to Room", it)
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
                    roomStorageEnabled = false
                    NPLogger.e(TAG, "Failed to write Room traffic stats", it)
                }.isSuccess
                if (roomSucceeded) {
                    persistedStats = snapshot
                    markPersistenceClean(expectedGeneration)
                    return@withLock
                }
            }

            val legacySucceeded = persistDailyStatsToDisk(snapshot)
            if (legacySucceeded) {
                runCatching { roomStore.markLegacyJsonPrimary() }
                    .onFailure {
                        NPLogger.e(TAG, "Failed to mark traffic stats JSON fallback state", it)
                    }
                persistedStats = snapshot
                markPersistenceClean(expectedGeneration)
            }
        }
    }

    private fun persistDailyStatsToDisk(list: List<TrafficStatsBucket>): Boolean {
        return runCatching {
            dailyFile.writeTextAtomically(gson.toJson(list))
            true
        }.onFailure {
            NPLogger.e(TAG, "Failed to persist traffic stats", it)
        }.getOrDefault(false)
    }

    private fun markPersistenceClean(expectedGeneration: Long?) {
        if (expectedGeneration == null || expectedGeneration == persistGeneration) {
            persistJob = null
        }
    }

    companion object {
        private const val TAG = "TrafficStatsRepo"
        private const val PERSIST_DEBOUNCE_MS = 5_000L

        @Volatile
        private var instance: TrafficStatsRepository? = null

        fun getInstance(app: Application): TrafficStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: TrafficStatsRepository(app).also { instance = it }
            }
        }
    }
}
