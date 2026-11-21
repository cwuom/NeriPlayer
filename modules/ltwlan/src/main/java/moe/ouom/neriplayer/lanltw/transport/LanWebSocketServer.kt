package moe.ouom.neriplayer.lanltw.transport

import moe.ouom.neriplayer.data.model.lan.LAN_PROTOCOL_VERSION
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
    private data class Peer(val id: String, val connectedAt: Double, @Volatile var joined: Boolean = false, var window: Double = connectedAt, var count: Int = 0, var backlog: Double? = null, var lastSendAt: Double = connectedAt)
    private val peers = ConcurrentHashMap<WebSocket, Peer>()
    private val closing = ConcurrentHashMap<WebSocket, Double>()
    val ready = CompletableDeferred<Int>()
    // 房间通过探测和应答判活，库的独立计时器会在进程刚恢复调度时提前关闭连接
    init { connectionLostTimeout = 0; isReuseAddr = true }
    override fun onStart() { ready.complete(port) }
    override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
        if (peers.size + closing.size >= 20) { socket.closeConnection(1008, "Room full"); return }
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
        if (m.type == "HELLO" && m.protocolVersion != LAN_PROTOCOL_VERSION) {
            socket.send(LanCodec.encode(LanMessage("LAN_ERROR", code = "VERSION")))
            socket.close(1008, "Version mismatch"); return
        }
        if (m.type != "HELLO" || (m.roomId != null && m.roomId != roomId) || name.isNullOrBlank() || name.length > 24) {
            socket.close(1008, "Handshake rejected"); return
        }
        synchronized(socket) {
            if (peers[socket] !== peer) return
            peer.joined = true
            onJoin(peer.id, name, m.hostToken == hostToken, m.volume?.coerceIn(0f, 1f) ?: 1f)
        }
    }
    fun sendTo(id: String, message: LanMessage) {
        val socket = peers.entries.firstOrNull { it.value.id == id }?.key ?: return
        val peer = peers[socket] ?: return
        val now = clock.nowMs()
        if (now - peer.lastSendAt > CLOSE_TIMEOUT_MS) peer.backlog = null
        peer.lastSendAt = now
        if (socket.hasBufferedData()) {
            val since = peer.backlog ?: now.also { peer.backlog = it }
            if (now - since > 2000) { socket.closeConnection(1008, "Slow peer"); return }
        } else peer.backlog = null
        runCatching { socket.send(LanCodec.encode(message)) }.onFailure { socket.close(1011, "Send failed") }
    }
    fun disconnect(id: String) { peers.entries.firstOrNull { it.value.id == id }?.key?.closeConnection(1008, "Session closed") }
    fun expireHandshakes() {
        val now = clock.nowMs()
        peers.forEach { (socket, peer) ->
            if (!peer.joined && now - peer.connectedAt > CLOSE_TIMEOUT_MS) socket.closeConnection(1008, "Handshake timeout")
        }
        closing.forEach { (socket, since) ->
            if (now - since >= CLOSE_TIMEOUT_MS) socket.closeConnection(1006, "Close timeout")
        }
    }
    override fun onClosing(socket: WebSocket, code: Int, reason: String, remote: Boolean) {
        val peer = peers.remove(socket) ?: return
        // 关闭帧可能被积压数据挡住，先撤销成员资格并给正常连接留出发送时间
        closing[socket] = clock.nowMs()
        if (peer.joined) onLeave(peer.id)
    }
    override fun onClose(socket: WebSocket, code: Int, reason: String, remote: Boolean) {
        closing.remove(socket)
        peers.remove(socket)?.takeIf { it.joined }?.let { onLeave(it.id) }
    }
    override fun onError(socket: WebSocket?, error: Exception) { if (socket == null) { ready.completeExceptionally(error); onFatal() } else socket.close(1011, "Transport error") }

    private companion object { const val CLOSE_TIMEOUT_MS = 5_000.0 }
}
