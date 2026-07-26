package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import kotlin.math.abs

internal fun ListenTogetherPlaybackState.expectedPositionMs(
    nowMs: Long = System.currentTimeMillis(),
    serverClockOffsetMs: Long = 0L
): Long {
    val correctedNowMs = nowMs + serverClockOffsetMs
    return if (state == "playing") {
        // baseTimestampMs 非法(<=0)时回退到当前时钟，避免 elapsed 差值把位置推到天文数字
        val effectiveBaseTimestampMs = if (baseTimestampMs > 0L) baseTimestampMs else correctedNowMs
        (basePositionMs + ((correctedNowMs - effectiveBaseTimestampMs) * playbackRate))
            .toLong()
            .coerceAtLeast(0L)
    } else {
        basePositionMs.coerceAtLeast(0L)
    }
}

internal fun clampListenTogetherPositionMs(positionMs: Long, durationMs: Long): Long {
    val floored = positionMs.coerceAtLeast(0L)
    return if (durationMs > 0L) floored.coerceAtMost(durationMs) else floored
}

internal fun isListenTogetherSeekControlSatisfied(
    playback: ListenTogetherPlaybackState,
    requestedPositionMs: Long,
    satisfiedDriftMs: Long = 1_500L
): Boolean {
    return abs(playback.basePositionMs.coerceAtLeast(0L) - requestedPositionMs.coerceAtLeast(0L)) <= satisfiedDriftMs
}
