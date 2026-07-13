package moe.ouom.neriplayer.listentogether.control

import moe.ouom.neriplayer.listentogether.playback.mergeCurrentTrack
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope

internal fun buildListenTogetherForwardedControlSyntheticState(
    currentState: ListenTogetherRoomState,
    message: ListenTogetherSocketEnvelope,
    committedEvent: ListenTogetherEvent,
    nowMs: Long = System.currentTimeMillis()
): ListenTogetherRoomState {
    val nextQueue = message.queue
        ?.mergeCurrentTrack(message.currentIndex ?: currentState.currentIndex, message.track)
        ?: currentState.queue.mergeCurrentTrack(currentState.currentIndex, currentState.track)
    val nextIndex = (message.currentIndex ?: currentState.currentIndex).coerceIn(
        0,
        nextQueue.lastIndex.coerceAtLeast(0)
    )
    val nextTrack = message.track ?: nextQueue.getOrNull(nextIndex) ?: currentState.track
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
