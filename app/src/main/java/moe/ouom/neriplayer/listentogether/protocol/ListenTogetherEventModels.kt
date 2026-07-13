package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ListenTogetherCause(
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val eventId: String? = null,
    val type: String? = null
)

@Serializable
data class ListenTogetherAppliedEvent(
    val type: String,
    val roomId: String? = null,
    val version: Long? = null,
    val state: ListenTogetherRoomState? = null,
    val expectedPositionMs: Long? = null,
    val causedBy: ListenTogetherCause? = null
)

@Serializable
data class ListenTogetherEvent(
    val type: String,
    val eventId: String? = null,
    val clientTimeMs: Long? = null,
    val positionMs: Long? = null,
    val currentIndex: Int? = null,
    val nextIndex: Int? = null,
    val track: ListenTogetherTrack? = null,
    val queue: List<ListenTogetherTrack>? = null,
    val roomSettings: ListenTogetherRoomSettings? = null,
    val shouldPlay: Boolean? = null,
    val state: String? = null,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    val requestTrackStableKey: String? = null,
    val finishedTrackStableKey: String? = null
)
