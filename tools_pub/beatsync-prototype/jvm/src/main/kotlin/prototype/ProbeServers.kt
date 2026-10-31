package prototype

import kotlinx.serialization.json.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

fun nowMs(): Double = System.nanoTime() / 1e6
interface ProbeServer : AutoCloseable {
    val name: String
    fun start(): Int
}
// Identical application handler for both transports. This is test protocol, not production room logic.
class ProbeHandler {
    private var ready = false
    fun respond(text: String, t1: Double): String {
        if (text.length > 65536) return """{"type":"ERROR","code":"TOO_LARGE"}"""
        val message = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) {
            return """{"type":"ERROR","code":"MALFORMED"}"""
        }
        return when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
            "HELLO" -> {
                ready = (message["protocolVersion"] as? JsonPrimitive)?.intOrNull == 1
                if (ready) """{"type":"HELLO_ACK","protocolVersion":1}""" else """{"type":"ERROR","code":"VERSION"}"""
            }
            "NTP_REQUEST" -> {
                if (!ready) return """{"type":"ERROR","code":"NOT_READY"}"""
                val t0 = (message["t0"] as? JsonPrimitive)?.doubleOrNull
                val group = (message["probeGroupId"] as? JsonPrimitive)?.intOrNull
                val index = (message["probeGroupIndex"] as? JsonPrimitive)?.intOrNull
                if (t0 == null || !t0.isFinite() || group == null || index !in 0..1) return """{"type":"ERROR","code":"INVALID_PROBE"}"""
                val t2 = nowMs()
                buildJsonObject {
                    put("type","NTP_RESPONSE"); put("t0",t0); put("t1",t1); put("t2",t2)
                    put("probeGroupId",group); put("probeGroupIndex",index!!)
                }.toString()
            }
            "PING" -> """{"type":"PONG"}"""
            else -> """{"type":"ERROR","code":"UNKNOWN"}"""
        }
    }
}
class JavaWsProbeServer : ProbeServer {
    override val name = "java-websocket"
    private val ready = CountDownLatch(1)
    private val failure = AtomicReference<Exception?>()
    private val handlers = ConcurrentHashMap<WebSocket, ProbeHandler>()
    private val server = object : WebSocketServer(InetSocketAddress("127.0.0.1",0),2) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) { handlers[conn] = ProbeHandler() }
        override fun onClose(conn: WebSocket, code:Int, reason:String, remote:Boolean) { handlers.remove(conn) }
        override fun onMessage(conn:WebSocket, message:String) {
            val t1 = nowMs()
            val handler = handlers[conn] ?: error("Missing session")
            conn.send(handler.respond(message,t1))
        }
        override fun onError(conn:WebSocket?, ex:Exception) { failure.compareAndSet(null,ex); ready.countDown() }
        override fun onStart() { ready.countDown() }
    }
    override fun start():Int {
        server.connectionLostTimeout=0
        server.start()
        check(ready.await(10,TimeUnit.SECONDS)) { "Java-WebSocket startup timeout" }
        failure.get()?.let { throw it }
        return server.port
    }
    override fun close() { server.stop(2000); failure.get()?.let { throw it } }
}
