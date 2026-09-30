package moe.ouom.neriplayer.data.ltw.compat

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.queue.ListenTogetherQueueMutation
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherControlAcknowledgementTest {
    private val track = ListenTogetherTrack(stableKey = "track", channelId = "netease", audioId = "1", name = "song", artist = "artist")
    private val state = ListenTogetherRoomState(roomId = "room", version = 2L, queue = listOf(track), currentIndex = 0)

    @Test
    fun `transport acknowledgement requires matching intent and valid committed state`() {
        val playing = state.copy(playback = ListenTogetherPlaybackState(state = "playing", basePositionMs = 8_000L))
        assertTrue(satisfied(ListenTogetherEvent(type = "REQUEST_PLAY"), playing))
        assertFalse(satisfied(ListenTogetherEvent(type = "REQUEST_PAUSE"), playing))
        assertTrue(satisfied(ListenTogetherEvent(type = "REQUEST_PAUSE"), state))
        assertFalse(satisfied(ListenTogetherEvent(type = "REQUEST_PLAY"), state))
        assertTrue(satisfied(ListenTogetherEvent(type = "REQUEST_SEEK", positionMs = 8_001L), playing))
        assertFalse(satisfied(ListenTogetherEvent(type = "REQUEST_SEEK", positionMs = 20_000L), playing))
        assertFalse(satisfied(ListenTogetherEvent(type = "REQUEST_SEEK"), playing))
        assertFalse(satisfied(ListenTogetherEvent(type = "unknown"), state))
        assertFalse(satisfied(ListenTogetherEvent(type = "PLAY"), null))
    }

    @Test
    fun `partial playback mode intent only compares specified fields`() {
        val current = state.copy(playback = ListenTogetherPlaybackState(repeatMode = 1, shuffleEnabled = true))
        assertTrue(satisfied(ListenTogetherEvent(type = "PLAYBACK_MODE"), current))
        assertTrue(satisfied(ListenTogetherEvent(type = "PLAYBACK_MODE", repeatMode = 1), current))
        assertTrue(satisfied(ListenTogetherEvent(type = "PLAYBACK_MODE", shuffleEnabled = true), current))
        assertFalse(satisfied(ListenTogetherEvent(type = "PLAYBACK_MODE", repeatMode = 2), current))
        assertFalse(satisfied(ListenTogetherEvent(type = "PLAYBACK_MODE", shuffleEnabled = false), current))
    }

    @Test
    fun `track intent supports legacy indexed queue and rejects missing or mismatched target`() {
        val event = ListenTogetherEvent(type = "SET_TRACK", queue = listOf(track), currentIndex = 0)
        assertTrue(satisfied(event, state))
        assertFalse(satisfied(event.copy(currentIndex = -1), state))
        assertFalse(satisfied(event.copy(currentIndex = null), state))
        assertFalse(satisfied(event.copy(queue = null), state))
        assertFalse(satisfied(event.copy(track = track.copy(stableKey = "other")), state))
    }

    @Test
    fun `queue acknowledgement rejects malformed snapshots and partially cleared state`() {
        val event = ListenTogetherEvent(type = "SET_QUEUE", queue = listOf(track), currentIndex = 0)
        assertTrue(satisfied(event, state))
        assertFalse(satisfied(event.copy(queue = null), state))
        assertFalse(satisfied(event.copy(currentIndex = null), state))
        assertFalse(satisfied(event.copy(currentIndex = 2), state))
        assertFalse(satisfied(event, state.copy(currentIndex = -1)))
        assertFalse(satisfied(event, state.copy(queue = listOf(track.copy(stableKey = "other")))))
        val clear = event.copy(queue = emptyList(), currentIndex = -1)
        val cleared = state.copy(queue = emptyList(), currentIndex = -1, track = null)
        assertTrue(satisfied(clear, cleared))
        assertFalse(satisfied(clear.copy(currentIndex = 0), cleared))
        assertFalse(satisfied(clear, cleared.copy(queue = listOf(track))))
        assertFalse(satisfied(clear, cleared.copy(currentIndex = 0)))
        assertFalse(satisfied(clear, cleared.copy(track = track)))
    }

    @Test
    fun `mutation acknowledgement requires causal event and newer version with selected target`() {
        val event = ListenTogetherEvent(type = "SET_QUEUE", eventId = "event", queueMutation = ListenTogetherQueueMutation(baseRoomVersion = 1L, operations = emptyList()))
        assertTrue(isListenTogetherPendingMemberControlSatisfied(event, state, committedEventId = "event"))
        assertTrue(isListenTogetherPendingMemberControlSatisfied(event.copy(track = track), state, committedEventId = "event"))
        assertFalse(isListenTogetherPendingMemberControlSatisfied(event.copy(track = track.copy(stableKey = "other")), state, committedEventId = "event"))
        assertFalse(isListenTogetherPendingMemberControlSatisfied(event, state.copy(version = 1L), committedEventId = "event"))
        for (id in listOf(null, "", " ", "other")) {
            assertFalse(isListenTogetherPendingMemberControlSatisfied(event.copy(eventId = id), state, committedEventId = "event"))
        }
    }

    @Test
    fun `legacy compatibility errors and unknown errors are distinguished`() {
        for (message in listOf("queue mutation is invalid", "queue mutation base version is ahead", "queue mutation event type unsupported", "queue update queue required")) {
            assertTrue(isListenTogetherQueueMutationCompatibilityError(" $message "))
        }
        for (message in listOf(null, "", "other error")) {
            assertFalse(isListenTogetherQueueMutationCompatibilityError(message))
            assertFalse(isUnsupportedTrackFinishedEventError(message))
        }
        assertFalse(isUnsupportedTrackFinishedEventError("TRACK_FINISHED temporary failure"))
    }

    @Test
    fun `playback intent and link readiness preserve paused and unknown command cases`() {
        assertFalse(resolveListenTogetherPlaybackCommandShouldPlay("SEEK", null, false, false))
        assertTrue(resolveListenTogetherPlaybackCommandShouldPlay("SEEK", null, false, true))
        assertFalse(resolveListenTogetherPlaybackCommandShouldPlay("unknown", null, true, false))
        assertTrue(resolveListenTogetherPlaybackCommandShouldPlay("unknown", null, false, true))
        assertEquals("paused", resolveListenTogetherLinkReadyState(null, false, false))
        assertEquals("paused", resolveListenTogetherLinkReadyState("paused", false, false))
        assertEquals("playing", resolveListenTogetherLinkReadyState("paused", false, true))
    }

    @Test
    fun `track finished fallback handles absent next index and legacy queue track`() {
        fun fallback(event: ListenTogetherEvent) = buildTrackFinishedLegacyFallbackEvent(event, true, 1L) { "legacy" }
        assertNull(fallback(ListenTogetherEvent(type = "PLAY")))
        assertEquals("PAUSE", fallback(ListenTogetherEvent(type = "TRACK_FINISHED", shouldPlay = true))?.type)
        val advanced = fallback(ListenTogetherEvent(type = "TRACK_FINISHED", shouldPlay = true, currentIndex = 0, queue = listOf(track)))
        assertEquals(track, advanced?.track)
        assertNull(fallback(ListenTogetherEvent(type = "TRACK_FINISHED", shouldPlay = true, currentIndex = 0))?.track)
    }

    private fun satisfied(event: ListenTogetherEvent, state: ListenTogetherRoomState?) =
        isListenTogetherPendingMemberControlSatisfied(event, state)
}
