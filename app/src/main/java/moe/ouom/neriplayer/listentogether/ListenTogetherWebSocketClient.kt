package moe.ouom.neriplayer.listentogether

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class ListenTogetherWebSocketClient(
    private val okHttpClient: OkHttpClient
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private var webSocket: WebSocket? = null

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

    fun sendEvent(event: ListenTogetherEvent): Boolean {
        return webSocket?.send(json.encodeToString(event)) == true
    }

    fun sendPing(): Boolean {
        return webSocket?.send("""{"type":"ping"}""") == true
    }

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
