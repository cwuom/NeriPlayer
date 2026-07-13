package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherPlaybackState
import kotlin.math.abs

internal fun ListenTogetherPlaybackState.expectedPositionMs(
    nowMs: Long = System.currentTimeMillis(),
    serverClockOffsetMs: Long = 0L
): Long {
    val correctedNowMs = nowMs + serverClockOffsetMs
    return if (state == "playing") {
        (basePositionMs + ((correctedNowMs - baseTimestampMs) * playbackRate)).toLong().coerceAtLeast(0L)
    } else {
        basePositionMs.coerceAtLeast(0L)
    }
}

internal fun isListenTogetherSeekControlSatisfied(
    playback: ListenTogetherPlaybackState,
    requestedPositionMs: Long,
    satisfiedDriftMs: Long = 1_500L
): Boolean {
    return abs(playback.basePositionMs.coerceAtLeast(0L) - requestedPositionMs.coerceAtLeast(0L)) <= satisfiedDriftMs
}
