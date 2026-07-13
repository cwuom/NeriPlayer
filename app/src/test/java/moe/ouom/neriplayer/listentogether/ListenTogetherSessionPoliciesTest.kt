package moe.ouom.neriplayer.listentogether

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.listentogether.session.ListenTogetherRecentEventTracker
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherRoomNotice
import moe.ouom.neriplayer.listentogether.session.resolveListenTogetherSessionRole
import moe.ouom.neriplayer.listentogether.session.shouldDropListenTogetherControllerLocalEcho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherSessionPoliciesTest {

    @Test
    fun `session role follows room controller identity when available`() {
        val state = roomState(
            controllerUserUuid = "controller-id"
        )

        assertEquals(
            "controller",
            resolveListenTogetherSessionRole(
                sessionUserId = " controller-id ",
                fallbackRole = "listener",
                state = state
            )
        )
        assertEquals(
            "listener",
            resolveListenTogetherSessionRole(
                sessionUserId = "member-id",
                fallbackRole = "controller",
                state = state
            )
        )
    }

    @Test
    fun `session role falls back before room controller identity is known`() {
        assertEquals(
            "listener",
            resolveListenTogetherSessionRole(
                sessionUserId = "member-id",
                fallbackRole = "listener",
                state = roomState()
            )
        )
        assertNull(
            resolveListenTogetherSessionRole(
                sessionUserId = "member-id",
                fallbackRole = null,
                state = roomState()
            )
        )
    }

    @Test
    fun `recent event tracker trims oldest event ids`() {
        val tracker = ListenTogetherRecentEventTracker(maxSize = 2)

        tracker.markOutbound("first")
        tracker.markOutbound("second")
        tracker.markOutbound("third")

        assertFalse(tracker.hasOutbound("first"))
        assertTrue(tracker.hasOutbound("second"))
        assertTrue(tracker.hasOutbound("third"))
    }

    @Test
    fun `recent event tracker ignores blank ids and clears both directions`() {
        val tracker = ListenTogetherRecentEventTracker(maxSize = 2)

        tracker.markOutbound("")
        tracker.markInbound("inbound")
        tracker.markOutbound("outbound")
        tracker.clear()

        assertFalse(tracker.hasOutbound(""))
        assertFalse(tracker.hasOutbound("outbound"))
        assertFalse(tracker.hasInbound("inbound"))
    }

    @Test
    fun `controller local echo drops near self caused state`() {
        val state = roomState(
            version = 4L,
            controllerUserUuid = "controller-id"
        )

        assertTrue(
            shouldDropListenTogetherControllerLocalEcho(
                state = state,
                cause = ListenTogetherCause(
                    userUuid = "controller-id",
                    type = "PLAY",
                    eventId = "event-1"
                ),
                latestVersion = 3L,
                currentUserId = "controller-id",
                lastControllerLocalControlAtElapsedMs = 1_000L,
                nowElapsedMs = 1_800L,
                controllerLocalControlCooldownMs = 1_200L
            )
        )
    }

    @Test
    fun `controller local echo keeps track finished and stale distant events`() {
        val state = roomState(
            version = 4L,
            controllerUserUuid = "controller-id"
        )

        assertFalse(
            shouldDropListenTogetherControllerLocalEcho(
                state = state,
                cause = ListenTogetherCause(userUuid = "controller-id", type = "TRACK_FINISHED"),
                latestVersion = 3L,
                currentUserId = "controller-id",
                lastControllerLocalControlAtElapsedMs = 1_000L,
                nowElapsedMs = 1_800L,
                controllerLocalControlCooldownMs = 1_200L
            )
        )
        assertFalse(
            shouldDropListenTogetherControllerLocalEcho(
                state = state,
                cause = ListenTogetherCause(userUuid = "controller-id", type = "PLAY"),
                latestVersion = 3L,
                currentUserId = "controller-id",
                lastControllerLocalControlAtElapsedMs = 1_000L,
                nowElapsedMs = 3_000L,
                controllerLocalControlCooldownMs = 1_200L
            )
        )
    }

    @Test
    fun `room notice resolves controller offline countdown`() {
        val state = roomState(
            roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE,
            controllerOfflineSince = 10_000L
        )

        assertEquals(
            "controller_offline:9",
            resolveListenTogetherRoomNotice(
                state = state,
                nowMs = 12_000L,
                controllerGracePeriodMs = 9 * 60 * 1_000L
            )
        )
    }

    @Test
    fun `room notice keeps fallback and closed reason precedence`() {
        assertEquals(
            "fallback",
            resolveListenTogetherRoomNotice(
                state = roomState(roomStatus = ListenTogetherRoomStatuses.ACTIVE),
                fallbackMessage = "fallback"
            )
        )
        assertEquals(
            "room expired",
            resolveListenTogetherRoomNotice(
                state = roomState(
                    roomStatus = ListenTogetherRoomStatuses.CLOSED,
                    closedReason = "room expired"
                )
            )
        )
    }

    private fun roomState(
        version: Long = 1L,
        controllerUserUuid: String? = null,
        roomStatus: String = ListenTogetherRoomStatuses.ACTIVE,
        controllerOfflineSince: Long? = null,
        closedReason: String? = null
    ): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = "ABC234",
            version = version,
            controllerUserUuid = controllerUserUuid,
            roomStatus = roomStatus,
            controllerOfflineSince = controllerOfflineSince,
            closedReason = closedReason
        )
    }
}
