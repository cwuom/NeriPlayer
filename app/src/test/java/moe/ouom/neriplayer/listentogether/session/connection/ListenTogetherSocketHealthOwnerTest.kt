package moe.ouom.neriplayer.listentogether.session.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherSocketHealthOwnerTest {
    @Test
    fun `pong estimates offset and stale round trips do not replace it`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        port.elapsedMs = 1_000L
        port.wallMs = 10_000L
        assertTrue(owner.sendPing())
        assertEquals(listOf(1_000L), port.pings)

        port.elapsedMs = 1_100L
        port.wallMs = 10_100L
        owner.onPong(serverNowMs = 10_150L, echoedSentAtElapsedMs = 1_000L)
        assertEquals(100L, owner.serverClockOffsetMs)

        port.wallMs = 10_200L
        owner.onServerMessage(serverNowMs = 10_250L, reason = "welcome")
        assertEquals(85L, owner.serverClockOffsetMs)
        owner.onRoundTrip(null, 10_000L, 1_000L, "missing_time")
        owner.onRoundTrip(-1L, 10_000L, 1_000L, "invalid_time")
        owner.onRoundTrip(11_000L, 10_000L, 2_000L, "future_ping")
        assertEquals(85L, owner.serverClockOffsetMs)

        port.elapsedMs = 32_000L
        port.wallMs = 41_100L
        owner.onPong(serverNowMs = 11_000L, echoedSentAtElapsedMs = 1_000L)
        owner.onPong(serverNowMs = 11_000L, echoedSentAtElapsedMs = null)
        assertEquals(85L, owner.serverClockOffsetMs)
    }

    @Test
    fun `unsupported clock ping switches transport and reset restores it`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        port.elapsedMs = 1_000L

        assertFalse(owner.handleUnsupportedPing("other rejection"))
        assertTrue(owner.handleUnsupportedPing("Unsupported event type: np_ping"))
        assertEquals(1, port.legacyPings)
        owner.sendPing()
        assertEquals(2, port.legacyPings)

        owner.resetConnectionTiming()
        owner.sendPing()
        assertEquals(1_000L, port.pings.last())
        assertEquals(0L, owner.serverClockOffsetMs)
    }

    @Test
    fun `keepalive requests reconnect only after an unanswered ping times out`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        owner.startKeepAlive()
        owner.startKeepAlive()
        runCurrent()
        assertEquals(1, port.pings.size)
        assertTrue(port.reconnectReasons.isEmpty())

        port.elapsedMs = 21_000L
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(2, port.pings.size)
        assertTrue(port.reconnectReasons.isEmpty())

        port.elapsedMs = 41_000L
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(listOf("socket_keep_alive_response_timeout"), port.reconnectReasons)
        assertTrue(owner.pendingRefreshAfterReconnect)
        owner.stopKeepAlive()
    }

    @Test
    fun `foreground probe reconnects only if the room has no newer message`() = runTest {
        val port = FakePort()
        val owner = owner(this, port)
        owner.scheduleForegroundProbe("room", 1_000L)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, port.connects)

        owner.pendingRefreshAfterReconnect = false
        owner.scheduleForegroundProbe("room", 6_000L)
        port.elapsedMs = 7_000L
        owner.noteMessage()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, port.connects)
        assertFalse(owner.pendingRefreshAfterReconnect)
    }

    private fun owner(scope: kotlinx.coroutines.CoroutineScope, port: FakePort) =
        ListenTogetherSocketHealthOwner(scope, port, { port.elapsedMs }, { port.wallMs })

    private class FakePort : ListenTogetherSocketHealthPort {
        var elapsedMs = 1_000L
        var wallMs = 10_000L
        var reconnect = true
        var sessionState = ListenTogetherSessionState(
            roomId = "room", connectionState = ListenTogetherConnectionState.CONNECTED
        )
        val pings = mutableListOf<Long>()
        var legacyPings = 0
        val reconnectReasons = mutableListOf<String>()
        var connects = 0

        override fun session(): ListenTogetherSessionState = sessionState
        override fun reconnectEnabled(): Boolean = reconnect
        override fun sendPing(sentAtElapsedMs: Long): Boolean {
            pings += sentAtElapsedMs
            return true
        }
        override fun sendLegacyPing(): Boolean {
            legacyPings++
            return true
        }
        override fun scheduleReconnect(reason: String) {
            reconnectReasons += reason
        }
        override fun connectWebSocket() {
            connects++
        }
        override fun updateBackgroundKeepAlive(reason: String) = Unit
    }
}
