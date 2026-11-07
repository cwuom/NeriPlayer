package moe.ouom.neriplayer.lanltw.transport

import okhttp3.*
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.core.LanClock
import moe.ouom.neriplayer.lanltw.protocol.LanCodec

class LanWebSocketClient(private val http: OkHttpClient, private val clock: LanClock) {
    @Volatile private var socket: WebSocket? = null
    private val generations = java.util.concurrent.atomic.AtomicLong()
    fun connect(url: String, hello: LanMessage, onOpen: () -> Unit, onMessage: (LanMessage, Double) -> Unit, onFailure: () -> Unit) {
        close()
        val ticket = generations.incrementAndGet()
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generations.get() != ticket) return
                webSocket.send(LanCodec.encode(hello)); onOpen()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val t3 = clock.nowMs()
                if (generations.get() != ticket) return
                val message = LanCodec.decode(text)
                if (message == null) { webSocket.close(1008, "Invalid message"); return }
                onMessage(message, t3)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (generations.get() == ticket) onFailure() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (generations.get() == ticket) onFailure() }
        })
    }
    fun send(message: LanMessage): Boolean {
        val current = socket ?: return false
        if (current.queueSize() > 256 * 1024) { close(); return false }
        return current.send(LanCodec.encode(message))
    }
    fun close() { generations.incrementAndGet(); val previous = socket; socket = null; previous?.close(1000, "Leave"); previous?.cancel() }
}
