package moe.ouom.neriplayer.listentogether

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
