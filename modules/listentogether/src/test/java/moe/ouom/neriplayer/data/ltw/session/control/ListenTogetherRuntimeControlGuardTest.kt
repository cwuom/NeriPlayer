package moe.ouom.neriplayer.data.ltw.session.control

import android.content.Context
import moe.ouom.neriplayer.data.ltw.testing.*
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ListenTogetherRuntimeControlGuardTest {
    @Test
    fun `listener guard rejects stale intent and holds playback until shared stream is available`() {
        val player = FakeListenTogetherPlaybackHost().apply { currentSongFlow.value = testSong(); waitForStream = true }
        val guard = ListenTogetherListenerControlSuppression(player, TestSongMapper)
        val room = testRoom(shareLinks = true)
        val event = ListenTogetherEvent("REQUEST_PAUSE", requestTrackStableKey = "netease:1")
        assertNull(guard.reason(event, true, room))
        assertNull(guard.reason(event.copy(type = "HEARTBEAT"), false, room))
        assertEquals(ListenTogetherListenerSuppressionReason.STALE_TARGET, guard.reason(event.copy(requestTrackStableKey = "netease:2"), false, room))
        assertEquals(ListenTogetherListenerSuppressionReason.AWAITING_STREAM, guard.reason(event, false, room))
        player.currentMediaUrlFlow.value = "https://m701.music.126.net/1"
        assertNull(guard.reason(event, false, room))
        player.currentMediaUrlFlow.value = null
        player.currentSongFlow.value = testSong().copy(streamUrl = "https://m701.music.126.net/1")
        assertNull(guard.reason(event, false, room))
        player.currentSongFlow.value = null
        guard.reason(event, false, room)
        assertEquals(ListenTogetherListenerSuppressionReason.STALE_TARGET, guard.reason(event, false, null))
        assertNull(guard.reason(event.copy(type = "REQUEST_SET_QUEUE"), false, null))
    }

    @Test
    fun `forwarded guard rejects unknown rooms disabled membership and stale target`() {
        val room = testRoom()
        val event = ListenTogetherEvent("PLAY", requestTrackStableKey = "netease:1")
        val request = ListenTogetherSocketEnvelope(type = "member_control_requested", causedBy = ListenTogetherCause(userUuid = "listener", type = "REQUEST_PLAY"))
        assertEquals("reject_member_control_room_unknown", forwardedListenTogetherRejectionReason(null, request, event))
        assertEquals("reject_member_control_disabled", forwardedListenTogetherRejectionReason(room.copy(settings = room.settings.copy(allowMemberControl = false)), request, event))
        assertEquals("reject_stale_member_control", forwardedListenTogetherRejectionReason(room, request, event.copy(requestTrackStableKey = "other")))
        assertNull(forwardedListenTogetherRejectionReason(room, request, event))
        assertNull(forwardedListenTogetherRejectionReason(room, request.copy(causedBy = null), event))
        assertNull(forwardedListenTogetherRejectionReason(room, request.copy(causedBy = ListenTogetherCause(type = "REQUEST_SET_QUEUE")), event))
    }

    @Test
    fun `local command permissions preserve controller offline and member-control messages`() {
        val context = mock(Context::class.java)
        `when`(context.getString(anyInt())).thenAnswer { "resource:${it.arguments[0]}" }
        val room = testRoom()
        assertNull(resolveListenTogetherControlBlockReason(context, "controller", room, "PLAY"))
        assertNull(resolveListenTogetherControlBlockReason(context, "listener", room, "PLAY"))
        assertNotNull(resolveListenTogetherControlBlockReason(context, "listener", room.copy(settings = room.settings.copy(allowMemberControl = false)), "PLAY"))
        assertNull(resolveListenTogetherControlBlockReason(context, "listener", room.copy(settings = room.settings.copy(allowMemberControl = false)), "UNKNOWN"))
        val offline = room.copy(roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE)
        assertNotNull(resolveListenTogetherControlBlockReason(context, "listener", offline, "PLAY"))
        assertNotEquals(resolveListenTogetherControlBlockReason(context, "listener", offline, "PLAY"), resolveListenTogetherControlBlockReason(context, "listener", offline.copy(settings = offline.settings.copy(shareAudioLinks = true)), "PLAY"))
        assertNull(resolveListenTogetherControlBlockReason(context, "controller", offline, "PLAY"))
        assertNull(resolveListenTogetherControlBlockReason(context, null, null, "PLAY"))
    }
}
