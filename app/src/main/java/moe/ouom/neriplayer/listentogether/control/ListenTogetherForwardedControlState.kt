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
    val currentQueue = currentState.queue.mergeCurrentTrack(
        currentState.currentIndex,
        currentState.track
    )
    val mutationResult = message.queueMutation?.let { mutation ->
        applyListenTogetherQueueMutation(
            roomQueue = currentQueue,
            roomCurrentIndex = currentState.currentIndex,
            mutation = mutation,
            targetCurrentStableKey = message.track?.stableKey
        )
    }
    val legacyQueueSnapshot = message.queue?.takeIf { queue ->
        queue.isNotEmpty() || committedEvent.type == "SET_QUEUE"
    }
    val queueWithoutCurrentTrack = mutationResult?.queue
        ?: legacyQueueSnapshot
        ?: currentQueue
    val isTrackSelection = committedEvent.type == "SET_TRACK"
    val requestedIndex = if (isTrackSelection) {
        mutationResult?.targetCurrentIndex
            ?: mutationResult?.currentIndex
            ?: message.currentIndex
            ?: currentState.currentIndex
    } else {
        mutationResult?.currentIndex
            ?: message.currentIndex
            ?: currentState.currentIndex
    }
    val preferredStableKey = if (isTrackSelection) {
        message.requestTrackStableKey
            ?: committedEvent.requestTrackStableKey
            ?: message.track?.stableKey
            ?: committedEvent.track?.stableKey
    } else {
        message.requestTrackStableKey
            ?: committedEvent.requestTrackStableKey
    }
    val nextIndex = resolveListenTogetherQueueIndex(
        queue = queueWithoutCurrentTrack,
        requestedIndex = requestedIndex,
        preferredStableKey = preferredStableKey
    )
    val nextQueue = queueWithoutCurrentTrack.mergeCurrentTrack(nextIndex, message.track)
    val nextTrack = if (nextQueue.isEmpty()) {
        null
    } else {
        nextQueue.getOrNull(nextIndex)
            ?: message.track
            ?: currentState.currentTrack()
    }
    val nextPlaybackState = if (nextQueue.isEmpty()) {
        "paused"
    } else {
        when (committedEvent.type) {
            "PLAY" -> "playing"
            "PAUSE" -> "paused"
            else -> message.stateName
                ?: if (message.shouldPlay == true) "playing" else currentState.playback.state
        }
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
