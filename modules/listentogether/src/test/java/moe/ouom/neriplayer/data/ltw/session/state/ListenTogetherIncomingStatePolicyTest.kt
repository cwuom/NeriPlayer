package moe.ouom.neriplayer.data.ltw.session.state

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherCause
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherIncomingStatePolicyTest {
    private val track = ListenTogetherTrack(stableKey = "track", channelId = "netease", audioId = "1", name = "song", artist = "artist")
    private val state = ListenTogetherRoomState(roomId = "room", version = 2L, queue = listOf(track), currentIndex = 0)

    @Test
    fun `only newer matching room queue updates override optimistic state`() {
        val cause = ListenTogetherCause(type = "SET_QUEUE")
        val changed = state.copy(version = 3L, queue = listOf(track.copy(stableKey = "other")))
        assertTrue(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, changed, state))
        assertFalse(shouldAcceptListenTogetherAuthoritativeQueueUpdate(null, changed, state))
        assertFalse(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, null, state))
        assertFalse(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, changed, null))
        assertFalse(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, changed.copy(roomId = "other"), state))
        assertFalse(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, changed.copy(version = 2L), state))
        assertFalse(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, state.copy(version = 3L), state))
        assertTrue(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, state.copy(version = 3L, currentIndex = -1), state))
        val empty = state.copy(queue = emptyList(), track = track)
        assertTrue(shouldAcceptListenTogetherAuthoritativeQueueUpdate(cause, empty.copy(version = 3L, track = track.copy(stableKey = "other")), empty))
    }

    @Test
    fun `replay filter respects request and track finished causes before identity deduplication`() {
        fun ignores(cause: ListenTogetherCause?) = shouldIgnoreListenTogetherIncomingState(cause, "self", { it == "out" }, { it == "in" })
        assertFalse(ignores(null))
        assertFalse(ignores(ListenTogetherCause(type = "TRACK_FINISHED", eventId = "out", userUuid = "self")))
        assertFalse(ignores(ListenTogetherCause(type = "REQUEST_PLAY", eventId = "out", userUuid = "self")))
        assertTrue(ignores(ListenTogetherCause(type = "PLAY", eventId = "out")))
        assertTrue(ignores(ListenTogetherCause(type = "PLAY", eventId = "in")))
        assertTrue(ignores(ListenTogetherCause(type = null, eventId = "event", userUuid = "self")))
        assertFalse(ignores(ListenTogetherCause(type = "PLAY", eventId = "event", userUuid = "other")))
        for (id in listOf(null, "", " ")) assertFalse(ignores(ListenTogetherCause(type = "PLAY", eventId = id, userUuid = "self")))
    }

    @Test
    fun `track finish barrier blocks only passive playing updates for its current track`() {
        val playing = state.copy(playback = ListenTogetherPlaybackState(state = "playing"))
        val heartbeat = ListenTogetherCause(type = "HEARTBEAT")
        assertTrue(shouldDeferListenTogetherIncomingStateForLocalTrackFinish(playing, heartbeat, "track"))
        assertFalse(shouldDeferListenTogetherIncomingStateForLocalTrackFinish(playing, heartbeat, null))
        assertFalse(shouldDeferListenTogetherIncomingStateForLocalTrackFinish(playing, null, "track"))
        assertFalse(shouldDeferListenTogetherIncomingStateForLocalTrackFinish(state, heartbeat, "track"))
        assertFalse(shouldDeferListenTogetherIncomingStateForLocalTrackFinish(playing, heartbeat, "other"))
    }
}
