package moe.ouom.neriplayer.core.player.runtime.stats

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem

interface PlaybackStatsPendingStore {
    fun append(snapshot: PlaybackStatsSnapshot)
    fun first(): PlaybackStatsSnapshot?
    fun acknowledge(eventId: String)
}

class PlaybackStatsPendingWrites(
    private val store: PlaybackStatsPendingStore,
    private val persistenceScope: CoroutineScope
) {
    private val lock = Any()
    private val drainMutex = Mutex()
    private val restoreMutex = Mutex()
    private val awaitingStorage = ArrayDeque<PlaybackStatsSnapshot>()
    private var writes: PlaybackStatsWritePort? = null
    private var stagingJob: Job? = null
    private var drainJob: Job? = null
    private var stagingFailure: Exception? = null
    private var durableWorkPossible = false
    private var storageGeneration = 0L
    private var sampler: PlaybackStatsOwner? = null
    private var restoring = false

    internal fun registerSampler(owner: PlaybackStatsOwner) = synchronized(lock) {
        sampler?.retireSampling()
        sampler = owner
    }

    internal fun collect(owner: PlaybackStatsOwner, action: () -> Unit) = synchronized(lock) {
        if (sampler === owner) action()
    }

    val canCollect: Boolean
        get() = synchronized(lock) { !restoring && stagingFailure == null && awaitingStorage.size < HANDOFF_CAPACITY }

    internal val inMemoryEventCount: Int
        get() = synchronized(lock) { awaitingStorage.size }

    internal val isRestoringStatistics: Boolean
        get() = synchronized(lock) { restoring }

    fun activate(port: PlaybackStatsWritePort) = synchronized(lock) {
        if (writes == null) {
            writes = port
            durableWorkPossible = true
            startDrainLocked()
        } else {
            check(writes === port) { "A playback journal cannot use different persistence ports" }
        }
    }

    fun enqueue(snapshot: PlaybackStatsSnapshot): Boolean = synchronized(lock) {
        // 正常交接之外只留停止采样时的两个已采事件，不再无限接收播放增量
        check(awaitingStorage.size < MAX_CAPTURED_EVENTS) { "Playback collection exceeded its suspended boundary" }
        awaitingStorage.addLast(snapshot.copy(song = snapshot.song.forPlaybackStatistics()))
        startStagingLocked()
        stagingFailure == null && awaitingStorage.size < HANDOFF_CAPACITY
    }

    fun retry() = synchronized(lock) {
        startStagingLocked()
        startDrainLocked()
    }

    fun hasPendingWork(): Boolean = synchronized(lock) {
        awaitingStorage.isNotEmpty() || durableWorkPossible || stagingJob != null || drainJob != null
    }

    suspend fun flush(port: PlaybackStatsWritePort) {
        activate(port)
        val staging = synchronized(lock) {
            startStagingLocked()
            stagingJob
        }
        staging?.join()
        synchronized(lock) { stagingFailure }?.let { throw it }
        drain(port)
        port.flushPendingWrites()
    }

    suspend fun withStatisticsRestore(port: PlaybackStatsWritePort, block: suspend () -> Unit) {
        activate(port)
        if (coroutineContext[RestoreSession]?.queue === this) {
            block()
            return
        }
        restoreMutex.withLock {
            withContext(RestoreSession(this)) {
                try {
                    synchronized(lock) {
                        restoring = true
                        sampler?.pauseForStatisticsRestore()
                    }
                    flush(port)
                    block()
                } finally {
                    synchronized(lock) {
                        try { sampler?.rebaseAfterStatisticsRestore() } finally { restoring = false }
                    }
                }
            }
        }
    }

    private fun startStagingLocked() {
        if (stagingJob != null || awaitingStorage.isEmpty()) return
        val job = persistenceScope.launch(start = CoroutineStart.LAZY) { stage() }
        stagingJob = job
        job.start()
    }

    private suspend fun stage() {
        try {
            while (true) {
                val snapshot = synchronized(lock) {
                    awaitingStorage.firstOrNull().also { if (it == null) stagingJob = null }
                } ?: return
                store.append(snapshot)
                synchronized(lock) {
                    awaitingStorage.removeFirst()
                    stagingFailure = null
                    durableWorkPossible = true
                    storageGeneration++
                    startDrainLocked()
                }
            }
        } catch (error: Exception) {
            synchronized(lock) { stagingFailure = error; stagingJob = null }
            if (error is CancellationException) throw error
            NPLogger.e("PlaybackStats", "Playback journal is unavailable; sampled events remain pending", error)
        }
    }

    private fun startDrainLocked() {
        val port = writes ?: return
        if (drainJob != null || !durableWorkPossible) return
        val job = persistenceScope.launch(start = CoroutineStart.LAZY) { drainAsynchronously(port) }
        drainJob = job
        job.start()
    }

    private suspend fun drainAsynchronously(port: PlaybackStatsWritePort) {
        var completed = false
        try {
            drain(port)
            completed = true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            NPLogger.e("PlaybackStats", "Playback journal remains unaccepted; retrying on the next flush", error)
        } finally {
            synchronized(lock) {
                drainJob = null
                if (completed) startDrainLocked()
            }
        }
    }

    private suspend fun drain(port: PlaybackStatsWritePort) = drainMutex.withLock {
        while (true) {
            val generation = synchronized(lock) { storageGeneration }
            val snapshot = store.first()
            if (snapshot == null) {
                val finished = synchronized(lock) {
                    if (generation != storageGeneration) false else {
                        durableWorkPossible = false
                        true
                    }
                }
                if (finished) return@withLock
                continue
            }
            port.record(snapshot)
            // 两个仓库都确认后才推进持久游标，取消或返回失败仍重试原事件
            store.acknowledge(snapshot.eventId)
        }
    }

    companion object {
        const val HANDOFF_CAPACITY = 32
        const val MAX_CAPTURED_EVENTS = HANDOFF_CAPACITY + 2
    }

    private class RestoreSession(val queue: PlaybackStatsPendingWrites) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RestoreSession>
    }
}

internal fun SongItem.forPlaybackStatistics() =
    SongItem(id, name, artist, album, albumId, durationMs, coverUrl,
        mediaUri = mediaUri, customCoverUrl = customCoverUrl, customName = customName,
        customArtist = customArtist, localFileName = localFileName, localFilePath = localFilePath,
        channelId = channelId, audioId = audioId, subAudioId = subAudioId, sourceStableKey = sourceStableKey)
