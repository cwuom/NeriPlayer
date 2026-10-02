package moe.ouom.neriplayer.core.player.runtime.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.runtime.blocking.blockingIo
import moe.ouom.neriplayer.data.model.SongItem

class PlaybackStatsOwner(
    private val scope: CoroutineScope,
    private val writes: PlaybackStatsWritePort,
    private val tracker: PlaybackStatsTracker,
    private val pendingWrites: PlaybackStatsPendingWrites,
    private val blockForPersistence: (Long, suspend () -> Unit) -> Unit = { timeoutMs, block ->
        blockingIo(timeoutMs, block)
    }
) {
    private var currentSong: SongItem? = null
    private var currentPlaylistId: Long? = null
    private var currentPlaying = false
    private var samplingSuspended = false
    private var statisticsEnabled = false

    init { pendingWrites.registerSampler(this) }

    fun onSongChanged(song: SongItem?, localPlaylistId: Long?, writesEnabled: Boolean) = pendingWrites.collect(this) {
        if (samplingSuspended && !tracker.matchesTrackedSong(song, localPlaylistId)) tracker.resetUntrackedPlayCycle()
        currentSong = song
        currentPlaylistId = localPlaylistId
        if (!canSample(writesEnabled)) return@collect
        val snapshot = synchronized(tracker) { tracker.onSongChanged(song, localPlaylistId) }
        persist(snapshot, writesEnabled)
    }

    fun onPlayingChanged(playing: Boolean, reason: String, writesEnabled: Boolean) = pendingWrites.collect(this) {
        currentPlaying = playing
        if (!canSample(writesEnabled)) return@collect
        val snapshot = synchronized(tracker) { tracker.onPlayingChanged(playing) }
        if (snapshot != null) {
            NPLogger.d(
                "NERI-PlayerManager",
                "syncPlaybackStatsPlayingState: reason=$reason, playing=$playing, song=${snapshot.song.name}, listenedMs=${snapshot.listenedMs}, playCountIncrement=${snapshot.playCountIncrement}"
            )
        }
        persist(snapshot, writesEnabled)
    }

    fun onTrackEnded(writesEnabled: Boolean) = pendingWrites.collect(this) {
        if (!canSample(writesEnabled)) {
            tracker.resetUntrackedPlayCycle()
            return@collect
        }
        val snapshot = synchronized(tracker) { tracker.onTrackEnded() }
        persist(snapshot, writesEnabled)
    }

    fun onManualSeek(positionMs: Long) = pendingWrites.collect(this) {
        synchronized(tracker) { tracker.onManualSeek(positionMs) }
    }

    fun onProgress(positionMs: Long, writesEnabled: Boolean): Boolean {
        var sampled = false
        pendingWrites.collect(this) {
            if (!canSample(writesEnabled)) {
                tracker.onUntrackedPlaybackProgress(positionMs)
                return@collect
            }
            val snapshot = synchronized(tracker) { tracker.onPlaybackProgress(positionMs) }
            persist(snapshot, writesEnabled)
            sampled = snapshot != null
        }
        return sampled
    }

    fun flushPeriodic(writesEnabled: Boolean) = pendingWrites.collect(this) {
        if (!canSample(writesEnabled)) return@collect
        val snapshot = synchronized(tracker) {
            if (tracker.shouldFlushPeriodically()) tracker.flushPeriodic() else null
        }
        persist(snapshot, writesEnabled)
    }

    private fun persist(snapshot: PlaybackStatsSnapshot?, writesEnabled: Boolean) {
        if (snapshot == null || !writesEnabled) return
        pendingWrites.activate(writes)
        if (!pendingWrites.enqueue(snapshot)) suspendSampling()
    }

    private fun canSample(writesEnabled: Boolean): Boolean {
        statisticsEnabled = writesEnabled
        if (pendingWrites.isRestoringStatistics) {
            pauseForStatisticsRestore()
            return false
        }
        if (!writesEnabled) return true
        pendingWrites.activate(writes)
        if (!pendingWrites.canCollect) {
            suspendSampling()
            pendingWrites.retry()
            return false
        }
        resumeSampling()
        return true
    }

    private fun suspendSampling() {
        if (!pauseSampling()) return
        NPLogger.e("PlaybackStats", "Playback statistics collection suspended; new listening time is not recorded while storage is unavailable")
    }

    private fun pauseSampling(): Boolean {
        if (samplingSuspended) return false
        samplingSuspended = true
        val final = synchronized(tracker) { tracker.suspendForPersistence() }
        if (statisticsEnabled) final?.let(pendingWrites::enqueue)
        return true
    }

    internal fun pauseForStatisticsRestore() { pauseSampling() }

    internal fun rebaseAfterStatisticsRestore() = synchronized(tracker) { tracker.rebaseAfterStatisticsRestore() }

    internal fun retireSampling() {
        if (!samplingSuspended) {
            samplingSuspended = true
            val final = synchronized(tracker) { tracker.suspendForPersistence() }
            if (statisticsEnabled) final?.let(pendingWrites::enqueue)
        }
        currentSong = null
        currentPlaylistId = null
        currentPlaying = false
        tracker.onSongChanged(null)
    }

    private fun resumeSampling() {
        if (!samplingSuspended) return
        synchronized(tracker) {
            tracker.onSongChanged(currentSong, currentPlaylistId)
            tracker.onPlayingChanged(currentPlaying)
        }
        samplingSuspended = false
        NPLogger.d("PlaybackStats", "Playback statistics collection resumed; the unavailable interval was not recorded")
    }

    fun drainBlocking(reason: String, writesEnabled: Boolean) {
        if (!writesEnabled) return
        drainPendingBlocking(reason)
    }

    private fun drainPendingBlocking(reason: String) {
        if (!hasPendingWork()) return
        NPLogger.d("NERI-PlayerManager", "drainPlaybackStatsPersistJobBlocking: reason=$reason")
        blockForPersistence(3_000L) {
            pendingWrites.flush(writes)
        }
    }

    fun flushBlocking(reason: String, stopTracking: Boolean, writesEnabled: Boolean) {
        if (!writesEnabled) return
        flushBlockingWhenEnabled(reason, stopTracking)
    }

    private fun flushBlockingWhenEnabled(reason: String, stopTracking: Boolean) {
        var snapshot: PlaybackStatsSnapshot? = null
        pendingWrites.collect(this) {
            snapshot = if (canSample(true)) finalSnapshot(stopTracking) else null
            persist(snapshot, true)
            clearTrackedSongIfStopped(stopTracking)
        }
        if (!hasPendingWork()) return
        logFlush("flushPlaybackStatsBlocking", reason, snapshot)
        blockForPersistence(2_000L) {
            pendingWrites.flush(writes)
        }
    }

    private fun clearTrackedSongIfStopped(stopTracking: Boolean) {
        if (stopTracking) clearTrackedSong()
    }

    fun flushAsync(reason: String, stopTracking: Boolean, writesEnabled: Boolean) = pendingWrites.collect(this) {
        if (!writesEnabled) return@collect
        val snapshot = if (canSample(true)) finalSnapshot(stopTracking) else null
        logFlush("flushPlaybackStatsAsync", reason, snapshot)
        persist(snapshot, writesEnabled)
        if (stopTracking) clearTrackedSong()
    }

    fun cancelSharedScopeAfterWrites() {
        // 统计写入由独立共享队列持有，释放播放器不能取消后续播放器的重放
        scope.cancel()
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
        currentSong = null
        currentPlaylistId = null
        currentPlaying = false
        synchronized(tracker) { tracker.onSongChanged(null) }
    }

    private fun hasPendingWork(): Boolean = pendingWrites.hasPendingWork() || writes.hasPendingWrites()

    private fun logFlush(label: String, reason: String, snapshot: PlaybackStatsSnapshot?) {
        snapshot ?: return
        NPLogger.d(
            "NERI-PlayerManager",
            "$label: reason=$reason, song=${snapshot.song.name}, listenedMs=${snapshot.listenedMs}, playCountIncrement=${snapshot.playCountIncrement}"
        )
    }
}
