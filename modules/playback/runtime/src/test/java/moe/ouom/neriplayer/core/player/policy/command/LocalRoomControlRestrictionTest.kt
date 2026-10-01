package moe.ouom.neriplayer.core.player.policy.command

import moe.ouom.neriplayer.core.player.presentation.command.errorResId

import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalRoomControlRestrictionTest {
    @Test
    fun `controller can control an offline or restricted room`() {
        assertEquals(LocalRoomControlRestriction.NONE,
            resolveLocalRoomControlRestriction("controller_offline", false, true))
    }

    @Test
    fun `member offline status precedes member control setting`() {
        assertEquals(LocalRoomControlRestriction.CONTROLLER_OFFLINE,
            resolveLocalRoomControlRestriction("controller_offline", false, false))
        assertEquals(LocalRoomControlRestriction.MEMBER_CONTROL_DISABLED,
            resolveLocalRoomControlRestriction("active", false, false))
        assertEquals(LocalRoomControlRestriction.NONE,
            resolveLocalRoomControlRestriction("active", true, false))
        assertEquals(LocalRoomControlRestriction.NONE,
            resolveLocalRoomControlRestriction(null, null, false))
    }

    @Test
    fun `room restrictions retain their error and debug reasons`() {
        assertEquals(null, LocalRoomControlRestriction.NONE.errorResId)
        assertEquals(CoreCommonR.string.listen_together_error_controller_offline,
            LocalRoomControlRestriction.CONTROLLER_OFFLINE.errorResId)
        assertEquals("local_control_blocked:controller_offline",
            LocalRoomControlRestriction.CONTROLLER_OFFLINE.debugReason)
        assertEquals(CoreCommonR.string.listen_together_error_member_control_disabled,
            LocalRoomControlRestriction.MEMBER_CONTROL_DISABLED.errorResId)
    }

    @Test
    fun `non local playback commands do not enter local USB or room gates`() {
        assertFalse(PlayerManager.shouldBlockLocalRoomControl(PlaybackCommandSource.REMOTE_SYNC))
        assertFalse(PlayerManager.shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL_SAFETY))
    }
}
