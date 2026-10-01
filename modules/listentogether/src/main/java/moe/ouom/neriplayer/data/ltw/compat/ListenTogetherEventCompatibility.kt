package moe.ouom.neriplayer.data.ltw.compat

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.ltw.session.normalizedListenTogetherIdentity
import java.util.Locale

fun buildListenTogetherLegacyQueueMutationFallback(
    event: ListenTogetherEvent,
    fallbackEventId: String
): ListenTogetherEvent? {
    val snapshot = event.legacyQueueSnapshot ?: return null
    if (event.queueMutation == null || fallbackEventId.isBlank()) return null
    return event.copy(
        eventId = fallbackEventId,
        queue = snapshot,
        queueMutation = null,
        legacyQueueSnapshot = null
    )
}

fun isListenTogetherQueueMutationCompatibilityError(
    errorMessage: String?
): Boolean {
    val normalized = errorMessage?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return normalized.contains("queue mutation is invalid") ||
        normalized.contains("queue mutation base version is ahead") ||
        normalized.contains("queue mutation event type unsupported") ||
        normalized.contains("queue update queue required")
}

fun resolveListenTogetherPlaybackCommandShouldPlay(
    commandType: String,
    commandShouldPlay: Boolean?,
    localTransportActive: Boolean,
    localPlaying: Boolean
): Boolean {
    commandShouldPlay?.let { return it }
    return if (commandType in TRANSPORT_PLAYBACK_COMMAND_TYPES) {
        localTransportActive || localPlaying
    } else {
        localPlaying
    }
}

fun resolveListenTogetherLinkReadyState(
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

fun isListenTogetherMemberControlTargetCurrent(
    eventType: String,
    requestedStableKey: String?,
    currentStableKey: String?
): Boolean {
    if (eventType !in TRACK_BOUND_MEMBER_CONTROL_TYPES) return true
    val requested = requestedStableKey.normalizedListenTogetherIdentity() ?: return false
    val current = currentStableKey.normalizedListenTogetherIdentity() ?: return false
    return requested == current
}

fun shouldSuppressListenerControlWhileAwaitingStream(
    eventType: String,
    awaitingAuthoritativeStream: Boolean,
    localTrackHasDirectStream: Boolean
): Boolean {
    if (eventType !in STREAM_DEPENDENT_MEMBER_CONTROL_TYPES) return false
    if (!awaitingAuthoritativeStream) return false
    return !localTrackHasDirectStream
}

private val TRANSPORT_PLAYBACK_COMMAND_TYPES = setOf(
    "PLAY_PLAYLIST", "PLAY_FROM_QUEUE", "NEXT", "PREVIOUS", "SEEK", "HEARTBEAT", "LINK_READY"
)

private val TRACK_BOUND_MEMBER_CONTROL_TYPES = setOf(
    "REQUEST_PLAY",
    "REQUEST_PAUSE",
    "REQUEST_SEEK"
)

private val STREAM_DEPENDENT_MEMBER_CONTROL_TYPES = setOf(
    "REQUEST_PAUSE",
    "REQUEST_SEEK"
)

fun isUnsupportedTrackFinishedEventError(errorMessage: String?): Boolean {
    val normalized = errorMessage
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if ("track_finished" !in normalized) return false
    return "unsupported event type" in normalized ||
        "unsuppported event type" in normalized
}

fun buildTrackFinishedLegacyFallbackEvent(
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
