package moe.ouom.neriplayer.api.ltw.ws

import android.os.SystemClock
import moe.ouom.neriplayer.listentogether.protocol.listenTogetherProtocolJson
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val LISTEN_TOGETHER_MAX_WS_MESSAGE_CHARS = 2 * 1024 * 1024

const val LISTEN_TOGETHER_SOCKET_RESPONSE_TIMEOUT_MS = 35_000L

fun shouldReconnectListenTogetherSocket(
    reconnectEnabled: Boolean,
    connectionState: ListenTogetherConnectionState,
    lastMessageAtElapsedMs: Long,
    lastPingSentAtElapsedMs: Long,
    nowElapsedMs: Long,
    responseTimeoutMs: Long = LISTEN_TOGETHER_SOCKET_RESPONSE_TIMEOUT_MS
): Boolean {
    if (!reconnectEnabled || connectionState != ListenTogetherConnectionState.CONNECTED) {
        return false
    }
    if (lastPingSentAtElapsedMs <= 0L || nowElapsedMs < lastPingSentAtElapsedMs) {
        return false
    }
    if (nowElapsedMs - lastPingSentAtElapsedMs < responseTimeoutMs.coerceAtLeast(0L)) {
        return false
    }
    return lastMessageAtElapsedMs <= lastPingSentAtElapsedMs
}

class ListenTogetherWebSocketClient(
    private val okHttpClient: OkHttpClient
) {
    private val json = listenTogetherProtocolJson()

    // 回调运行在 OkHttp 分发线程, 需保证对 webSocket 引用的可见性
    // 否则 onOpen/onMessage 里的身份校验可能读到过期引用而误丢消息
    @Volatile
    private var webSocket: WebSocket? = null

    @Synchronized
    fun connect(
        wsUrl: String,
        listener: Listener
    ) {
        disconnect()
        val request = Request.Builder().url(wsUrl).build()
        val activeSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@ListenTogetherWebSocketClient.webSocket !== webSocket) return
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@ListenTogetherWebSocketClient.webSocket !== webSocket) return
                    if (text.length > LISTEN_TOGETHER_MAX_WS_MESSAGE_CHARS) {
                        listener.onProtocolError(
                            text.take(256),
                            IllegalArgumentException("WebSocket message too large: ${text.length} chars")
                        )
                        disconnect(code = 1009, reason = "message_too_large")
                        return
                    }
                    runCatching {
                        json.decodeFromString<ListenTogetherSocketEnvelope>(text)
                    }.onSuccess(listener::onMessage)
                        .onFailure { listener.onProtocolError(text, it) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@ListenTogetherWebSocketClient.webSocket !== webSocket) return
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@ListenTogetherWebSocketClient.webSocket !== webSocket) return
                    val message = buildString {
                        append(t.message ?: t.javaClass.simpleName)
                        response?.let {
                            append(" (http=")
                            append(it.code)
                            append(' ')
                            append(it.message)
                            append(')')
                        }
                    }
                    listener.onFailure(IllegalStateException(message, t))
                }
            }
        )
        webSocket = activeSocket
    }

    @Synchronized
    fun sendEvent(event: ListenTogetherEvent): Boolean {
        return webSocket?.send(json.encodeToString(event)) == true
    }

    @Synchronized
    fun sendPing(sentAtElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return webSocket?.send("""{"type":"np_ping","t":$sentAtElapsedMs}""") == true
    }

    @Synchronized
    fun sendLegacyPing(): Boolean {
        return webSocket?.send("""{"type":"ping"}""") == true
    }

    @Synchronized
    fun disconnect(code: Int = 1000, reason: String = "client_closed") {
        webSocket?.close(code, reason)
        webSocket = null
    }

    interface Listener {
        fun onOpen()
        fun onMessage(message: ListenTogetherSocketEnvelope)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
        fun onProtocolError(rawText: String, error: Throwable)
    }
}
