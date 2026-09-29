package moe.ouom.neriplayer.api.ltw.ws

import moe.ouom.neriplayer.listentogether.protocol.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.message.socket.ListenTogetherSocketEnvelope
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ListenTogetherWebSocketClientTest {
    @Test
    fun `replaced and disconnected sockets cannot deliver callbacks into the current session`() {
        val fixture = Fixture()
        fixture.connect()
        val old = fixture.connections.single()
        fixture.connect()
        verify(old.socket).close(1000, "client_closed")
        old.deliverAllCallbacks()
        assertTrue(fixture.listener.events.isEmpty())

        val current = fixture.connections.last()
        current.deliverAllCallbacks()
        assertEquals(listOf("open", "np_pong", "closed:1000", "failure"), fixture.listener.events)
        fixture.client.disconnect()
        fixture.listener.events.clear()
        current.deliverAllCallbacks()
        assertTrue(fixture.listener.events.isEmpty())
    }

    @Test
    fun `malformed messages report a protocol error without closing a valid socket`() {
        val fixture = Fixture()
        fixture.connect()
        val current = fixture.connections.single()
        current.callback.onMessage(current.socket, "not-json")
        assertEquals(listOf("protocol"), fixture.listener.events)
        assertEquals("not-json", fixture.listener.rawText)
        assertTrue(fixture.client.sendPing(123L))
        verify(current.socket).send("""{"type":"np_ping","t":123}""")
    }

    @Test
    fun `oversized messages close the connection and retain only bounded diagnostic text`() {
        val fixture = Fixture()
        fixture.connect()
        val current = fixture.connections.single()
        current.callback.onMessage(current.socket, "x".repeat(2 * 1024 * 1024 + 1))
        assertEquals(listOf("protocol"), fixture.listener.events)
        assertEquals(256, fixture.listener.rawText?.length)
        verify(current.socket).close(1009, "message_too_large")
        assertFalse(fixture.client.sendPing(123L))
    }

    @Test
    fun `send operations reflect absent and rejecting transports`() {
        val fixture = Fixture()
        val event = ListenTogetherEvent(type = "PLAY")
        assertFalse(fixture.client.sendEvent(event))
        assertFalse(fixture.client.sendLegacyPing())
        fixture.connect()
        assertTrue(fixture.client.sendEvent(event))
        assertTrue(fixture.client.sendLegacyPing())
        val current = fixture.connections.single()
        verify(current.socket).send("""{"type":"ping"}""")
        `when`(current.socket.send(anyString())).thenReturn(false)
        assertFalse(fixture.client.sendEvent(event))
        assertFalse(fixture.client.sendLegacyPing())
        assertFalse(fixture.client.sendPing(123L))
    }

    @Test
    fun `failure diagnostics retain transport cause and optional HTTP status`() {
        val fixture = Fixture()
        fixture.connect()
        val current = fixture.connections.single()
        val cause = IllegalStateException("closed")
        current.callback.onFailure(current.socket, cause, current.response)
        assertTrue(fixture.listener.error?.message.orEmpty().contains("http=101"))
        assertTrue(fixture.listener.error?.cause === cause)
        current.callback.onFailure(current.socket, IllegalStateException(), null)
        assertEquals("IllegalStateException", fixture.listener.error?.message)
    }

    private class Fixture {
        val connections = mutableListOf<Connection>()
        val listener = RecordingListener()
        private val http = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                val socket = mock(WebSocket::class.java)
                `when`(socket.send(anyString())).thenReturn(true)
                connections += Connection(socket, listener, request)
                return socket
            }
        }
        val client = ListenTogetherWebSocketClient(http)
        fun connect() = client.connect("wss://worker.example/api/rooms/ABC/ws?token=test-token", listener)
    }

    private class Connection(val socket: WebSocket, val callback: WebSocketListener, request: Request) {
        val response: Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build()

        fun deliverAllCallbacks() {
            callback.onOpen(socket, response)
            callback.onMessage(socket, """{"type":"np_pong","t":42}""")
            callback.onClosed(socket, 1000, "closed")
            callback.onFailure(socket, IllegalStateException("closed"), null)
        }
    }

    private class RecordingListener : ListenTogetherWebSocketClient.Listener {
        val events = mutableListOf<String>()
        var rawText: String? = null
        var error: Throwable? = null
        override fun onOpen() { events += "open" }
        override fun onMessage(message: ListenTogetherSocketEnvelope) { events += message.type }
        override fun onClosed(code: Int, reason: String) { events += "closed:$code" }
        override fun onFailure(error: Throwable) {
            events += "failure"
            this.error = error
        }
        override fun onProtocolError(rawText: String, error: Throwable) {
            events += "protocol"
            this.rawText = rawText
        }
    }
}
