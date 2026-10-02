package moe.ouom.neriplayer.core.player.runtime.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.runtime.blocking.blockingIo
import moe.ouom.neriplayer.data.model.SongItem

class PlaybackStatsOwner(
    private val scope: CoroutineScope,
    private val writes: PlaybackStatsWritePort,
    private val tracker: PlaybackStatsTracker,
    private val blockForPersistence: (Long, suspend () -> Unit) -> Unit = { timeoutMs, block ->
        blockingIo(timeoutMs, block)
    }
) {
    private val persistenceLock = Any()
    private val drainMutex = Mutex()
    private val retainedWrites = ArrayDeque<PlaybackStatsSnapshot>()
    private var persistJob: Job? = null

    fun onSongChanged(song: SongItem?, localPlaylistId: Long?, writesEnabled: Boolean) {
        val snapshot = synchronized(tracker) { tracker.onSongChanged(song, localPlaylistId) }
        persist(snapshot, writesEnabled)
    }

    fun onPlayingChanged(playing: Boolean, reason: String, writesEnabled: Boolean) {
        val snapshot = synchronized(tracker) { tracker.onPlayingChanged(playing) }
        if (snapshot != null) {
            NPLogger.d(
                "NERI-PlayerManager",
                "syncPlaybackStatsPlayingState: reason=$reason, playing=$playing, song=${snapshot.song.name}, listenedMs=${snapshot.listenedMs}, playCountIncrement=${snapshot.playCountIncrement}"
            )
        }
        persist(snapshot, writesEnabled)
    }

    fun onTrackEnded(writesEnabled: Boolean) {
        val snapshot = synchronized(tracker) { tracker.onTrackEnded() }
        persist(snapshot, writesEnabled)
    }

    fun onManualSeek(positionMs: Long) {
        synchronized(tracker) { tracker.onManualSeek(positionMs) }
    }

    fun onProgress(positionMs: Long, writesEnabled: Boolean): Boolean {
        val snapshot = synchronized(tracker) { tracker.onPlaybackProgress(positionMs) }
        persist(snapshot, writesEnabled)
        return snapshot != null
    }

    fun flushPeriodic(writesEnabled: Boolean) {
        val snapshot = synchronized(tracker) {
            if (tracker.shouldFlushPeriodically()) tracker.flushPeriodic() else null
        }
        persist(snapshot, writesEnabled)
    }

    private fun persist(snapshot: PlaybackStatsSnapshot?, writesEnabled: Boolean) {
        if (snapshot == null || !writesEnabled) return
        synchronized(persistenceLock) {
            retainedWrites.addLast(snapshot)
            val previousJob = persistJob
            persistJob = scope.launch {
                previousJob?.join()
                try {
                    drainRetainedWrites()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    NPLogger.e("NERI-PlayerManager", "Playback delta remains unaccepted; retrying on the next flush", error)
                }
            }
        }
    }

    private suspend fun drainRetainedWrites() = drainMutex.withLock {
        while (true) {
            val snapshot = synchronized(persistenceLock) { retainedWrites.firstOrNull() } ?: break
            writes.record(snapshot)
            // 只有持久入队确认后才释放；重试复用相同事件 id 和播放时间
            synchronized(persistenceLock) { retainedWrites.removeFirst() }
        }
    }

    fun drainBlocking(reason: String, writesEnabled: Boolean) {
        if (!writesEnabled) return
        drainPendingBlocking(reason)
    }

    private fun drainPendingBlocking(reason: String) {
        val pendingJob = pendingJob()
        if (!hasPendingWork(pendingJob)) return
        NPLogger.d("NERI-PlayerManager", "drainPlaybackStatsPersistJobBlocking: reason=$reason")
        blockForPersistence(3_000L) {
            pendingJob?.join()
            drainRetainedWrites()
            writes.flushPendingWrites()
        }
        clearCompletedJob(pendingJob)
    }

    fun flushBlocking(reason: String, stopTracking: Boolean, writesEnabled: Boolean) {
        if (!writesEnabled) return
        flushBlockingWhenEnabled(reason, stopTracking)
    }

    private fun flushBlockingWhenEnabled(reason: String, stopTracking: Boolean) {
        val pendingJob = pendingJob()
        val snapshot = finalSnapshot(stopTracking)
        snapshot?.let { synchronized(persistenceLock) { retainedWrites.addLast(it) } }
        clearTrackedSongIfStopped(stopTracking)
        if (!hasBlockingFlushWork(pendingJob, snapshot)) return
        logFlush("flushPlaybackStatsBlocking", reason, snapshot)
        blockForPersistence(2_000L) {
            pendingJob?.join()
            drainRetainedWrites()
            writes.flushPendingWrites()
        }
        clearCompletedJob(pendingJob)
    }

    private fun clearTrackedSongIfStopped(stopTracking: Boolean) {
        if (stopTracking) clearTrackedSong()
    }

    private fun hasBlockingFlushWork(job: Job?, snapshot: PlaybackStatsSnapshot?): Boolean =
        hasPendingWork(job) || snapshot != null

    fun flushAsync(reason: String, stopTracking: Boolean, writesEnabled: Boolean) {
        if (!writesEnabled) return
        val snapshot = finalSnapshot(stopTracking)
        logFlush("flushPlaybackStatsAsync", reason, snapshot)
        persist(snapshot, writesEnabled)
        if (stopTracking) clearTrackedSong()
    }

    fun cancelSharedScopeAfterWrites() {
        val pendingJob = pendingJob()
        if (pendingJob == null) {
            scope.cancel()
            return
        }
        scope.launch {
            pendingJob.join()
            scope.cancel()
        }
    }

    private fun finalSnapshot(stopTracking: Boolean): PlaybackStatsSnapshot? {
        val snapshot = synchronized(tracker) {
            if (stopTracking) {
                tracker.onPlayingChanged(false) ?: tracker.flushFinal()
            } else {
                tracker.flushFinal()
            }
        }
        return snapshot
    }

    private fun clearTrackedSong() {
        synchronized(tracker) { tracker.onSongChanged(null) }
    }

    private fun pendingJob(): Job? = synchronized(persistenceLock) { persistJob }

    private fun hasPendingWork(job: Job?): Boolean =
        (job != null && !job.isCompleted) || hasRetainedWrites() || writes.hasPendingWrites()

    private fun hasRetainedWrites(): Boolean = synchronized(persistenceLock) { retainedWrites.isNotEmpty() }

    private fun clearCompletedJob(job: Job?) {
        if (job != null) clearCompletedJobIfFinished(job)
    }

    private fun clearCompletedJobIfFinished(job: Job) {
        if (job.isCompleted) clearCompletedJobIfCurrent(job)
    }

    private fun clearCompletedJobIfCurrent(job: Job) {
        synchronized(persistenceLock) {
            if (persistJob === job) persistJob = null
        }
    }

    private fun logFlush(label: String, reason: String, snapshot: PlaybackStatsSnapshot?) {
        snapshot ?: return
        NPLogger.d(
            "NERI-PlayerManager",
            "$label: reason=$reason, song=${snapshot.song.name}, listenedMs=${snapshot.listenedMs}, playCountIncrement=${snapshot.playCountIncrement}"
        )
    }
}
