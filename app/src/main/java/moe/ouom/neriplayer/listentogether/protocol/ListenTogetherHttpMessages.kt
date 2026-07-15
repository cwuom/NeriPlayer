package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ListenTogetherInitialSnapshot(
    val queue: List<ListenTogetherTrack> = emptyList(),
    val currentIndex: Int = 0,
    val track: ListenTogetherTrack? = null,
    val settings: ListenTogetherRoomSettings = ListenTogetherRoomSettings(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false
)

@Serializable
data class ListenTogetherCreateRoomRequest(
    val userUuid: String,
    val nickname: String,
    val initialSnapshot: ListenTogetherInitialSnapshot
)

@Serializable
data class ListenTogetherJoinRoomRequest(
    val userUuid: String,
    val nickname: String
)

@Serializable
data class ListenTogetherRoomResponse(
    val ok: Boolean,
    val roomId: String? = null,
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val autoPauseOnJoin: Boolean = false,
    val token: String? = null,
    val state: ListenTogetherRoomState? = null,
    val wsUrl: String? = null,
    val error: String? = null
)

@Serializable
data class ListenTogetherStateResponse(
    val ok: Boolean,
    val state: ListenTogetherRoomState? = null,
    val expectedPositionMs: Long? = null,
    val serverNowMs: Long? = null,
    val autoPauseOnJoin: Boolean = false,
    val error: String? = null
)

@Serializable
data class ListenTogetherControlResponse(
    val ok: Boolean,
    val applied: ListenTogetherAppliedEvent? = null,
    val error: String? = null
)
