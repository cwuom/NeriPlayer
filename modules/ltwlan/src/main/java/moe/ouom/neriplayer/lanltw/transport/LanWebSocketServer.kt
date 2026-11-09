package moe.ouom.neriplayer.lanltw.transport

import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.core.LanClock
import moe.ouom.neriplayer.lanltw.protocol.LanCodec
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

class LanWebSocketServer(
    private val roomId: String, private val hostToken: String, private val clock: LanClock,
    private val onJoin: (String, String, Boolean, Float) -> Unit,
    private val onMessage: (String, LanMessage) -> Unit,
    private val onLeave: (String) -> Unit,
    private val onFatal: () -> Unit
) : WebSocketServer(InetSocketAddress(0), 2, listOf(Draft_6455(emptyList(), LanCodec.MAX_BYTES))) {
    private data class Peer(val id: String, val connectedAt: Double, @Volatile var joined: Boolean = false, var window: Double = connectedAt, var count: Int = 0, var backlog: Double = 0.0)
    private val peers = ConcurrentHashMap<WebSocket, Peer>()
    val ready = CompletableDeferred<Int>()
    init { connectionLostTimeout = 15; isReuseAddr = true }
    override fun onStart() { ready.complete(port) }
    override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
        if (peers.size >= 20) { socket.close(1008, "Room full"); return }
        peers[socket] = Peer(UUID.randomUUID().toString(), clock.nowMs())
    }
    override fun onMessage(socket: WebSocket, text: String) {
        val receivedAt = clock.nowMs()
        val peer = peers[socket] ?: return
        if (receivedAt - peer.window >= 1000) { peer.window = receivedAt; peer.count = 0 }
        if (++peer.count > 100) { socket.close(1008, "Rate limit"); return }
        val m = LanCodec.decode(text) ?: return socket.close(1008, "Invalid message")
        if (!peer.joined) { hello(socket, peer, m); return }
        if (m.type == "NTP_REQUEST") {
            if (!LanCodec.validProbe(m)) return socket.close(1008, "Invalid probe")
            onMessage(peer.id, m)
            socket.send(LanCodec.encode(LanMessage("NTP_RESPONSE", t0 = m.t0, t1 = receivedAt, t2 = clock.nowMs(), probeGroupId = m.probeGroupId, probeGroupIndex = m.probeGroupIndex)))
        } else onMessage(peer.id, m)
    }
    private fun hello(socket: WebSocket, peer: Peer, m: LanMessage) {
        val name = m.name
        if (m.type != "HELLO" || m.protocolVersion != 2 || (m.roomId != null && m.roomId != roomId) || name.isNullOrBlank() || name.length > 24) {
            socket.close(1008, "Handshake rejected"); return
        }
        peer.joined = true
        onJoin(peer.id, name, m.hostToken == hostToken, m.volume?.coerceIn(0f, 1f) ?: 1f)
    }
    fun sendTo(id: String, message: LanMessage) {
        val socket = peers.entries.firstOrNull { it.value.id == id }?.key ?: return
        val peer = peers[socket] ?: return
        if (socket.hasBufferedData()) {
            if (peer.backlog == 0.0) peer.backlog = clock.nowMs()
            if (clock.nowMs() - peer.backlog > 2000) { socket.close(1008, "Slow peer"); return }
        } else peer.backlog = 0.0
        runCatching { socket.send(LanCodec.encode(message)) }.onFailure { socket.close(1011, "Send failed") }
    }
    fun disconnect(id: String) { peers.entries.firstOrNull { it.value.id == id }?.key?.close(1008, "Session closed") }
    fun expireHandshakes() { peers.entries.filter { !it.value.joined && clock.nowMs() - it.value.connectedAt > 5000 }.forEach { it.key.close(1008, "Handshake timeout") } }
    override fun onClose(socket: WebSocket, code: Int, reason: String, remote: Boolean) { peers.remove(socket)?.takeIf { it.joined }?.let { onLeave(it.id) } }
    override fun onError(socket: WebSocket?, error: Exception) { if (socket == null) { ready.completeExceptionally(error); onFatal() } else socket.close(1011, "Transport error") }
}
