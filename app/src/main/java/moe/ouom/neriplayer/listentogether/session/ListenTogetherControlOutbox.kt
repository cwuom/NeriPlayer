package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent

internal data class PendingListenTogetherControlEvent(
    val event: ListenTogetherEvent,
    val roomId: String,
    val legacyFallbackEvent: ListenTogetherEvent? = null
)

internal class ListenTogetherControlOutbox(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS
) {
    private val events = LinkedHashMap<String, PendingListenTogetherControlEvent>()

    init {
        require(maxEvents > 0)
    }

    fun offer(
        event: ListenTogetherEvent,
        roomId: String?,
        legacyFallbackEvent: ListenTogetherEvent? = null
    ) {
        val resolvedRoomId = roomId?.takeIf { it.isNotBlank() } ?: return
        val key = listenTogetherControlOutboxKey(resolvedRoomId, event) ?: return
        synchronized(events) {
            events.remove(key)
            events[key] = PendingListenTogetherControlEvent(
                event = event,
                roomId = resolvedRoomId,
                legacyFallbackEvent = legacyFallbackEvent
            )
            while (events.size > maxEvents) {
                events.entries.iterator().next().also { events.remove(it.key) }
            }
        }
    }

    fun pendingForRoom(roomId: String?): List<PendingListenTogetherControlEvent> {
        val resolvedRoomId = roomId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return synchronized(events) {
            events.values.filter { it.roomId == resolvedRoomId }
        }
    }

    fun acknowledge(eventId: String?) {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return
        synchronized(events) {
            events.entries.removeIf { (_, pending) -> pending.event.eventId == resolvedEventId }
        }
    }

    fun pendingByEventId(eventId: String?): PendingListenTogetherControlEvent? {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(events) {
            events.values.firstOrNull { pending -> pending.event.eventId == resolvedEventId }
        }
    }

    fun singlePendingWithLegacyFallback(): PendingListenTogetherControlEvent? {
        return synchronized(events) {
            events.values.filter { it.legacyFallbackEvent != null }.singleOrNull()
        }
    }

    fun replace(
        eventId: String?,
        replacement: ListenTogetherEvent
    ): PendingListenTogetherControlEvent? {
        val resolvedEventId = eventId?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(events) {
            val existingEntry = events.entries.firstOrNull { (_, pending) ->
                pending.event.eventId == resolvedEventId
            } ?: return@synchronized null
            val replaced = existingEntry.value
            val replacementKey = listenTogetherControlOutboxKey(
                roomId = replaced.roomId,
                event = replacement
            ) ?: return@synchronized null
            events.remove(existingEntry.key)
            events[replacementKey] = PendingListenTogetherControlEvent(
                event = replacement,
                roomId = replaced.roomId
            )
            replaced
        }
    }

    fun clear() {
        synchronized(events) {
            events.clear()
        }
    }

    private companion object {
        private const val DEFAULT_MAX_EVENTS = 8
    }
}

internal fun listenTogetherControlOutboxKey(
    roomId: String,
    event: ListenTogetherEvent
): String? {
    val intentKey = when (event.type) {
        "SET_TRACK", "REQUEST_SET_TRACK" -> "track"
        "SET_QUEUE", "REQUEST_SET_QUEUE" -> "queue"
        "PLAY", "REQUEST_PLAY", "PAUSE", "REQUEST_PAUSE" -> "transport"
        "SEEK", "REQUEST_SEEK" -> "seek"
        "PLAYBACK_MODE", "REQUEST_PLAYBACK_MODE" -> "playback_mode"
        "TRACK_FINISHED" -> "track_finished"
        else -> null
    }
    return intentKey?.let { "$roomId:$it" }
}
