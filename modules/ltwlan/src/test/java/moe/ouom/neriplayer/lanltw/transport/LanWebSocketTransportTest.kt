package moe.ouom.neriplayer.lanltw.transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.lan.LAN_PROTOCOL_VERSION
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.core.LanClock
import moe.ouom.neriplayer.lanltw.protocol.LanCodec
import okhttp3.OkHttpClient
import org.java_websocket.WebSocketImpl
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.HandshakeImpl1Client
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LanWebSocketTransportTest {
    private class TransportFixture {
        var now = 1_000.0
        val joined = mutableListOf<String>()
        val left = mutableListOf<String>()
        val server = LanWebSocketServer("room", "secret", LanClock { now },
            { id, _, _, _ -> joined.add(id) }, { _, _ -> }, left::add, {})

        fun open(authenticate: Boolean = true): BlockedSocket {
            val socket = BlockedSocket(server)
            server.onOpen(socket, HandshakeImpl1Client())
            if (authenticate) {
                server.onMessage(socket, LanCodec.encode(LanMessage("HELLO", protocolVersion = LAN_PROTOCOL_VERSION, name = "Guest")))
            }
            return socket
        }
    }

    private class BlockedSocket(private val server: LanWebSocketServer) : WebSocketImpl(server, Draft_6455()) {
        var forcedCloses = 0
        var sent = 0
        override fun hasBufferedData() = true
        override fun send(text: String) { sent++ }
        override fun closeConnection(code: Int, reason: String) {
            forcedCloses++
            server.onClose(this, code, reason, false)
        }
    }

    @Test fun expiredOrUnauthenticatedPeersCloseWithoutWaitingForSocketWrites() {
        val fixture = TransportFixture()
        val member = fixture.open()
        val waiting = fixture.open(authenticate = false)
        val id = fixture.joined.single()
        fixture.server.disconnect(id)
        fixture.now += 6_000
        fixture.server.expireHandshakes()
        fixture.server.sendTo(id, LanMessage("SYNC"))
        assertEquals(0, member.sent)
        assertEquals(1, member.forcedCloses)
        assertEquals(1, waiting.forcedCloses)
        assertEquals(listOf(id), fixture.left)
    }

    @Test fun continuouslyBlockedPeerClosesAfterRegularWritesResume() {
        val fixture = TransportFixture()
        val socket = fixture.open()
        val id = fixture.joined.single()
        fixture.server.sendTo(id, LanMessage("SYNC"))
        fixture.now += 60_000
        fixture.server.sendTo(id, LanMessage("SYNC"))
        assertEquals(0, socket.forcedCloses)
        fixture.now += 2_500
        fixture.server.sendTo(id, LanMessage("SYNC"))
        assertEquals(1, socket.forcedCloses)
        assertEquals(listOf(id), fixture.left)
    }

    @Test fun closingRevokesMembershipOnceAndOnlyForcesUnfinishedHandshakes() {
        val fixture = TransportFixture()
        val blocked = fixture.open()
        val normal = fixture.open()
        fixture.server.onClosing(blocked, 1000, "Leave", true)
        fixture.server.onClosing(normal, 1000, "Leave", true)
        assertEquals(fixture.joined, fixture.left)
        fixture.server.onClose(normal, 1000, "Leave", true)
        fixture.now += 4_000
        fixture.server.expireHandshakes()
        assertEquals(0, blocked.forcedCloses)
        fixture.now += 1_000
        repeat(2) { fixture.server.expireHandshakes() }
        assertEquals(1, blocked.forcedCloses)
        assertEquals(0, normal.forcedCloses)
        assertEquals(fixture.joined, fixture.left)
    }

    @Test fun realServerHandshakeTimestampEchoAndClientClose() = runBlocking {
        val clock = LanClock { System.nanoTime() / 1e6 }
        val joined = LinkedBlockingQueue<Pair<String, Boolean>>()
        val incoming = LinkedBlockingQueue<LanMessage>()
        lateinit var server: LanWebSocketServer
        server = LanWebSocketServer("room", "private-host-token", clock,
            { id, _, host, _ -> joined.put(id to host)
                server.sendTo(id, LanMessage("WELCOME", protocolVersion = LAN_PROTOCOL_VERSION, clientId = id)) },
            { _, _ -> }, { }, { })
        val http = OkHttpClient()
        val client = LanWebSocketClient(http, clock)
        try {
            server.start(); val port = withTimeout(5000) { server.ready.await() }
            for (oldVersion in listOf(2, 3)) {
                client.connect("http://127.0.0.1:$port/ws",
                    LanMessage("HELLO", protocolVersion = oldVersion, roomId = "room", name = "Member"),
                    {}, { m, _ -> incoming.put(m) }, {})
                assertEquals("VERSION", incoming.poll(5, TimeUnit.SECONDS)?.code)
                assertTrue(joined.isEmpty())
            }
            client.connect("http://127.0.0.1:$port/ws",
                LanMessage("HELLO", protocolVersion = LAN_PROTOCOL_VERSION, roomId = "room", name = "Member"),
                {}, { m, _ -> incoming.put(m) }, {})
            assertFalse(joined.poll(5, TimeUnit.SECONDS).second)
            val welcome = incoming.poll(5, TimeUnit.SECONDS)
            assertEquals("WELCOME", welcome.type)
            assertEquals(LAN_PROTOCOL_VERSION, welcome.protocolVersion)
            val sent = clock.nowMs()
            assertTrue(client.send(LanMessage("NTP_REQUEST", t0 = sent, probeGroupId = 7, probeGroupIndex = 0)))
            val response = incoming.poll(5, TimeUnit.SECONDS)
            assertEquals("NTP_RESPONSE", response.type); assertEquals(sent, response.t0!!, .00001)
            assertTrue(response.t2!! >= response.t1!!); assertEquals(7, response.probeGroupId)
        } finally { client.close(); server.stop(1500); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
