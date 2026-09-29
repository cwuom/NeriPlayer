package moe.ouom.neriplayer.listentogether.session.liveness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherHeartbeatOwnerTest {
    @Test
    fun `controller heartbeat waits for idle interval and outbound activity postpones it`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherHeartbeatOwner(this, port) { testScheduler.currentTime + 1_000L }
        owner.start()
        owner.start()
        runCurrent()
        advanceTimeBy(20_000L)
        runCurrent()
        assertTrue(port.sent.isEmpty())

        owner.noteOutboundSync()
        advanceTimeBy(21_000L)
        runCurrent()
        assertTrue(port.sent.isEmpty())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, port.sent.size)
        assertEquals(500L, port.sent.single().positionMs)
        owner.stop()
    }

    @Test
    fun `non-controller or unshareable track does not send heartbeat`() = runTest {
        val port = FakePort()
        val owner = ListenTogetherHeartbeatOwner(this, port) { testScheduler.currentTime + 1_000L }
        port.controller = false
        owner.start()
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(port.sent.isEmpty())

        port.controller = true
        port.shareable = false
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(port.sent.isEmpty())

        port.shareable = true
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, port.sent.size)
        owner.reset()
    }

    private class FakePort : ListenTogetherHeartbeatPort {
        var controller = true
        var shareable = true
        val sent = mutableListOf<ListenTogetherEvent>()
        private val state = ListenTogetherSessionState(
            roomId = "room", connectionState = ListenTogetherConnectionState.CONNECTED
        )
        override fun session(): ListenTogetherSessionState = state
        override fun isController(session: ListenTogetherSessionState): Boolean = controller
        override fun currentTrackShareable(): Boolean = shareable
        override fun playbackStateName(): String = "playing"
        override fun playbackPositionMs(): Long = 500L
        override fun buildHeartbeat(state: String, positionMs: Long): ListenTogetherEvent =
            ListenTogetherEvent(type = "HEARTBEAT", eventId = "heartbeat", positionMs = positionMs)
        override fun sendHeartbeat(event: ListenTogetherEvent) {
            sent += event
        }
    }
}
