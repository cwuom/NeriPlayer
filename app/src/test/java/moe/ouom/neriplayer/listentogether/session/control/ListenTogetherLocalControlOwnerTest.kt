package moe.ouom.neriplayer.listentogether.session.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherCause
import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.listentogether.protocol.message.queue.ListenTogetherQueueMutation
import moe.ouom.neriplayer.listentogether.protocol.model.room.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.model.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherLocalControlOwnerTest {
    private val track = ListenTogetherTrack(
        stableKey = "netease:track", channelId = "netease", audioId = "track",
        name = "Track", artist = "Artist"
    )

    @Test
    fun `coalescing keeps the latest queue intent and cancellation prevents delivery`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)

        owner.enqueueOrDispatch(event("SET_QUEUE", "old"), "room")
        owner.enqueueOrDispatch(event("SET_QUEUE", "new"), "room")
        runCurrent()
        assertTrue(port.sent.isEmpty())
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(listOf("new"), port.sent.map { it.eventId })

        owner.enqueueOrDispatch(event("SET_QUEUE", "cancelled"), "room")
        owner.clearCoalesced("leave")
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(listOf("new"), port.sent.map { it.eventId })
    }

    @Test
    fun `dispatch rejects stale room and replays only unacknowledged room intents`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)

        owner.enqueueOrDispatch(event("PLAY", "stale"), "other")
        assertTrue(port.sent.isEmpty())
        owner.enqueueOrDispatch(event("PLAY", "play"), "room")
        owner.enqueueOrDispatch(event("SEEK", "seek"), "room")
        owner.acknowledge("play")
        owner.replayPending()
        assertEquals(listOf("play", "seek", "seek"), port.sent.map { it.eventId })

        port.roomId = "other"
        owner.replayPending()
        assertEquals(3, port.sent.size)
        owner.clearOutbox()
        port.roomId = "room"
        owner.replayPending()
        assertEquals(3, port.sent.size)
    }

    @Test
    fun `queue compatibility rejection replaces the pending mutation once`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        val mutation = ListenTogetherEvent(
            type = "SET_QUEUE", eventId = "mutation",
            queueMutation = ListenTogetherQueueMutation(1L, emptyList()),
            legacyQueueSnapshot = listOf(track)
        )

        owner.enqueueOrDispatch(mutation, "room")
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(owner.tryQueueMutationLegacyFallback("network timeout", "mutation"))
        assertTrue(owner.tryQueueMutationLegacyFallback("queue mutation is invalid", "mutation"))
        assertEquals(2, port.sent.size)
        assertEquals(listOf(track), port.sent.last().queue)
        assertEquals(null, port.sent.last().queueMutation)
        assertFalse(owner.tryQueueMutationLegacyFallback("queue mutation is invalid", "mutation"))
    }

    @Test
    fun `queue fallback requires a pending snapshot in the current room`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        assertFalse(owner.tryQueueMutationLegacyFallback("queue mutation is invalid", "missing"))

        owner.enqueueOrDispatch(event("PLAY", "plain"), "room")
        assertFalse(owner.tryQueueMutationLegacyFallback("queue mutation is invalid", "plain"))

        owner.enqueueOrDispatch(ListenTogetherEvent(
            type = "SET_QUEUE", eventId = "mutation",
            queueMutation = ListenTogetherQueueMutation(1L, emptyList()),
            legacyQueueSnapshot = listOf(track)
        ), "room")
        advanceTimeBy(100L)
        runCurrent()
        port.roomId = "other"
        assertFalse(owner.tryQueueMutationLegacyFallback("queue mutation is invalid", "mutation"))
        port.roomId = "room"
        assertTrue(owner.tryQueueMutationLegacyFallback("queue mutation is invalid", null))
    }

    @Test
    fun `blank current room never dispatches a local control event`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        port.roomId = ""

        owner.enqueueOrDispatch(event("PLAY", "play"), "room")

        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun `track finished fallback is bounded and clears its local barrier`() = runTest {
        val port = FakePort(controller = true)
        val owner = owner(this, port)
        val finished = ListenTogetherEvent(
            type = "TRACK_FINISHED", eventId = "finished", shouldPlay = true,
            currentIndex = 0, nextIndex = 1, finishedTrackStableKey = "old",
            queue = listOf(track, track.copy(stableKey = "netease:next"))
        )

        owner.enqueueOrDispatch(finished, "room")
        assertEquals("old", owner.awaitingTrackFinishStableKey())
        assertTrue(owner.tryTrackFinishedLegacyFallback("unsupported event type: TRACK_FINISHED"))
        assertEquals("SET_TRACK", port.sent.last().type)
        assertEquals(null, owner.awaitingTrackFinishStableKey())
        assertFalse(owner.tryTrackFinishedLegacyFallback("unsupported event type: TRACK_FINISHED"))

        owner.enqueueOrDispatch(finished.copy(eventId = "expired"), "room")
        port.nowElapsedMs += 16_000L
        assertFalse(owner.tryTrackFinishedLegacyFallback("unsupported event type: TRACK_FINISHED"))
    }

    @Test
    fun `member request retries only while unsatisfied and stops on acknowledgement`() = runTest {
        val port = FakePort(controller = false)
        val owner = owner(this, port)
        val request = event("REQUEST_PLAY", "request")

        owner.enqueueOrDispatch(request, "room")
        port.nowElapsedMs += 2_000L
        owner.retryPendingMemberRequest(null)
        assertEquals(1, port.sent.size)
        port.nowElapsedMs += 1_000L
        owner.retryPendingMemberRequest(null)
        assertEquals(2, port.sent.size)
        owner.acknowledgeMember(ListenTogetherCause(eventId = "request"))
        port.nowElapsedMs += 4_000L
        owner.retryPendingMemberRequest(null)
        assertEquals(2, port.sent.size)
    }

    @Test
    fun `member request clears when room playback satisfies it or time expires`() = runTest {
        val port = FakePort(controller = false)
        val owner = owner(this, port)
        val playing = ListenTogetherRoomState(
            roomId = "room", version = 2L,
            playback = ListenTogetherPlaybackState(state = "playing")
        )

        owner.enqueueOrDispatch(event("REQUEST_PLAY", "satisfied"), "room")
        port.nowElapsedMs += 4_000L
        owner.retryPendingMemberRequest(playing)
        owner.retryPendingMemberRequest(null)
        assertEquals(1, port.sent.size)

        owner.enqueueOrDispatch(event("REQUEST_PAUSE", "expired"), "room")
        port.nowElapsedMs += 19_000L
        owner.retryPendingMemberRequest(null)
        assertEquals(2, port.sent.size)
    }

    @Test
    fun `committed track clears the finish barrier and disconnect preserves the outbox`() = runTest {
        val port = FakePort(controller = true)
        val owner = owner(this, port)
        owner.enqueueOrDispatch(ListenTogetherEvent(
            type = "TRACK_FINISHED", eventId = "finished", finishedTrackStableKey = "old"
        ), "room")
        assertEquals("old", owner.awaitingTrackFinishStableKey())

        owner.onRoomStateCommitted("old")
        assertEquals("old", owner.awaitingTrackFinishStableKey())
        owner.onRoomStateCommitted("new")
        assertEquals(null, owner.awaitingTrackFinishStableKey())
        owner.resetTransientRequests()
        owner.replayPending()
        assertEquals(listOf("finished", "finished"), port.sent.map { it.eventId })
    }

    private fun owner(
        scope: kotlinx.coroutines.CoroutineScope,
        port: FakePort
    ) = ListenTogetherLocalControlOwner(
        scope = scope, port = port,
        elapsedRealtimeMs = { port.nowElapsedMs },
        wallTimeMs = { 100L }
    )

    private fun event(type: String, eventId: String) = ListenTogetherEvent(type = type, eventId = eventId)

    private class FakePort(
        overrideRoomId: String = "room",
        private val controller: Boolean = false
    ) : ListenTogetherLocalControlPort {
        var roomId = overrideRoomId
        var nowElapsedMs = 1_000L
        val sent = mutableListOf<ListenTogetherEvent>()
        private var nextId = 0

        override fun currentRoomId(): String = roomId
        override fun isController(): Boolean = controller
        override fun nextEventId(): String = "fallback-${++nextId}"
        override fun markOutbound(eventId: String?) = Unit
        override fun noteOutboundSync() = Unit
        override fun send(event: ListenTogetherEvent, reason: String): Boolean {
            sent += event
            return true
        }
    }
}
