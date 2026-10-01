package moe.ouom.neriplayer.core.player.policy.command

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRoomControlRestrictionTest {
    @Test
    fun `controller access takes precedence and listeners respect room restrictions`() {
        assertEquals(LocalRoomControlRestriction.NONE, resolveLocalRoomControlRestriction("controller_offline", false, true))
        assertEquals(LocalRoomControlRestriction.CONTROLLER_OFFLINE, resolveLocalRoomControlRestriction("controller_offline", true, false))
        assertEquals(LocalRoomControlRestriction.MEMBER_CONTROL_DISABLED, resolveLocalRoomControlRestriction(null, false, false))
        assertEquals(LocalRoomControlRestriction.NONE, resolveLocalRoomControlRestriction(null, null, false))
    }
}
