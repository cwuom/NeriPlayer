package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.SerializationException
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ListenTogetherSocketCodecTest {
    private val codec = ListenTogetherSocketCodec()

    @Test
    fun `socket envelope accepts future fields without losing identity`() {
        val envelope = codec.decodeEnvelope("""{"type":"np_pong","roomId":"ABC234","nowMs":42,"future":true}""")
        assertEquals("np_pong", envelope.type)
        assertEquals("ABC234", envelope.roomId)
        assertEquals(42L, envelope.nowMs)
    }

    @Test
    fun `socket codec rejects oversized message before decoding`() {
        val text = " ".repeat(LISTEN_TOGETHER_MAX_WS_MESSAGE_CHARS + 1)
        val error = assertThrows(ListenTogetherSocketMessageTooLargeException::class.java) { codec.decodeEnvelope(text) }
        assertEquals("WebSocket message too large: ${text.length} chars", error.message)
    }

    @Test
    fun `exact character boundary remains a valid message`() {
        val json = """{"type":"pong"}"""
        assertEquals("pong", codec.decodeEnvelope(json + " ".repeat(LISTEN_TOGETHER_MAX_WS_MESSAGE_CHARS - json.length)).type)
    }

    @Test
    fun `malformed envelope retains serialization failure`() {
        assertThrows(SerializationException::class.java) { codec.decodeEnvelope("{") }
    }

    @Test
    fun `event encoding retains defaults and omits nullable fields`() {
        val encoded = codec.encodeEvent(ListenTogetherEvent(type = "PLAY", eventId = "event"))
        val event = listenTogetherProtocolJson().decodeFromString<ListenTogetherEvent>(encoded)
        assertEquals("PLAY", event.type)
        assertEquals("event", event.eventId)
        assertFalse(encoded.contains(":null"))
    }

    @Test
    fun `ping encodings preserve worker wire format`() {
        assertEquals("""{"type":"np_ping","t":42}""", codec.encodePing(42L))
        assertEquals("""{"type":"ping"}""", codec.encodeLegacyPing())
    }
}
