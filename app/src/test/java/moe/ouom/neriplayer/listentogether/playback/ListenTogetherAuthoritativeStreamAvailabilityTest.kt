package moe.ouom.neriplayer.listentogether.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherAuthoritativeStreamAvailabilityTest {

    @Test
    fun `unavailable status is scoped to its room and track`() {
        val availability = ListenTogetherAuthoritativeStreamAvailability()

        assertFalse(
            availability.markUnavailable(
                roomId = "room-a",
                stableKey = "netease:1",
                signalId = "unavailable-1"
            )
        )

        assertTrue(
            availability.isAwaitingConfirmation(
                roomId = "room-a",
                stableKey = "netease:1"
            )
        )
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:1"))
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:2"))
        assertFalse(availability.isUnavailable(roomId = "room-b", stableKey = "netease:1"))
    }

    @Test
    fun `new authoritative link or track change clears unavailable status`() {
        val availability = ListenTogetherAuthoritativeStreamAvailability()
        availability.markUnavailable(
            roomId = "room-a",
            stableKey = "netease:1",
            signalId = "unavailable-1"
        )
        availability.markUnavailable(
            roomId = "room-a",
            stableKey = "netease:1",
            signalId = "unavailable-2"
        )

        availability.reconcile(
            roomId = "room-a",
            stableKey = "netease:1",
            hasAuthoritativeStream = true
        )
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:1"))

        availability.markUnavailable(
            roomId = "room-a",
            stableKey = "netease:1",
            signalId = "unavailable-3"
        )
        availability.reconcile(
            roomId = "room-a",
            stableKey = "netease:2",
            hasAuthoritativeStream = false
        )
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:1"))
    }

    @Test
    fun `duplicate unavailable signal does not confirm the link is unavailable`() {
        val availability = ListenTogetherAuthoritativeStreamAvailability()

        availability.markUnavailable("room-a", "netease:1", signalId = "same-event")
        availability.markUnavailable("room-a", "netease:1", signalId = "same-event")

        assertFalse(availability.isUnavailable("room-a", "netease:1"))
        assertTrue(availability.isAwaitingConfirmation("room-a", "netease:1"))
    }

    @Test
    fun `two independent unavailable signals confirm the target`() {
        val availability = ListenTogetherAuthoritativeStreamAvailability()

        availability.markUnavailable("room-a", "netease:1", signalId = "first")
        assertTrue(
            availability.markUnavailable(
                "room-a",
                "netease:1",
                signalId = "second"
            )
        )

        assertTrue(availability.isUnavailable("room-a", "netease:1"))
        assertFalse(availability.isAwaitingConfirmation("room-a", "netease:1"))
    }
}
