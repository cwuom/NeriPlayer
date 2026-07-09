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
import moe.ouom.neriplayer.data.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.currentTrafficNetworkType
import java.io.File

class TrafficStatsRepository private constructor(
    private val app: Application
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsMutex = Mutex()
    private val dailyFile: File by lazy { File(app.filesDir, "traffic_stats_daily.json") }
    private val _dailyStats = MutableStateFlow(loadDailyStatsFromDisk())
    private var persistJob: Job? = null

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
                runCatching { dailyFile.delete() }
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
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistDailyStatsToDisk(snapshot)
        }
    }

    private fun loadDailyStatsFromDisk(): List<TrafficStatsBucket> {
        return runCatching {
            if (!dailyFile.exists()) return emptyList()
            val type = object : TypeToken<List<TrafficStatsBucket>>() {}.type
            gson.fromJson<List<TrafficStatsBucket>>(dailyFile.readText(), type).orEmpty()
                .filter { it.dayStartAt > 0L }
                .sortedBy { it.dayStartAt }
        }.onFailure {
            NPLogger.e(TAG, "Failed to load traffic stats", it)
        }.getOrDefault(emptyList())
    }

    private fun persistDailyStatsToDisk(list: List<TrafficStatsBucket>) {
        runCatching {
            dailyFile.writeText(gson.toJson(list))
        }.onFailure {
            NPLogger.e(TAG, "Failed to persist traffic stats", it)
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
