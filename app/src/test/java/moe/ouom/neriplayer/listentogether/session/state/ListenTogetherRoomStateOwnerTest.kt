package moe.ouom.neriplayer.listentogether.session.state

import moe.ouom.neriplayer.data.model.ltw.session.RoomStateSource

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherRoomStateOwnerTest {
    private val committed = mutableListOf<ListenTogetherRoomState>()
    private val supplements = mutableListOf<Long>()
    private var roomActivations = 0
    private val owner = ListenTogetherRoomStateOwner(
        observer = object : ListenTogetherRoomStateObserver {
            override fun onRoomActivated() {
                roomActivations++
            }

            override fun onCommitted(
                state: ListenTogetherRoomState,
                expectedPositionMs: Long?,
                source: RoomStateSource
            ) {
                committed += state
            }

            override fun onPositionSupplement(expectedPositionMs: Long) {
                supplements += expectedPositionMs
            }

            override fun onSocketMessageAccepted() = Unit
        },
        elapsedRealtimeMs = { 1_000L }
    )

    @Test
    fun `accepts active room and rejects stale or foreign states`() {
        assertNull(owner.accept(room("room", 1L), null, RoomStateSource.HTTP_REFRESH))
        assertNull(owner.activateIfPresent(null))
        assertNull(owner.activateIfPresent(""))
        assertTrue(owner.activate("room"))
        assertEquals(false, owner.activateIfPresent("room"))
        assertEquals(1, roomActivations)
        assertEquals(1L, owner.accept(room("room", 1L), null, RoomStateSource.HTTP_REFRESH)?.state?.version)
        assertNull(owner.accept(room("other", 2L), null, RoomStateSource.HTTP_REFRESH))
        assertNull(owner.accept(room("room", 0L), null, RoomStateSource.HTTP_REFRESH))
        assertEquals(listOf(1L), committed.map { it.version })
        assertEquals(1L, owner.roomState.value?.version)
    }

    @Test
    fun `same version keeps structure and applies position supplement`() {
        owner.activate("room")
        val first = room("room", 3L)
        owner.accept(first, null, RoomStateSource.HTTP_REFRESH)
        val differentPayload = first.copy(roomStatus = "closed")

        val accepted = owner.accept(differentPayload, 700L, RoomStateSource.WEB_SOCKET_STATE)

        assertEquals(first, accepted?.state)
        assertEquals(first, owner.roomState.value)
        assertEquals(listOf(700L), supplements)
        assertEquals(1, committed.size)
    }

    @Test
    fun `version gap is repaired by HTTP state and a new room resets history`() {
        owner.activate("room")
        owner.accept(room("room", 2L), null, RoomStateSource.WEB_SOCKET_STATE)
        assertFalse(owner.recordSocketMessage(ListenTogetherSocketEnvelope(type = "room_state_updated", roomId = "other", version = 5L)))
        assertFalse(owner.recordSocketMessage(ListenTogetherSocketEnvelope(
            type = "room_state_updated", state = room("other", 5L)
        )))
        assertTrue(owner.recordSocketMessage(ListenTogetherSocketEnvelope(type = "welcome", roomId = "")))
        assertTrue(owner.recordSocketMessage(ListenTogetherSocketEnvelope(type = "welcome")))
        assertTrue(owner.recordSocketMessage(ListenTogetherSocketEnvelope(type = "room_state_updated", roomId = "room", version = 3L)))
        assertEquals(-1L, owner.pendingRepairVersion())
        assertTrue(owner.recordSocketMessage(ListenTogetherSocketEnvelope(type = "room_state_updated", roomId = "room", version = 5L)))
        assertEquals(5L, owner.pendingRepairVersion())

        owner.accept(room("room", 5L), null, RoomStateSource.HTTP_REFRESH)
        assertEquals(-1L, owner.pendingRepairVersion())
        assertEquals(5L, owner.lastAppliedVersion())

        assertTrue(owner.activate("new-room"))
        assertEquals(2, roomActivations)
        assertNull(owner.roomState.value)
        assertEquals(-1L, owner.lastAppliedVersion())
        assertEquals(1L, owner.accept(room("new-room", 1L), null, RoomStateSource.HTTP_REFRESH)?.state?.version)
    }

    @Test
    fun `disconnect resets version gate while close clears membership`() {
        owner.activate("room")
        owner.accept(room("room", 4L), null, RoomStateSource.HTTP_REFRESH)
        owner.resetVersions()
        assertEquals(-1L, owner.lastAppliedVersion())
        assertEquals(4L, owner.roomState.value?.version)
        assertTrue(owner.hasActiveRoom())

        owner.close()
        assertNull(owner.roomState.value)
        assertFalse(owner.hasActiveRoom())
        assertFalse(owner.recordSocketMessage(ListenTogetherSocketEnvelope(type = "welcome", roomId = "room")))
    }

    @Test
    fun `synthetic commit publishes the new state atomically`() {
        owner.activate("room")
        owner.accept(room("room", 1L), null, RoomStateSource.HTTP_REFRESH)

        val next = owner.commitSynthetic(200L) { it.copy(version = 2L) }

        assertEquals(2L, next?.version)
        assertEquals(2L, owner.roomState.value?.version)
        assertEquals(listOf(1L, 2L), committed.map { it.version })
        assertEquals(2L, owner.lastAppliedVersion())
    }

    @Test
    fun `forwarded control commits state with cause and playback position`() {
        val message = ListenTogetherSocketEnvelope(
            type = "member_control_requested",
            causedBy = ListenTogetherCause(type = "REQUEST_PLAY"),
            expectedPositionMs = 100L
        )
        val event = ListenTogetherEvent(type = "PLAY", positionMs = 200L)
        assertNull(owner.commitForwarded(message, event))
        owner.activate("room")
        owner.accept(room("room", 1L), null, RoomStateSource.HTTP_REFRESH)

        val committed = owner.commitForwarded(message, event)

        assertEquals("REQUEST_PLAY", committed?.causeType)
        assertEquals(200L, committed?.expectedPositionMs)
        assertEquals(committed?.state, owner.roomState.value)
        assertEquals(2, this.committed.size)

        val fallback = owner.commitForwarded(
            message.copy(causedBy = null),
            ListenTogetherEvent(type = "PAUSE")
        )
        assertEquals("PAUSE", fallback?.causeType)
        assertEquals(100L, fallback?.expectedPositionMs)
    }

    @Test
    fun `recent controller echo is rejected but authoritative queue update is accepted`() {
        owner.activate("room")
        owner.accept(room("room", 1L), null, RoomStateSource.HTTP_REFRESH)
        val echo = ListenTogetherCause(userUuid = "host", type = "PLAY", eventId = "local")

        assertNull(owner.accept(
            room("room", 2L), null, RoomStateSource.WEB_SOCKET_STATE,
            cause = echo, currentUserId = "host", lastControllerLocalControlAtElapsedMs = 900L,
            controllerLocalControlCooldownMs = 500L
        ))
        assertEquals(1L, owner.roomState.value?.version)

        val queueUpdate = ListenTogetherCause(userUuid = "host", type = "SET_QUEUE", eventId = "queue")
        val nextTrack = ListenTogetherTrack(
            stableKey = "netease:track", channelId = "netease", audioId = "track",
            name = "Track", artist = "Artist"
        )
        val accepted = owner.accept(
            room("room", 2L).copy(queue = listOf(nextTrack), track = nextTrack),
            null, RoomStateSource.WEB_SOCKET_STATE,
            cause = queueUpdate, currentUserId = "host", lastControllerLocalControlAtElapsedMs = 900L,
            controllerLocalControlCooldownMs = 500L
        )
        assertEquals(2L, accepted?.state?.version)
    }

    private fun room(roomId: String, version: Long) = ListenTogetherRoomState(
        roomId = roomId,
        version = version,
        controllerUserUuid = "host"
    )
}
