package moe.ouom.neriplayer.lanltw.transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.lan.LAN_PROTOCOL_VERSION
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.core.LanClock
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LanWebSocketTransportTest {
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
