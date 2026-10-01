package moe.ouom.neriplayer.listentogether.protocol

import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope

const val LISTEN_TOGETHER_MAX_WS_MESSAGE_CHARS = 2 * 1024 * 1024

class ListenTogetherSocketMessageTooLargeException(length: Int) :
    IllegalArgumentException("WebSocket message too large: $length chars")

class ListenTogetherSocketCodec {
    private val json = listenTogetherProtocolJson()

    fun decodeEnvelope(text: String): ListenTogetherSocketEnvelope {
        if (text.length > LISTEN_TOGETHER_MAX_WS_MESSAGE_CHARS) {
            throw ListenTogetherSocketMessageTooLargeException(text.length)
        }
        return json.decodeFromString(text)
    }

    fun encodeEvent(event: ListenTogetherEvent): String = json.encodeToString(event)

    fun encodePing(sentAtElapsedMs: Long): String = """{"type":"np_ping","t":$sentAtElapsedMs}"""

    fun encodeLegacyPing(): String = """{"type":"ping"}"""
}
