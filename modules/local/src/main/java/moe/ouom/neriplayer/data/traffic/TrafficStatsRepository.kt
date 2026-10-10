package moe.ouom.neriplayer.data.traffic

import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.traffic.TrafficStatsBucket
import moe.ouom.neriplayer.data.model.traffic.TrafficUsageSource

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.io.writeTextAtomically
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class TrafficStatsRepository internal constructor(
    private val app: Application,
    private val roomStore: TrafficStatsRoomStore,
    private val scope: CoroutineScope,
    private val currentTimeMillis: () -> Long
) {
    private constructor(app: Application) : this(
        app = app,
        roomStore = TrafficStatsRoomStore(
            NeriUserDataDatabase.getInstance(app.applicationContext)
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        currentTimeMillis = System::currentTimeMillis
    )

    private val gson = Gson()
    private val statsMutex = Mutex()
    private val persistenceMutex = Mutex()
    private val dailyFile: File by lazy { File(app.filesDir, "traffic_stats_daily.json") }
    @Volatile
    private var roomStorageEnabled = true
    @Volatile
    private var persistenceSuspended = false
    private val _dailyStats = MutableStateFlow(emptyList<TrafficStatsBucket>())
    private var persistedStats = emptyList<TrafficStatsBucket>()
    private var persistJob: Job? = null
    private var persistDeferredSinceMs = 0L
    private var persistGeneration = 0L
    // 首次访问常在主线程，Room 与旧 JSON 读取放到后台；写入先等待加载完成再叠加
    private val initialLoad: Job = scope.launch {
        val loaded = loadInitialStats()
        statsMutex.withLock {
            persistedStats = loaded
            _dailyStats.value = loaded
        }
    }

    val dailyStatsFlow: StateFlow<List<TrafficStatsBucket>> = _dailyStats

    internal suspend fun awaitInitialLoad() = initialLoad.join()

    fun currentNetworkType(): TrafficNetworkType = app.currentTrafficNetworkType()

    fun recordNetworkBytes(
        networkType: TrafficNetworkType,
        bytes: Long,
        source: TrafficUsageSource
    ) {
        if (bytes <= 0L) return
        scope.launch {
            initialLoad.join()
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
            initialLoad.join()
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
            initialLoad.join()
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
        val todayStartAt = playbackStatsDayStartAt(currentTimeMillis())
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
        val now = currentTimeMillis()
        val previous = persistJob
        if (previous == null || previous.isCompleted) persistDeferredSinceMs = now
        val waitMs = trafficPersistDelayMs(pendingForMs = now - persistDeferredSinceMs)
        previous?.cancel()
        persistJob = scope.launch {
            delay(waitMs.milliseconds)
            // 新流量只能取消等待中的防抖，已开始的 Room 事务被取消会被误判为写入失败并切到 JSON
            withContext(NonCancellable) { persistSnapshot(snapshot, generation) }
        }
    }

    private suspend fun loadInitialStats(): List<TrafficStatsBucket> {
        val roomStats = try {
            roomStore.readIfRoomPrimary()
        } catch (error: Exception) {
            // 读失败时无法确认 Room 是否已是主存，导入旧 JSON 会覆盖甚至清空更新的数据
            roomStorageEnabled = false
            persistenceSuspended = true
            NPLogger.e(TAG, "Failed to read Room traffic stats; keeping stored data untouched", error)
            return emptyList()
        }
        if (roomStats != null) {
            LegacyJsonCleanupRequests.schedule(app, "traffic-stats-room-load")
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
        runCatching {
            roomStore.importLegacyAndPromote(legacyStats)
            LegacyJsonCleanupRequests.schedule(app, "traffic-stats-import")
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
            if (persistenceSuspended) return@withLock
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

        @Volatile
        private var instance: TrafficStatsRepository? = null

        fun getInstance(app: Application): TrafficStatsRepository {
            return synchronized(this) {
                instance ?: TrafficStatsRepository(app).also { instance = it }
            }
        }
    }
}

private const val PERSIST_DEBOUNCE_MS = 5_000L
private const val PERSIST_MAX_DEFER_MS = 30_000L

/** 持续下载或播放时每次新流量都会推迟落盘，进程被杀会丢掉整段统计，因此累计推迟不超过上限 */
internal fun trafficPersistDelayMs(pendingForMs: Long): Long =
    (PERSIST_MAX_DEFER_MS - pendingForMs).coerceIn(0L, PERSIST_DEBOUNCE_MS)
