package moe.ouom.neriplayer.listentogether.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherAuthoritativeStreamAvailabilityTest {

    @Test
    fun `unavailable status is scoped to its room and track`() {
        val availability = ListenTogetherAuthoritativeStreamAvailability()

        availability.markUnavailable(roomId = "room-a", stableKey = "netease:1")

        assertTrue(availability.isUnavailable(roomId = "room-a", stableKey = "netease:1"))
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:2"))
        assertFalse(availability.isUnavailable(roomId = "room-b", stableKey = "netease:1"))
    }

    @Test
    fun `new authoritative link or track change clears unavailable status`() {
        val availability = ListenTogetherAuthoritativeStreamAvailability()
        availability.markUnavailable(roomId = "room-a", stableKey = "netease:1")

        availability.reconcile(
            roomId = "room-a",
            stableKey = "netease:1",
            hasAuthoritativeStream = true
        )
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:1"))

        availability.markUnavailable(roomId = "room-a", stableKey = "netease:1")
        availability.reconcile(
            roomId = "room-a",
            stableKey = "netease:2",
            hasAuthoritativeStream = false
        )
        assertFalse(availability.isUnavailable(roomId = "room-a", stableKey = "netease:1"))
    }
}
