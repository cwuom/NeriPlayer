package moe.ouom.neriplayer.lanltw.transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            { id, _, host, _ -> joined.put(id to host); server.sendTo(id, LanMessage("WELCOME", clientId = id)) },
            { _, _ -> }, { }, { })
        val http = OkHttpClient()
        val client = LanWebSocketClient(http, clock)
        try {
            server.start(); val port = withTimeout(5000) { server.ready.await() }
            client.connect("http://127.0.0.1:$port/ws", LanMessage("HELLO", roomId = "room", name = "Member"), {}, { m, _ -> incoming.put(m) }, {})
            assertFalse(joined.poll(5, TimeUnit.SECONDS).second)
            assertEquals("WELCOME", incoming.poll(5, TimeUnit.SECONDS).type)
            val sent = clock.nowMs()
            assertTrue(client.send(LanMessage("NTP_REQUEST", t0 = sent, probeGroupId = 7, probeGroupIndex = 0)))
            val response = incoming.poll(5, TimeUnit.SECONDS)
            assertEquals("NTP_RESPONSE", response.type); assertEquals(sent, response.t0!!, .00001)
            assertTrue(response.t2!! >= response.t1!!); assertEquals(7, response.probeGroupId)
        } finally { client.close(); server.stop(1500); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
