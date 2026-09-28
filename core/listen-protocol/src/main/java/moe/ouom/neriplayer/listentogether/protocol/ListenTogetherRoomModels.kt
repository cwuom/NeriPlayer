package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ListenTogetherRoomSettings(
    val allowMemberControl: Boolean = true,
    val autoPauseOnMemberChange: Boolean = true,
    val shareAudioLinks: Boolean = true
)

@Serializable
data class ListenTogetherMember(
    val userUuid: String = "",
    val nickname: String = "",
    val userId: String? = null,
    val role: String,
    val joinedAt: Long
)

@Serializable
data class ListenTogetherPlaybackState(
    val state: String = "paused",
    val basePositionMs: Long = 0L,
    val baseTimestampMs: Long = 0L,
    val playbackRate: Double = 1.0,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null
)

@Serializable
data class ListenTogetherRoomState(
    val roomId: String,
    val version: Long,
    val schemaVersion: Int = 1,
    val controllerUserUuid: String? = null,
    val controllerUserId: String? = null,
    val controllerHeartbeatAt: Long? = null,
    val settings: ListenTogetherRoomSettings = ListenTogetherRoomSettings(),
    val members: List<ListenTogetherMember> = emptyList(),
    val queue: List<ListenTogetherTrack> = emptyList(),
    val currentIndex: Int = 0,
    val track: ListenTogetherTrack? = null,
    val playback: ListenTogetherPlaybackState = ListenTogetherPlaybackState(),
    val controllerOfflineSince: Long? = null,
    val roomStatus: String = "active",
    val closedReason: String? = null,
    val updatedAt: Long = 0L
)

object ListenTogetherRoomStatuses {
    const val ACTIVE = "active"
    const val CONTROLLER_OFFLINE = "controller_offline"
    const val CLOSED = "closed"
}
