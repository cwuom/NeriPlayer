package moe.ouom.neriplayer.listentogether.session

private const val DEFAULT_RECENT_EVENT_LIMIT = 128

internal class ListenTogetherRecentEventTracker(
    private val maxSize: Int = DEFAULT_RECENT_EVENT_LIMIT
) {
    private val outboundEventIds = LinkedHashSet<String>()
    private val inboundEventIds = LinkedHashSet<String>()
    private val lock = Any()

    fun clear() = synchronized(lock) {
        outboundEventIds.clear()
        inboundEventIds.clear()
    }

    fun hasOutbound(eventId: String): Boolean = synchronized(lock) {
        outboundEventIds.contains(eventId)
    }

    fun hasInbound(eventId: String): Boolean = synchronized(lock) {
        inboundEventIds.contains(eventId)
    }

    fun markOutbound(eventId: String?) {
        mark(outboundEventIds, eventId)
    }

    fun markInbound(eventId: String?) {
        mark(inboundEventIds, eventId)
    }

    private fun mark(events: LinkedHashSet<String>, eventId: String?) = synchronized(lock) {
        if (eventId.isNullOrBlank()) return@synchronized
        events.add(eventId)
        while (events.size > maxSize) {
            val oldest = events.firstOrNull() ?: break
            events.remove(oldest)
        }
    }
}
