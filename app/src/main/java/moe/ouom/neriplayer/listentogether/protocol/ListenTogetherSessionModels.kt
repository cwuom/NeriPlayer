package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ListenTogetherConnectionState {
    @SerialName("disconnected")
    DISCONNECTED,
    @SerialName("connecting")
    CONNECTING,
    @SerialName("connected")
    CONNECTED
}

data class ListenTogetherSessionState(
    val baseUrl: String? = null,
    val roomId: String? = null,
    val userUuid: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val token: String? = null,
    val wsUrl: String? = null,
    val connectionState: ListenTogetherConnectionState = ListenTogetherConnectionState.DISCONNECTED,
    val lastError: String? = null,
    val expectedPositionMs: Long? = null,
    val roomNotice: String? = null
)
