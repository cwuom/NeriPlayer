package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.session.ListenTogetherControlOutbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherControlOutboxTest {

    @Test
    fun `latest track intent replaces an older disconnected track intent`() {
        val outbox = ListenTogetherControlOutbox()

        outbox.offer(event("SET_TRACK", "track-old"), "ROOM01")
        outbox.offer(event("SET_TRACK", "track-new"), "ROOM01")

        assertEquals(
            listOf("track-new"),
            outbox.pendingForRoom("ROOM01").map { it.event.eventId }
        )
    }

    @Test
    fun `transport seek and queue intents retain independent latest values`() {
        val outbox = ListenTogetherControlOutbox()

        outbox.offer(event("PLAY", "play"), "ROOM01")
        outbox.offer(event("SEEK", "seek-old"), "ROOM01")
        outbox.offer(event("SET_QUEUE", "queue"), "ROOM01")
        outbox.offer(event("REQUEST_SEEK", "seek-new"), "ROOM01")

        assertEquals(
            listOf("play", "queue", "seek-new"),
            outbox.pendingForRoom("ROOM01").map { it.event.eventId }
        )
    }

    @Test
    fun `outbox is room scoped and ignores blank rooms or unknown events`() {
        val outbox = ListenTogetherControlOutbox()

        outbox.offer(event("PLAY", "one"), "ROOM01")
        outbox.offer(event("PAUSE", "two"), "ROOM02")
        outbox.offer(event("PLAY", "ignored-room"), " ")
        outbox.offer(event("REQUEST_LINK", "ignored-type"), "ROOM01")

        assertEquals(listOf("one"), outbox.pendingForRoom("ROOM01").map { it.event.eventId })
        assertEquals(listOf("two"), outbox.pendingForRoom("ROOM02").map { it.event.eventId })
    }

    @Test
    fun `acknowledgement removes only its matching replayable event`() {
        val outbox = ListenTogetherControlOutbox()

        outbox.offer(event("PLAY", "play"), "ROOM01")
        outbox.offer(event("SEEK", "seek"), "ROOM01")
        outbox.acknowledge("play")

        assertEquals(listOf("seek"), outbox.pendingForRoom("ROOM01").map { it.event.eventId })
        outbox.acknowledge("missing")
        assertEquals(listOf("seek"), outbox.pendingForRoom("ROOM01").map { it.event.eventId })
    }

    @Test
    fun `outbox eviction is bounded and clear removes every pending event`() {
        val outbox = ListenTogetherControlOutbox(maxEvents = 2)

        outbox.offer(event("PLAY", "play"), "ROOM01")
        outbox.offer(event("SEEK", "seek"), "ROOM01")
        outbox.offer(event("SET_QUEUE", "queue"), "ROOM01")

        assertEquals(
            listOf("seek", "queue"),
            outbox.pendingForRoom("ROOM01").map { it.event.eventId }
        )
        outbox.clear()
        assertTrue(outbox.pendingForRoom("ROOM01").isEmpty())
        assertFalse(outbox.pendingForRoom("ROOM01").isNotEmpty())
    }

    @Test
    fun `legacy fallback atomically replaces only the rejected queue intent`() {
        val outbox = ListenTogetherControlOutbox()
        val original = event("SET_QUEUE", "mutation")
        val fallback = event("SET_QUEUE", "legacy")

        outbox.offer(event("PLAY", "play"), "ROOM01")
        outbox.offer(
            event = original,
            roomId = "ROOM01",
            legacyFallbackEvent = fallback
        )

        assertEquals(original, outbox.replace("mutation", fallback)?.event)
        assertEquals(
            listOf("play", "legacy"),
            outbox.pendingForRoom("ROOM01").map { it.event.eventId }
        )
        assertNull(outbox.pendingByEventId("mutation"))
        assertNull(outbox.singlePendingWithLegacyFallback())
    }

    @Test
    fun `legacy fallback replacement rejects missing ids without disturbing pending intent`() {
        val outbox = ListenTogetherControlOutbox()
        outbox.offer(event("SET_QUEUE", "mutation"), "ROOM01")

        assertNull(outbox.replace(" ", event("SET_QUEUE", "legacy")))
        assertEquals(
            listOf("mutation"),
            outbox.pendingForRoom("ROOM01").map { it.event.eventId }
        )
    }

    private fun event(type: String, eventId: String) = ListenTogetherEvent(
        type = type,
        eventId = eventId
    )
}
