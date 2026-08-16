package moe.ouom.neriplayer.listentogether.control

import moe.ouom.neriplayer.listentogether.playback.mergeCurrentTrack
import moe.ouom.neriplayer.listentogether.playback.currentTrack
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherQueueIndex
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope

internal fun buildListenTogetherForwardedControlSyntheticState(
    currentState: ListenTogetherRoomState,
    message: ListenTogetherSocketEnvelope,
    committedEvent: ListenTogetherEvent,
    nowMs: Long = System.currentTimeMillis()
): ListenTogetherRoomState {
    // 空 queue 的转发请求视为"不改动队列", 回退当前房间队列, 避免被清空
    val queueWithoutCurrentTrack = message.queue
        ?.takeIf { it.isNotEmpty() }
        ?: currentState.queue.mergeCurrentTrack(currentState.currentIndex, currentState.track)
    val requestedIndex = message.currentIndex ?: currentState.currentIndex
    val preferredStableKey = message.requestTrackStableKey
        ?: committedEvent.requestTrackStableKey
    val nextIndex = resolveListenTogetherQueueIndex(
        queue = queueWithoutCurrentTrack,
        requestedIndex = requestedIndex,
        preferredStableKey = preferredStableKey
    )
    val nextQueue = queueWithoutCurrentTrack.mergeCurrentTrack(nextIndex, message.track)
    val nextTrack = nextQueue.getOrNull(nextIndex)
        ?: message.track
        ?: currentState.currentTrack()
    val nextPlaybackState = when (committedEvent.type) {
        "PLAY" -> "playing"
        "PAUSE" -> "paused"
        else -> message.stateName ?: if (message.shouldPlay == true) "playing" else currentState.playback.state
    }
    return currentState.copy(
        queue = nextQueue,
        currentIndex = nextIndex,
        track = nextTrack,
        playback = currentState.playback.copy(
            state = nextPlaybackState,
            basePositionMs = (committedEvent.positionMs ?: message.expectedPositionMs ?: 0L).coerceAtLeast(0L),
            baseTimestampMs = nowMs,
            repeatMode = committedEvent.repeatMode ?: currentState.playback.repeatMode,
            shuffleEnabled = committedEvent.shuffleEnabled ?: currentState.playback.shuffleEnabled
        )
    )
}
