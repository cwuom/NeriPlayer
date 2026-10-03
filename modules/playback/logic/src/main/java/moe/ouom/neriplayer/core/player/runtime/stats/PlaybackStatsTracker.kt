package moe.ouom.neriplayer.core.player.runtime.stats

import android.os.SystemClock
import moe.ouom.neriplayer.data.model.SongItem
import java.util.UUID

const val PLAYBACK_STATS_PERIODIC_FLUSH_MS = 15_000L
private const val PLAYBACK_STATS_MIN_LISTEN_MS_FOR_PLAY_COUNT = 30_000L
private const val NO_ACTIVE_SEGMENT_START_MS = -1L

data class PlaybackStatsSnapshot(
    val song: SongItem,
    val listenedMs: Long,
    val playCountIncrement: Int,
    val scheduleSync: Boolean,
    val localPlaylistId: Long? = null,
    val eventId: String = UUID.randomUUID().toString(),
    val playedAt: Long = System.currentTimeMillis(),
    val observedClearedAt: Long = 0L
)

class PlaybackStatsTracker(
    private val songKey: (SongItem) -> String,
    private val periodicFlushMs: Long = PLAYBACK_STATS_PERIODIC_FLUSH_MS,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val readClearedAt: () -> Long = { 0L }
) {
    private var trackingSong: SongItem? = null
    private var trackingSongKey: String? = null
    private var trackingLocalPlaylistId: Long? = null
    private var segmentStartElapsedMs = NO_ACTIVE_SEGMENT_START_MS
    private var accumulatedMs = 0L
    private var currentPlayListenedMs = 0L
    private var isPlaying = false
    private var hasCountedCurrentPlay = false
    private var lastPlaybackPositionMs: Long? = null
    private var suppressNextPositionWrap = false
    private var observedClearedAt = 0L

    fun onSongChanged(
        song: SongItem?,
        localPlaylistId: Long? = null
    ): PlaybackStatsSnapshot? {
        val newKey = song?.let(songKey)
        if (newKey == trackingSongKey && localPlaylistId == trackingLocalPlaylistId) {
            if (song != null) {
                trackingSong = song
            }
            return null
        }

        collectActiveSegmentLocked()
        val snapshot = flushLocked(countPlay = shouldCountCurrentPlay(), scheduleSync = true)
        trackingSong = song
        trackingSongKey = newKey
        trackingLocalPlaylistId = localPlaylistId
        accumulatedMs = 0L
        currentPlayListenedMs = 0L
        hasCountedCurrentPlay = false
        lastPlaybackPositionMs = null
        suppressNextPositionWrap = false
        segmentStartElapsedMs = NO_ACTIVE_SEGMENT_START_MS
        return snapshot
    }

    fun onPlayingChanged(playing: Boolean): PlaybackStatsSnapshot? {
        if (playing == isPlaying) {
            if (playing) {
                startActiveSegmentIfNeeded()
            }
            return null
        }

        if (playing) {
            isPlaying = true
            startActiveSegmentIfNeeded()
            return null
        }

        collectActiveSegmentLocked()
        isPlaying = false
        segmentStartElapsedMs = NO_ACTIVE_SEGMENT_START_MS
        return flushLocked(countPlay = shouldCountCurrentPlay(), scheduleSync = true)
    }

    fun flushPeriodic(): PlaybackStatsSnapshot? {
        collectActiveSegmentLocked()
        return flushLocked(countPlay = shouldCountCurrentPlay(), scheduleSync = false)
    }

    fun onPlaybackProgress(positionMs: Long): PlaybackStatsSnapshot? {
        val song = trackingSong ?: return null
        val resolvedPositionMs = positionMs.coerceAtLeast(0L)
        val previousPositionMs = lastPlaybackPositionMs
        lastPlaybackPositionMs = resolvedPositionMs
        if (suppressNextPositionWrap) {
            suppressNextPositionWrap = false
            return null
        }
        return if (hasPlaybackPositionWrapped(previousPositionMs, resolvedPositionMs, song.durationMs)) {
            onTrackEnded()
        } else {
            null
        }
    }

    fun onTrackEnded(): PlaybackStatsSnapshot? {
        collectActiveSegmentLocked()
        val snapshot = flushLocked(countPlay = true, scheduleSync = true)
        hasCountedCurrentPlay = false
        currentPlayListenedMs = 0L
        lastPlaybackPositionMs = null
        if (trackingSong != null && isPlaying) {
            segmentStartElapsedMs = nowElapsedMs()
        }
        return snapshot
    }

    fun onManualSeek(positionMs: Long) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        suppressNextPositionWrap = true
    }

    fun flushFinal(): PlaybackStatsSnapshot? {
        collectActiveSegmentLocked()
        return flushLocked(countPlay = shouldCountCurrentPlay(), scheduleSync = true)
    }

    fun suspendForPersistence(): PlaybackStatsSnapshot? {
        return onPlayingChanged(false) ?: flushFinal()
    }

    fun matchesTrackedSong(song: SongItem?, localPlaylistId: Long?): Boolean =
        song?.let(songKey) == trackingSongKey && localPlaylistId == trackingLocalPlaylistId

    fun resetUntrackedPlayCycle() {
        hasCountedCurrentPlay = false
        currentPlayListenedMs = 0L
        lastPlaybackPositionMs = null
        suppressNextPositionWrap = false
    }

    fun rebaseAfterStatisticsRestore() {
        val epoch = readClearedAt().coerceAtLeast(0L)
        // 手动恢复允许降低栅栏，队列已冻结并排空旧事件，真实播放周期的计数状态继续保留
        if (epoch < observedClearedAt) observedClearedAt = epoch else observeClearEpoch()
    }

    fun onUntrackedPlaybackProgress(positionMs: Long) {
        val song = trackingSong ?: return
        val resolved = positionMs.coerceAtLeast(0L)
        val previous = lastPlaybackPositionMs
        lastPlaybackPositionMs = resolved
        if (suppressNextPositionWrap) {
            suppressNextPositionWrap = false
        } else if (hasPlaybackPositionWrapped(previous, resolved, song.durationMs)) {
            resetUntrackedPlayCycle()
        }
    }

    fun shouldFlushPeriodically(): Boolean {
        observeClearEpoch()
        if (!isPlaying || trackingSong == null || periodicFlushMs <= 0L) return false
        val activeSegmentMs = activeSegmentElapsedMs()
        return accumulatedMs + activeSegmentMs >= periodicFlushMs
    }

    private fun collectActiveSegmentLocked() {
        observeClearEpoch()
        if (!isPlaying || segmentStartElapsedMs == NO_ACTIVE_SEGMENT_START_MS) return
        val now = nowElapsedMs()
        if (now > segmentStartElapsedMs) {
            val deltaMs = now - segmentStartElapsedMs
            accumulatedMs += deltaMs
            currentPlayListenedMs += deltaMs
        }
        segmentStartElapsedMs = if (trackingSong != null) {
            now
        } else {
            NO_ACTIVE_SEGMENT_START_MS
        }
    }

    private fun startActiveSegmentIfNeeded() {
        observeClearEpoch()
        if (trackingSong != null && segmentStartElapsedMs == NO_ACTIVE_SEGMENT_START_MS) {
            segmentStartElapsedMs = nowElapsedMs()
        }
    }

    private fun flushLocked(
        countPlay: Boolean,
        scheduleSync: Boolean
    ): PlaybackStatsSnapshot? {
        observeClearEpoch()
        val song = trackingSong ?: return null
        val listenedMs = accumulatedMs.coerceAtLeast(0L)
        val playCountIncrement = if (countPlay && !hasCountedCurrentPlay && currentPlayListenedMs > 0L) 1 else 0
        if (listenedMs <= 0L && playCountIncrement <= 0) return null

        accumulatedMs = 0L
        if (playCountIncrement > 0) {
            hasCountedCurrentPlay = true
        }
        return PlaybackStatsSnapshot(
            song = song,
            listenedMs = listenedMs,
            playCountIncrement = playCountIncrement,
            scheduleSync = scheduleSync,
            localPlaylistId = trackingLocalPlaylistId,
            observedClearedAt = observedClearedAt
        )
    }

    private fun observeClearEpoch() {
        val epoch = maxOf(observedClearedAt, readClearedAt().coerceAtLeast(0L))
        if (epoch == observedClearedAt) return
        // 无法确定这段播放有多少发生在清除前，重新计时避免旧增量回流
        observedClearedAt = epoch
        accumulatedMs = 0L
        currentPlayListenedMs = 0L
        hasCountedCurrentPlay = false
        lastPlaybackPositionMs = null
        segmentStartElapsedMs = if (isPlaying && trackingSong != null) nowElapsedMs() else NO_ACTIVE_SEGMENT_START_MS
    }

    private fun activeSegmentElapsedMs(): Long {
        if (segmentStartElapsedMs == NO_ACTIVE_SEGMENT_START_MS) return 0L
        return (nowElapsedMs() - segmentStartElapsedMs).coerceAtLeast(0L)
    }

    private fun shouldCountCurrentPlay(): Boolean {
        return !hasCountedCurrentPlay &&
            currentPlayListenedMs >= PLAYBACK_STATS_MIN_LISTEN_MS_FOR_PLAY_COUNT
    }
}
