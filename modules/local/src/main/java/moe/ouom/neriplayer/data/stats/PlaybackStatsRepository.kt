package moe.ouom.neriplayer.data.stats

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomSnapshotAccess
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomDiffAccess
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomExportCapture
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsCaptureStamp
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomState
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatProjection
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import java.io.IOException
import java.util.UUID

internal const val PLAYBACK_STATS_SYNC_DELAY_MS = 60_000L

data class PlaybackStatsCapturedState(
    val revision: Long, val clearedAt: Long, val counterEpochStartedAt: Long = clearedAt
)

internal data class PlaybackStatsCaptureProjection(
    val localeTags: String, val localAlbumNames: Set<String>, val policyVersion: Int = 1
)
internal data class PlaybackStatsSyncCapture(val state: PlaybackStatsCapturedState, val playback: SyncPlaybackSource)
private data class WarmPlaybackCapture(
    val stamp: PlaybackStatsCaptureStamp,
    val projection: PlaybackStatsCaptureProjection,
    val playback: SyncPlaybackSource
)

class PlaybackStatsRepository internal constructor(
    private val app: Context,
    private val roomStore: PlaybackStatsRoomStore = PlaybackStatsRoomStore(NeriUserDataDatabase.getInstance(app.applicationContext)),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val readDeviceId: () -> String = { SecureTokenStorage(app).getOrCreateDeviceId() }
) {
    private val gson = Gson()
    private val mutex = Mutex()
    private val captureCacheMutex = Mutex()
    private var warmCapture: WarmPlaybackCapture? = null
    private val initialLoad = CompletableDeferred<Unit>()
    private val _clearedAt = MutableStateFlow(0L)
    val statsClearedAtFlow: StateFlow<Long> = _clearedAt
    val revisionFlow: Flow<Long> = flow {
        if (!awaitInitialized()) throw IOException("Playback stats initialization failed", initialLoadFailure)
        emitAll(roomStore.revisionFlow)
    }
    @Volatile private var initialized = false
    @Volatile private var pendingWrites = true
    private var initialLoadFailure: Exception? = null
    private var clearObservationJob: Job? = null

    init {
        scope.launch {
            try { mutex.withLock { ensureInitializedLocked() } }
            finally { initialLoad.complete(Unit) }
        }
    }

    private suspend fun ensureInitializedLocked(): Boolean {
        if (initialized) { startClearObservationLocked(); return true }
        return try {
            var state = roomStore.readPrimaryState()
            roomStore.cleanupAbandonedSnapshots()
            if (state == null) {
                PlaybackStatsLegacyImporter(app, gson, roomStore).migrate()
                state = checkNotNull(roomStore.readPrimaryState())
                LegacyJsonCleanupRequests.schedule(app, "playback-stats-import")
            }
            _clearedAt.value = state.clearedAt
            initialLoadFailure = null
            initialized = true
            startClearObservationLocked()
            drainPendingLocked()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            initialLoadFailure = error
            NPLogger.e("PlaybackStatsRepo", "Playback statistics unavailable; preserving the primary store", error)
            false
        }
    }

    private fun startClearObservationLocked() {
        if (clearObservationJob?.isActive == true) return
        clearObservationJob = scope.launch {
            try {
                // 即使写入在提交后的返回阶段取消，也从已提交元数据观察清除代次
                roomStore.clearedAtFlow.collect {
                    mutex.withLock {
                        val state = roomStore.readPrimaryState()
                            ?: throw IOException("Playback statistics primary state is unavailable")
                        _clearedAt.value = state.clearedAt
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                NPLogger.e("PlaybackStatsRepo", "Playback clear observation failed; preserving the observed epoch", error)
            }
        }
    }

    suspend fun awaitInitialized(): Boolean = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock { ensureInitializedLocked() }
    }

    private suspend fun requireInitializedLocked() {
        if (!ensureInitializedLocked()) throw IOException("Playback statistics initialization failed", initialLoadFailure)
    }

    fun hasPendingWrites(): Boolean = pendingWrites

    suspend fun flushPendingWrites() = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have uncommitted journal entries")
        }
    }

    fun recordSession(song: SongItem, listenedMs: Long) {
        if (listenedMs <= 0) return
        scope.launch { recordDelta(song, listenedMs, null, true, UUID.randomUUID().toString(), System.currentTimeMillis()) }
    }

    suspend fun recordListenDeltaNow(
        song: SongItem,
        listenedMs: Long,
        playCountIncrement: Int,
        scheduleSync: Boolean = true,
        eventId: String = UUID.randomUUID().toString(),
        playedAt: Long = System.currentTimeMillis(),
        observedClearedAt: Long? = null
    ) {
        if (listenedMs <= 0 && playCountIncrement <= 0) return
        recordDelta(song, listenedMs, playCountIncrement.coerceAtLeast(0), scheduleSync, eventId, playedAt, observedClearedAt)
    }

    private suspend fun recordDelta(song: SongItem, listenedMs: Long, count: Int?, scheduleSync: Boolean, eventId: String, playedAt: Long,
        observedClearedAt: Long? = null) = withContext(Dispatchers.IO) {
        require(eventId.isNotBlank())
        initialLoad.await()
        mutex.withLock {
            requireInitializedLocked()
            val deviceId = readDeviceId()
            check(deviceId.isNotBlank()) { "Playback counter device identity unavailable" }
            val accepted = roomStore.enqueueDelta(eventId, gson.toJson(song.toStatisticsMetadata()), listenedMs.coerceAtLeast(0), count,
                playedAt, deviceId, observedClearedAt, observeCurrentClear = true)
            // 入队已经是持久确认，应用失败由后续 drain 重试，不能让调用方重复增加计数
            pendingWrites = true
            drainPendingLocked()
            if (accepted && scheduleSync) triggerSync()
        }
    }

    private suspend fun drainPendingLocked(): Boolean {
        return try {
            while (true) {
                val page = roomStore.pendingDeltas()
                if (page.isEmpty()) break
                for (delta in page) {
                    val metadata = gson.fromJson(delta.trackJson, TrackStat::class.java)
                        ?: throw IOException("Playback journal contains no track metadata")
                    roomStore.applyDelta(delta, metadata.identityKey) { rows -> PlaybackStatsDeltaPolicy.apply(delta, metadata, rows) }
                }
            }
            pendingWrites = false
            true
        } catch (error: Exception) {
            pendingWrites = true
            if (error is CancellationException) throw error
            NPLogger.e("PlaybackStatsRepo", "Playback delta retained in the durable journal", error)
            false
        }
    }

    suspend fun captureSyncSnapshot(sink: SyncPlaybackSink): PlaybackStatsCapturedState {
        PlaybackStatsCaptureBarrier.await(app)
        return captureSyncSnapshot(sink, null)
    }

    private suspend fun captureSyncSnapshot(sink: SyncPlaybackSink, projection: SyncPlaybackStatProjection?): PlaybackStatsCapturedState = withContext(Dispatchers.IO) {
        initialLoad.await()
        val capture = mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
            PlaybackStatsRoomExportCapture.open(roomStore)
        }
        try {
            capture.export(app, sink::appendTracks, sink::appendBuckets, projection)
            PlaybackStatsCapturedState(capture.state.revision, capture.state.clearedAt, capture.state.counterEpochStartedAt)
        } finally {
            withContext(NonCancellable) { capture.release() }
        }
    }

    internal suspend fun borrowSyncCapture(store: FileSyncPlaybackDatasetStore): PlaybackStatsSyncCapture =
        borrowSyncCapture(store, ::captureProjection)

    internal suspend fun borrowSyncCapture(store: FileSyncPlaybackDatasetStore,
        readProjection: () -> PlaybackStatsCaptureProjection): PlaybackStatsSyncCapture {
        PlaybackStatsCaptureBarrier.await(app)
        var owned: SyncPlaybackSource? = null
        try {
            return withContext(Dispatchers.IO) {
                captureCacheMutex.withLock {
                    try { borrowSyncCaptureLocked(store, readProjection) }
                    catch (failure: Throwable) {
                        try { invalidateCaptureLocked() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                }.also { owned = it.playback }
            }
        } catch (failure: Throwable) {
            // IO 已取得引用后，返回调用方的调度仍可能被取消
            try { owned?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    internal suspend fun releaseSyncCaptureCache() = withContext(Dispatchers.IO) {
        captureCacheMutex.withLock { invalidateCaptureLocked() }
    }

    private fun captureProjection(): PlaybackStatsCaptureProjection = PlaybackStatsCaptureProjection(
        LanguageManager.applyLanguage(app).resources.configuration.locales.toLanguageTags(),
        LocalFilesPlaylist.candidateNames(app)
    )

    private fun frozenProjection(read: () -> PlaybackStatsCaptureProjection): PlaybackStatsCaptureProjection =
        read().let { it.copy(localAlbumNames = it.localAlbumNames.toSet()) }

    private suspend fun confirmedCaptureStamp(): PlaybackStatsCaptureStamp {
        initialLoad.await()
        return mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
            roomStore.readConfirmedCaptureStamp()
        }
    }

    private suspend fun borrowSyncCaptureLocked(store: FileSyncPlaybackDatasetStore,
        readProjection: () -> PlaybackStatsCaptureProjection): PlaybackStatsSyncCapture {
        val stamp = confirmedCaptureStamp()
        val projection = frozenProjection(readProjection)
        borrowWarmCapture(store, stamp, projection, readProjection)?.let { return it }
        return captureNewSource(store, stamp, projection, readProjection)
    }

    private suspend fun borrowWarmCapture(store: FileSyncPlaybackDatasetStore, stamp: PlaybackStatsCaptureStamp,
        projection: PlaybackStatsCaptureProjection, readProjection: () -> PlaybackStatsCaptureProjection): PlaybackStatsSyncCapture? {
        val cached = warmCapture ?: return null
        if (cached.stamp != stamp || cached.projection != projection) {
            invalidateCaptureLocked()
            return null
        }
        val borrowed = try { store.retainValidated(cached.playback) }
        catch (failure: IOException) {
            NPLogger.w("PlaybackStatsRepo", "Warm playback capture unavailable; recapturing the primary store: ${failure.message}")
            invalidateCaptureLocked()
            return null
        }
        try {
            if (confirmedCaptureStamp() == stamp && frozenProjection(readProjection) == projection) {
                return PlaybackStatsSyncCapture(stamp.state.capturedState(), borrowed)
            }
        } catch (failure: Throwable) {
            try { borrowed.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        borrowed.close()
        invalidateCaptureLocked()
        return null
    }

    private suspend fun captureNewSource(store: FileSyncPlaybackDatasetStore, stamp: PlaybackStatsCaptureStamp,
        projection: PlaybackStatsCaptureProjection, readProjection: () -> PlaybackStatsCaptureProjection): PlaybackStatsSyncCapture =
        store.newOrderedSink().use { sink ->
            val state = captureSyncSnapshot(sink, SyncPlaybackStatMapper.bind(app, projection.localAlbumNames))
            val source = sink.seal()
            var borrowed: SyncPlaybackSource? = null
            try {
                // 先结束 sink 的所有权，避免 use 在返回借用后抛异常而遗失借用引用
                sink.close()
                val retained = store.retainValidated(source)
                borrowed = retained
                if (state == stamp.state.capturedState() && confirmedCaptureStamp() == stamp &&
                    frozenProjection(readProjection) == projection) {
                    warmCapture = WarmPlaybackCapture(stamp, projection, source)
                } else {
                    source.close()
                }
                PlaybackStatsSyncCapture(state, retained)
            } catch (failure: Throwable) {
                try { borrowed?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { source.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

    private fun invalidateCaptureLocked() {
        val previous = warmCapture
        warmCapture = null
        previous?.playback?.close()
    }

    suspend fun writeBackupStatistics(writer: JsonWriter): PlaybackStatsCapturedState = withContext(Dispatchers.IO) {
        PlaybackStatsCaptureBarrier.await(app)
        initialLoad.await()
        val capture = mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
            PlaybackStatsRoomExportCapture.open(roomStore)
        }
        try {
            writer.name("playbackStats").beginArray()
            var bucketsStarted = false
            capture.export(app, { page ->
                page.forEach { gson.toJson(it, SyncTrackStat::class.java, writer) }
            }, { page ->
                if (!bucketsStarted) { writer.endArray().name("playbackStatBuckets").beginArray(); bucketsStarted = true }
                page.forEach { gson.toJson(it, SyncPlaybackStatBucket::class.java, writer) }
            })
            if (!bucketsStarted) writer.endArray().name("playbackStatBuckets").beginArray()
            writer.endArray()
            PlaybackStatsCapturedState(capture.state.revision, capture.state.clearedAt, capture.state.counterEpochStartedAt)
        } finally { withContext(NonCancellable) { capture.release() } }
    }

    suspend fun checkCapturedRevision(expectedRevision: Long): Boolean = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
            roomStore.checkCapturedRevision(expectedRevision)
        }
    }

    suspend fun applySyncSnapshot(source: SyncPlaybackSource, clearedAt: Long, expectedRevision: Long): Boolean = withContext(Dispatchers.IO) {
        initialLoad.await()
        val frozen = mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
            val state = checkNotNull(roomStore.readPrimaryState())
            if (state.revision != expectedRevision) return@withContext false
            roomStore.beginDiffSnapshot(clearedAt)
        }
        try {
            val barrier = maxOf(frozen.clearedAt, clearedAt)
            PlaybackStatsRoomDiffAccess(roomStore).build(frozen.id, source, app)
            mutex.withLock {
                val applied = roomStore.commitDiffSnapshot(frozen.id, expectedRevision)
                if (applied) _clearedAt.value = barrier
                applied
            }
        } finally {
            withContext(NonCancellable) { roomStore.releaseSnapshot(frozen.id) }
        }
    }

    suspend fun getStatForTrack(identityKey: String): TrackStat? = withContext(Dispatchers.IO) {
        if (!awaitInitialized()) throw IOException("Playback statistics unavailable", initialLoadFailure)
        roomStore.readTrack(identityKey)
    }

    suspend fun readSummary(query: PlaybackStatsQuery): PlaybackStatsSummary = withContext(Dispatchers.IO) {
        if (!awaitInitialized()) throw IOException("Playback statistics unavailable", initialLoadFailure)
        roomStore.readSummary(query)
    }

    suspend fun readPage(query: PlaybackStatsQuery, after: PlaybackStatsCursor? = null, pageSize: Int = 100, before: Boolean = false): PlaybackStatsPage = withContext(Dispatchers.IO) {
        if (!awaitInitialized()) throw IOException("Playback statistics unavailable", initialLoadFailure)
        roomStore.readPage(query, after, pageSize, before)
    }

    fun clearAll() {
        scope.launch {
            initialLoad.await()
            mutex.withLock {
                requireInitializedLocked()
                val state = roomStore.clear(System.currentTimeMillis())
                _clearedAt.value = state.clearedAt
                pendingWrites = false
                triggerSync()
            }
        }
    }

    fun removeTracks(keys: Set<String>) {
        if (keys.isEmpty()) return
        scope.launch {
            initialLoad.await()
            mutex.withLock {
                requireInitializedLocked()
                if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
                roomStore.removeTracks(keys)
                triggerSync()
            }
        }
    }

    suspend fun applyMergedStats(syncStats: List<SyncTrackStat>, playbackStatsClearedAt: Long, respectLocalClear: Boolean = true, syncDailyStats: List<SyncPlaybackStatBucket> = emptyList()) {
        mergeBackupStatistics(playbackStatsClearedAt, respectLocalClear) { access, id, barrier ->
            access.mergeLegacyBackup(id, syncStats, syncDailyStats, barrier, respectLocalClear)
        }
    }

    suspend fun applyMergedStats(source: SyncPlaybackSource, playbackStatsClearedAt: Long, respectLocalClear: Boolean = true) {
        mergeBackupStatistics(playbackStatsClearedAt, respectLocalClear) { access, id, barrier ->
            access.mergeLegacyBackup(id, source, barrier, respectLocalClear, app)
        }
    }

    private suspend fun mergeBackupStatistics(playbackStatsClearedAt: Long, respectLocalClear: Boolean,
        merge: suspend (PlaybackStatsRoomSnapshotAccess, String, Long) -> Unit) {
        if (respectLocalClear) mergePreparedBackup(playbackStatsClearedAt, true, merge)
        else PlaybackStatsCaptureBarrier.withRestore(app) {
            mergePreparedBackup(playbackStatsClearedAt, false, merge)
        }
    }

    private suspend fun mergePreparedBackup(playbackStatsClearedAt: Long, respectLocalClear: Boolean,
        merge: suspend (PlaybackStatsRoomSnapshotAccess, String, Long) -> Unit) {
        val clearedAt = playbackStatsClearedAt.coerceAtLeast(0L)
        initialLoad.await()
        val frozen = mutex.withLock {
            requireInitializedLocked()
            if (!drainPendingLocked()) throw IOException("Playback statistics have pending journal entries")
            roomStore.freezeSnapshot()
        }
        try {
            val access = PlaybackStatsRoomSnapshotAccess(roomStore)
            val barrier = if (respectLocalClear) maxOf(frozen.clearedAt, clearedAt) else clearedAt
            merge(access, frozen.id, barrier)
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                // 提交和发布清除时间必须一起完成，恢复采样才能读取真实的删除栅栏
                withContext(NonCancellable) {
                    if (!roomStore.commitFrozenSnapshot(frozen.id, frozen.revision)) throw IOException("Playback statistics changed during backup import")
                    _clearedAt.value = barrier
                }
            }
        } finally { withContext(NonCancellable) { roomStore.releaseSnapshot(frozen.id) } }
    }

    private fun triggerSync() {
        runCatching {
            SecureTokenStorage(app).markSyncMutation()
            GitHubSyncWorker.scheduleDelayedSync(app, triggerByUserAction = false, markMutation = false, initialDelayMs = PLAYBACK_STATS_SYNC_DELAY_MS)
            WebDavSyncWorker.scheduleDelayedSync(app, triggerByUserAction = false, markMutation = false, initialDelayMs = PLAYBACK_STATS_SYNC_DELAY_MS)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak") @Volatile private var INSTANCE: PlaybackStatsRepository? = null
        fun getInstance(context: Context): PlaybackStatsRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PlaybackStatsRepository(context.applicationContext).also { INSTANCE = it }
        }
    }
}

internal fun SongItem.toStatisticsMetadata() = TrackStat(id, name, artist, album, albumId, coverUrl, durationMs, 0, 0, 0, 0,
    mediaUri, localFilePath, localFileName, customName, customArtist, customCoverUrl, stableKey())

private fun PlaybackStatsRoomState.capturedState() = PlaybackStatsCapturedState(revision, clearedAt, counterEpochStartedAt)
