package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ListenTogetherSocketEnvelope(
    val type: String,
    val sessionId: String? = null,
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val autoPauseOnJoin: Boolean = false,
    val state: ListenTogetherRoomState? = null,
    val expectedPositionMs: Long? = null,
    val nowMs: Long? = null,
    val ok: Boolean? = null,
    val result: ListenTogetherControlResponse? = null,
    val message: String? = null,
    val roomId: String? = null,
    val version: Long? = null,
    val causedBy: ListenTogetherCause? = null,
    val track: ListenTogetherTrack? = null,
    val queue: List<ListenTogetherTrack>? = null,
    val positionMs: Long? = null,
    val currentIndex: Int? = null,
    val requestTrackStableKey: String? = null,
    val shouldPlay: Boolean? = null,
    val stateName: String? = null,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    val clientTimeMs: Long? = null,
    val requestSequence: Long? = null
)
