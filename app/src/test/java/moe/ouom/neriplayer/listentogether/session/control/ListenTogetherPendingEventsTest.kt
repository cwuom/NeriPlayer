package moe.ouom.neriplayer.listentogether.session.control

import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherPendingEventsTest {
    private val pending = PendingMemberControlRequest(
        event = ListenTogetherEvent(type = "REQUEST_PLAY", eventId = "request-1"),
        createdAtElapsedMs = 1L,
        lastSentAtElapsedMs = 1L,
        attempts = 1
    )

    @Test
    fun `matching nonblank event acknowledges pending request`() {
        assertNull(pending.acknowledgedBy(ListenTogetherCause(eventId = "request-1")))
    }

    @Test
    fun `unrelated or absent event keeps pending request`() {
        assertEquals(pending, pending.acknowledgedBy(ListenTogetherCause(eventId = "other")))
        assertEquals(pending, pending.acknowledgedBy(ListenTogetherCause(eventId = "")))
        assertEquals(pending, pending.acknowledgedBy(null))
        assertNull((null as PendingMemberControlRequest?).acknowledgedBy(ListenTogetherCause(eventId = "request-1")))
    }
}
