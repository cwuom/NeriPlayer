package moe.ouom.neriplayer.ui.component.playback.lan

import moe.ouom.neriplayer.data.model.lan.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LanPlaybackControlsTest {
    private val all = LanPermissions()
    private val none = LanPermissions(playPause = false, seek = false)
    private val member = LanSessionState(phase = "CONNECTED", synced = true, room = LanRoom("r", "Room"))
    @Test fun idleAndHostKeepControlsWhileMembersFollowEachPermission() {
        assertEquals(all, lanPlaybackControls(LanSessionState()))
        assertEquals(all, lanPlaybackControls(member.copy(hosting = true)))
        assertEquals(LanPermissions(), lanPlaybackControls(member))
        for (permissions in listOf(none, none.copy(playPause = true), none.copy(seek = true), none.copy(volume = true), all)) {
            val granted = member.copy(room = member.room!!.copy(permissions = permissions))
            assertEquals(permissions, lanPlaybackControls(granted))
            assertEquals(LanPermissions(), lanPlaybackControls(granted.copy(room = member.room)))
        }
    }
    @Test fun stalePermissionsCannotEnableControlsDuringConnectionLoss() {
        val granted = member.copy(room = member.room!!.copy(permissions = all))
        assertEquals(none, lanPlaybackControls(granted.copy(phase = "RECONNECTING", synced = false)))
        assertEquals(none, lanPlaybackControls(granted.copy(room = null)))
    }

    @Test fun failedStreamCanBeRetriedEvenIfRoomStillReportsPlaying() {
        val playing = member.copy(room = member.room!!.copy(playback = LanPlayback(playing = true)))
        assertEquals("PAUSE", lanRoomPlayPauseIntent(playing))
        assertEquals("PLAY_PAUSE", lanRoomPlayPauseIntent(playing.copy(audioSuspended = true)))
        assertEquals("PLAY_PAUSE", lanRoomPlayPauseIntent(playing.copy(error = "SOURCE")))
    }

    @Test fun bufferingShowsOnPlayerControlsBeforeTheLocalPlayerStarts() {
        val preparing = member.copy(preparing = true)
        assertEquals(true, lanPlayerControls(preparing).preparing)
        assertEquals("PLAY_PAUSE", lanRoomPlayPauseIntent(preparing))
        assertEquals(false, lanPlayerControls(preparing.copy(error = "SOURCE")).preparing)
        assertEquals(false, lanPlayerControls(member).preparing)
    }
}
