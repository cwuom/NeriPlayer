package moe.ouom.neriplayer.ui.component.playback.lan

import moe.ouom.neriplayer.data.model.lan.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LanPlaybackControlsTest {
    private val all = LanPermissions(true, true, true)
    private val member = LanSessionState(phase = "CONNECTED", synced = true, room = LanRoom("r", "Room"))
    @Test fun idleAndHostKeepControlsWhileMembersFollowEachPermission() {
        assertEquals(all, lanPlaybackControls(LanSessionState()))
        assertEquals(all, lanPlaybackControls(member.copy(hosting = true)))
        assertEquals(LanPermissions(), lanPlaybackControls(member))
        for (permissions in listOf(LanPermissions(next = true), LanPermissions(playPause = true), LanPermissions(seek = true), all)) {
            val granted = member.copy(room = member.room!!.copy(permissions = permissions))
            assertEquals(permissions, lanPlaybackControls(granted))
            assertEquals(LanPermissions(), lanPlaybackControls(granted.copy(room = member.room)))
        }
    }
    @Test fun stalePermissionsCannotEnableControlsDuringConnectionLoss() {
        val granted = member.copy(room = member.room!!.copy(permissions = all))
        assertEquals(LanPermissions(), lanPlaybackControls(granted.copy(phase = "RECONNECTING", synced = false)))
        assertEquals(LanPermissions(), lanPlaybackControls(granted.copy(room = null)))
    }
}
