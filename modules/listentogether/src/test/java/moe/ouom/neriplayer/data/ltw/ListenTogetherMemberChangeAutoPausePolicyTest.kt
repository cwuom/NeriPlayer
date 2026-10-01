package moe.ouom.neriplayer.data.ltw

import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.ltw.session.membership.shouldApplyListenTogetherClosedRoomPause
import moe.ouom.neriplayer.data.ltw.session.membership.shouldAutoPauseListenTogetherForMemberChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherMemberChangeAutoPausePolicyTest {

    @Test
    fun `enabled setting pauses for both a new member and an explicit leave`() {
        assertTrue(
            shouldAutoPauseListenTogetherForMemberChange(
                autoPauseOnMemberChange = true,
                memberChangeType = "MEMBER_JOINED"
            )
        )
        assertTrue(
            shouldAutoPauseListenTogetherForMemberChange(
                autoPauseOnMemberChange = true,
                memberChangeType = "MEMBER_LEFT"
            )
        )
    }

    @Test
    fun `disabled setting and transport-only rejoin do not pause playback`() {
        assertFalse(
            shouldAutoPauseListenTogetherForMemberChange(
                autoPauseOnMemberChange = false,
                memberChangeType = "MEMBER_JOINED"
            )
        )
        assertFalse(
            shouldAutoPauseListenTogetherForMemberChange(
                autoPauseOnMemberChange = true,
                memberChangeType = "MEMBER_REJOINED"
            )
        )
    }

    @Test
    fun `closed room applies only an authoritative paused state`() {
        assertTrue(
            shouldApplyListenTogetherClosedRoomPause(
                roomState(playbackState = "paused")
            )
        )
        assertFalse(
            shouldApplyListenTogetherClosedRoomPause(
                roomState(playbackState = "playing")
            )
        )
    }

    private fun roomState(playbackState: String): ListenTogetherRoomState {
        return ListenTogetherRoomState(
            roomId = "ROOM1",
            version = 1L,
            playback = ListenTogetherPlaybackState(state = playbackState)
        )
    }
}
