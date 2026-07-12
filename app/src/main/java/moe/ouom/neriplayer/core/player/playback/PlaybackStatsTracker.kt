package moe.ouom.neriplayer.core.player.playback

import android.os.SystemClock
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal const val PLAYBACK_STATS_PERIODIC_FLUSH_MS = 15_000L
private const val PLAYBACK_STATS_MIN_LISTEN_MS_FOR_PLAY_COUNT = 30_000L
private const val NO_ACTIVE_SEGMENT_START_MS = -1L
private const val PLAYBACK_POSITION_WRAP_TOLERANCE_MS = 3_000L

internal data class PlaybackStatsSnapshot(
    val song: SongItem,
    val listenedMs: Long,
    val playCountIncrement: Int,
    val scheduleSync: Boolean
)

internal class PlaybackStatsTracker(
    private val periodicFlushMs: Long = PLAYBACK_STATS_PERIODIC_FLUSH_MS,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var trackingSong: SongItem? = null
    private var trackingSongKey: String? = null
    private var segmentStartElapsedMs = NO_ACTIVE_SEGMENT_START_MS
    private var accumulatedMs = 0L
    private var currentPlayListenedMs = 0L
    private var isPlaying = false
    private var hasCountedCurrentPlay = false
    private var lastPlaybackPositionMs: Long? = null
    private var suppressNextPositionWrap = false

    fun onSongChanged(song: SongItem?): PlaybackStatsSnapshot? {
        val newKey = song?.stableKey()
        if (newKey == trackingSongKey) {
            if (song != null) {
                trackingSong = song
            }
            return null
        }

        collectActiveSegmentLocked()
        val snapshot = flushLocked(countPlay = shouldCountCurrentPlay(), scheduleSync = true)
        trackingSong = song
        trackingSongKey = newKey
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
        val durationMs = song.durationMs.takeIf { it > 0L } ?: return null
        if (previousPositionMs == null || previousPositionMs <= resolvedPositionMs) {
            return null
        }

        val nearEnd = previousPositionMs >=
            (durationMs - PLAYBACK_POSITION_WRAP_TOLERANCE_MS).coerceAtLeast(0L)
        val nearStart = resolvedPositionMs <= PLAYBACK_POSITION_WRAP_TOLERANCE_MS
        return if (nearEnd && nearStart) {
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

    fun shouldFlushPeriodically(): Boolean {
        if (!isPlaying || trackingSong == null || periodicFlushMs <= 0L) return false
        val activeSegmentMs = activeSegmentElapsedMs()
        return accumulatedMs + activeSegmentMs >= periodicFlushMs
    }

    private fun collectActiveSegmentLocked() {
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
        if (trackingSong != null && segmentStartElapsedMs == NO_ACTIVE_SEGMENT_START_MS) {
            segmentStartElapsedMs = nowElapsedMs()
        }
    }

    private fun flushLocked(
        countPlay: Boolean,
        scheduleSync: Boolean
    ): PlaybackStatsSnapshot? {
        val song = trackingSong ?: return null
        val listenedMs = accumulatedMs.coerceAtLeast(0L)
        val playCountIncrement = if (countPlay && !hasCountedCurrentPlay) 1 else 0
        if (listenedMs <= 0L && playCountIncrement <= 0) return null

        accumulatedMs = 0L
        if (playCountIncrement > 0) {
            hasCountedCurrentPlay = true
        }
        return PlaybackStatsSnapshot(
            song = song,
            listenedMs = listenedMs,
            playCountIncrement = playCountIncrement,
            scheduleSync = scheduleSync
        )
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
