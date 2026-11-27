package moe.ouom.neriplayer.core.player.runtime.report

import android.os.SystemClock
import moe.ouom.neriplayer.core.player.runtime.stats.hasPlaybackPositionWrapped
import moe.ouom.neriplayer.data.model.SongItem

const val PLATFORM_PLAY_REPORT_HEARTBEAT_MS = 15_000L
private const val PLATFORM_PLAY_REPORT_END_TOLERANCE_MS = 3_000L

/**
 * 一次连续收听的上报事件。
 *
 * [listenedMs] 只统计真实播放时长，拖动进度和暂停都不计入。
 */
sealed interface PlatformPlayReportEvent {
    val song: SongItem
    val positionMs: Long
    val listenedMs: Long

    data class Heartbeat(
        override val song: SongItem,
        override val positionMs: Long,
        override val listenedMs: Long
    ) : PlatformPlayReportEvent

    data class Finished(
        override val song: SongItem,
        override val positionMs: Long,
        override val listenedMs: Long,
        val completed: Boolean
    ) : PlatformPlayReportEvent
}

/**
 * 把播放器状态流折算成"一首歌听了多久"的会话。
 *
 * 只依赖当前歌曲、播放状态和进度三个输入，便于在播放器外部以观察者身份运行，
 * 不需要侵入播放核心的各个调用点；单曲循环通过进度回绕识别为新的一次收听。
 */
class PlatformPlayReportTracker(
    private val songKey: (SongItem) -> String,
    private val heartbeatIntervalMs: Long = PLATFORM_PLAY_REPORT_HEARTBEAT_MS,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var song: SongItem? = null
    private var trackedKey: String? = null
    private var playing = false
    private var segmentStartElapsedMs: Long? = null
    private var listenedMs = 0L
    private var listenedAtLastHeartbeatMs = 0L
    private var lastPositionMs: Long? = null

    fun onSongChanged(newSong: SongItem?): List<PlatformPlayReportEvent> {
        val newKey = newSong?.let(songKey)
        if (newKey == trackedKey) {
            if (newSong != null) song = newSong
            return emptyList()
        }
        val events = listOfNotNull(finishSession(completed = isNearEnd()))
        song = newSong
        trackedKey = newKey
        resetSession()
        return events
    }

    fun onPlayingChanged(isPlaying: Boolean): List<PlatformPlayReportEvent> {
        if (isPlaying == playing) return emptyList()
        playing = isPlaying
        if (isPlaying) {
            startSegment()
            return emptyList()
        }
        collectSegment()
        segmentStartElapsedMs = null
        // 队列最后一首播完时不会切歌，停在片尾就直接结算，避免记录一直悬着
        if (isNearEnd()) {
            val finished = finishSession(completed = true)
            resetSession()
            return listOfNotNull(finished)
        }
        // 暂停时补一次心跳，让平台记录到暂停位置
        return listOfNotNull(heartbeat())
    }

    fun onProgress(positionMs: Long): List<PlatformPlayReportEvent> {
        val current = song ?: return emptyList()
        val resolved = positionMs.coerceAtLeast(0L)
        val previous = lastPositionMs
        lastPositionMs = resolved
        if (hasPlaybackPositionWrapped(previous, resolved, current.durationMs) && hasHeardMostOf(current)) {
            val finished = finishSession(completed = true, positionMs = previous ?: resolved)
            resetSession()
            lastPositionMs = resolved
            startSegment()
            return listOfNotNull(finished)
        }
        if (!playing) return emptyList()
        collectSegment()
        startSegment()
        if (listenedMs - listenedAtLastHeartbeatMs < heartbeatIntervalMs) return emptyList()
        return listOfNotNull(heartbeat())
    }

    // 只有真正听过大半首才算单曲循环，切歌时残留的上一首进度不会被当成播完
    private fun hasHeardMostOf(current: SongItem): Boolean {
        collectSegment()
        startSegment()
        return listenedMs * 2 >= current.durationMs
    }

    private fun heartbeat(): PlatformPlayReportEvent.Heartbeat? {
        // 累计时长只增不减，没有新增收听就不重复上报同一个位置
        val current = song?.takeIf { listenedMs > listenedAtLastHeartbeatMs } ?: return null
        listenedAtLastHeartbeatMs = listenedMs
        return PlatformPlayReportEvent.Heartbeat(
            song = current,
            positionMs = lastPositionMs ?: 0L,
            listenedMs = listenedMs
        )
    }

    private fun finishSession(
        completed: Boolean,
        positionMs: Long? = lastPositionMs
    ): PlatformPlayReportEvent.Finished? {
        collectSegment()
        val current = song ?: return null
        if (listenedMs <= 0L) return null
        return PlatformPlayReportEvent.Finished(
            song = current,
            positionMs = positionMs ?: 0L,
            listenedMs = listenedMs,
            completed = completed
        )
    }

    private fun resetSession() {
        listenedMs = 0L
        listenedAtLastHeartbeatMs = 0L
        lastPositionMs = null
        segmentStartElapsedMs = null
        if (playing) startSegment()
    }

    private fun startSegment() {
        if (song == null || !playing || segmentStartElapsedMs != null) return
        segmentStartElapsedMs = nowElapsedMs()
    }

    private fun collectSegment() {
        val start = segmentStartElapsedMs ?: return
        val now = nowElapsedMs()
        if (now > start) listenedMs += now - start
        segmentStartElapsedMs = null
    }

    private fun isNearEnd(): Boolean {
        val durationMs = song?.durationMs ?: return false
        val positionMs = lastPositionMs ?: return false
        return durationMs > 0L && positionMs >= durationMs - PLATFORM_PLAY_REPORT_END_TOLERANCE_MS
    }
}
