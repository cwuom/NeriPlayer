package moe.ouom.neriplayer.data.ltw.session.control

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherControlIsolationTest {
    @Test
    fun `outbox queries cannot leak intents across rooms or absent identities`() {
        val outbox = ListenTogetherControlOutbox()
        outbox.offer(ListenTogetherEvent(type = "PLAY", eventId = "event"), "room")
        assertEquals(1, outbox.pendingForRoom("room").size)
        for (room in listOf(null, "", " ", "other")) assertTrue(outbox.pendingForRoom(room).isEmpty())
    }

    @Test
    fun `sequence dedupe supports missing legacy requester without mixing named members`() {
        val deduper = ListenTogetherForwardedRequestDeduper(maxRequesters = 2)
        assertTrue(deduper.shouldProcess(null, 1L, null))
        assertFalse(deduper.shouldProcess(" ", 1L, null))
        assertTrue(deduper.shouldProcess("other", 1L, null))
        assertTrue(deduper.shouldProcess(null, 2L, null))
        assertTrue(deduper.shouldProcess("third", 1L, null))
        assertTrue(deduper.shouldProcess("other", 1L, null))
    }

    @Test
    fun `controller echo filtering keeps other members distant versions and track finish`() {
        val state = ListenTogetherRoomState(roomId = "room", version = 2L, controllerUserId = "self")
        fun drops(candidate: ListenTogetherRoomState = state, cause: ListenTogetherCause? = ListenTogetherCause(type = "PLAY", userUuid = "self"), user: String? = "self") =
            shouldDropListenTogetherControllerLocalEcho(candidate, cause, 1L, user, 1_000L, 1_100L, 1_200L)
        assertTrue(drops())
        assertFalse(drops(user = "other"))
        assertFalse(drops(cause = ListenTogetherCause(type = "PLAY", userUuid = "other")))
        assertFalse(drops(cause = null))
        assertFalse(drops(candidate = state.copy(version = 5L)))
        assertFalse(drops(cause = ListenTogetherCause(type = "TRACK_FINISHED", userUuid = "self")))
    }
}
