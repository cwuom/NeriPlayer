package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.ltw.playback.mergeCurrentTrack
import moe.ouom.neriplayer.data.ltw.playback.currentTrack
import moe.ouom.neriplayer.data.ltw.playback.resolveListenTogetherQueueIndex
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope

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
    val queueWithoutCurrentTrack = resolveForwardedQueue(currentQueue, mutationResult, message, committedEvent.type)
    val nextIndex = resolveListenTogetherQueueIndex(
        queue = queueWithoutCurrentTrack,
        requestedIndex = resolveRequestedIndex(currentState.currentIndex, mutationResult, message, committedEvent.type),
        preferredStableKey = resolvePreferredStableKey(message, committedEvent)
    )
    val nextQueue = queueWithoutCurrentTrack.mergeCurrentTrack(nextIndex, message.track)
    return currentState.copy(
        queue = nextQueue,
        currentIndex = nextIndex,
        track = resolveForwardedTrack(nextQueue, nextIndex, message, currentState),
        playback = resolveForwardedPlayback(currentState.playback, nextQueue, message, committedEvent, nowMs)
    )
}

private fun resolveForwardedQueue(
    currentQueue: List<ListenTogetherTrack>,
    mutation: ListenTogetherQueueMutationResult?,
    message: ListenTogetherSocketEnvelope,
    eventType: String
): List<ListenTogetherTrack> {
    val legacySnapshot = message.queue?.takeIf { it.isNotEmpty() || eventType == "SET_QUEUE" }
    return mutation?.queue ?: legacySnapshot ?: currentQueue
}

private fun resolveRequestedIndex(
    currentIndex: Int,
    mutation: ListenTogetherQueueMutationResult?,
    message: ListenTogetherSocketEnvelope,
    eventType: String
): Int {
    val fallback = message.currentIndex ?: currentIndex
    val result = mutation ?: return fallback
    return if (eventType == "SET_TRACK") result.targetCurrentIndex ?: result.currentIndex else result.currentIndex
}

private fun resolvePreferredStableKey(message: ListenTogetherSocketEnvelope, event: ListenTogetherEvent): String? {
    val requestedKey = message.requestTrackStableKey ?: event.requestTrackStableKey
    if (event.type != "SET_TRACK") return requestedKey
    return requestedKey ?: selectedTrackKey(message, event)
}

private fun selectedTrackKey(message: ListenTogetherSocketEnvelope, event: ListenTogetherEvent): String? =
    message.track?.stableKey ?: event.track?.stableKey

private fun resolveForwardedTrack(
    queue: List<ListenTogetherTrack>,
    index: Int,
    message: ListenTogetherSocketEnvelope,
    currentState: ListenTogetherRoomState
): ListenTogetherTrack? {
    if (queue.isEmpty()) return null
    return queue.getOrNull(index) ?: message.track ?: currentState.currentTrack()
}

private fun resolveForwardedPlayback(
    current: ListenTogetherPlaybackState,
    queue: List<ListenTogetherTrack>,
    message: ListenTogetherSocketEnvelope,
    event: ListenTogetherEvent,
    nowMs: Long
): ListenTogetherPlaybackState = current.copy(
    state = resolvePlaybackState(current.state, queue.isEmpty(), message, event.type),
    basePositionMs = (event.positionMs ?: message.expectedPositionMs ?: 0L).coerceAtLeast(0L),
    baseTimestampMs = nowMs,
    repeatMode = event.repeatMode ?: current.repeatMode,
    shuffleEnabled = event.shuffleEnabled ?: current.shuffleEnabled
)

private fun resolvePlaybackState(
    currentState: String,
    emptyQueue: Boolean,
    message: ListenTogetherSocketEnvelope,
    eventType: String
): String {
    if (emptyQueue) return "paused"
    if (eventType == "PLAY") return "playing"
    if (eventType == "PAUSE") return "paused"
    return message.stateName ?: implicitPlaybackState(message.shouldPlay, currentState)
}

private fun implicitPlaybackState(shouldPlay: Boolean?, currentState: String): String =
    if (shouldPlay == true) "playing" else currentState
