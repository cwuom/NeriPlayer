package moe.ouom.neriplayer.listentogether.compat

import moe.ouom.neriplayer.listentogether.playback.isListenTogetherSeekControlSatisfied
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import java.util.Locale

internal fun resolveListenTogetherPlaybackCommandShouldPlay(
    commandType: String,
    commandShouldPlay: Boolean?,
    localTransportActive: Boolean,
    localPlaying: Boolean
): Boolean {
    commandShouldPlay?.let { return it }
    return when (commandType) {
        "PLAY_PLAYLIST",
        "PLAY_FROM_QUEUE",
        "NEXT",
        "PREVIOUS",
        "SEEK",
        "HEARTBEAT",
        "LINK_READY" -> localTransportActive || localPlaying
        else -> localPlaying
    }
}

internal fun resolveListenTogetherLinkReadyState(
    roomPlaybackState: String?,
    localTransportActive: Boolean,
    localPlaying: Boolean
): String {
    val normalizedRoomState = roomPlaybackState
        ?.trim()
        ?.lowercase(Locale.ROOT)
    return if (
        normalizedRoomState == "playing" ||
        localTransportActive ||
        localPlaying
    ) {
        "playing"
    } else {
        "paused"
    }
}

internal fun isListenTogetherMemberControlTargetCurrent(
    eventType: String,
    requestedStableKey: String?,
    currentStableKey: String?
): Boolean {
    if (eventType !in TRACK_BOUND_MEMBER_CONTROL_TYPES) return true
    val requested = requestedStableKey
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return false
    val current = currentStableKey
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return false
    return requested == current
}

internal fun shouldSuppressListenerControlWhileAwaitingStream(
    eventType: String,
    awaitingAuthoritativeStream: Boolean,
    localTrackHasDirectStream: Boolean
): Boolean {
    if (eventType !in STREAM_DEPENDENT_MEMBER_CONTROL_TYPES) return false
    if (!awaitingAuthoritativeStream) return false
    return !localTrackHasDirectStream
}

internal fun isListenTogetherPendingMemberControlSatisfied(
    event: ListenTogetherEvent,
    state: ListenTogetherRoomState?,
    seekSatisfiedDriftMs: Long = 1_500L
): Boolean {
    state ?: return false
    val requestedType = event.type.removePrefix("REQUEST_")
    return when (requestedType) {
        "PLAY" -> state.playback.state == "playing"
        "PAUSE" -> state.playback.state == "paused"
        "SEEK" -> {
            val requestedPositionMs = event.positionMs ?: return false
            isListenTogetherSeekControlSatisfied(
                playback = state.playback,
                requestedPositionMs = requestedPositionMs,
                satisfiedDriftMs = seekSatisfiedDriftMs
            )
        }
        "PLAYBACK_MODE" -> {
            val requestedRepeatMode = event.repeatMode
            val requestedShuffleEnabled = event.shuffleEnabled
            (requestedRepeatMode == null || state.playback.repeatMode == requestedRepeatMode) &&
                (requestedShuffleEnabled == null || state.playback.shuffleEnabled == requestedShuffleEnabled)
        }
        "SET_TRACK" -> {
            val requestedStableKey = event.track?.stableKey
                ?: event.queue?.getOrNull(event.currentIndex ?: -1)?.stableKey
                ?: return false
            state.currentStableKeyForCompatibility() == requestedStableKey
        }
        else -> false
    }
}

private val TRACK_BOUND_MEMBER_CONTROL_TYPES = setOf(
    "REQUEST_PLAY",
    "REQUEST_PAUSE",
    "REQUEST_SEEK"
)

private val STREAM_DEPENDENT_MEMBER_CONTROL_TYPES = setOf(
    "REQUEST_PAUSE",
    "REQUEST_SEEK"
)

private fun ListenTogetherRoomState.currentStableKeyForCompatibility(): String? {
    return track?.stableKey ?: queue.getOrNull(currentIndex)?.stableKey
}

internal fun isUnsupportedTrackFinishedEventError(errorMessage: String?): Boolean {
    val normalized = errorMessage
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if ("track_finished" !in normalized) return false
    return "unsupported event type" in normalized ||
        "unsuppported event type" in normalized
}

internal fun buildTrackFinishedLegacyFallbackEvent(
    event: ListenTogetherEvent,
    isController: Boolean,
    nowMs: Long,
    eventIdFactory: () -> String
): ListenTogetherEvent? {
    if (!isController || event.type != "TRACK_FINISHED") return null
    val nextIndex = event.nextIndex ?: event.currentIndex
    if (event.shouldPlay == true && nextIndex != null) {
        return event.copy(
            type = "SET_TRACK",
            eventId = eventIdFactory(),
            clientTimeMs = nowMs,
            positionMs = 0L,
            currentIndex = nextIndex,
            nextIndex = null,
            track = event.track ?: event.queue?.getOrNull(nextIndex),
            shouldPlay = true,
            state = "playing",
            finishedTrackStableKey = null
        )
    }
    return event.copy(
        type = "PAUSE",
        eventId = eventIdFactory(),
        clientTimeMs = nowMs,
        nextIndex = null,
        shouldPlay = false,
        state = "paused",
        finishedTrackStableKey = null
    )
}
