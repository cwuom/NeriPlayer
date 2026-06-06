package moe.ouom.neriplayer.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

object ListenTogetherChannels {
    const val NETEASE = "netease"
    const val BILIBILI = "bilibili"
    const val YOUTUBE_MUSIC = "youtubeMusic"
    const val LOCAL = "local"
}

@Serializable
data class ListenTogetherTrack(
    val stableKey: String,
    val channelId: String,
    val audioId: String,
    val subAudioId: String? = null,
    val playlistContextId: String? = null,
    val mediaUri: String? = null,
    val streamUrl: String? = null,
    val name: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val coverUrl: String? = null
)

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
    val playbackRate: Double = 1.0
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

@Serializable
data class ListenTogetherCause(
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val eventId: String? = null,
    val type: String? = null
)

@Serializable
data class ListenTogetherInitialSnapshot(
    val queue: List<ListenTogetherTrack> = emptyList(),
    val currentIndex: Int = 0,
    val track: ListenTogetherTrack? = null,
    val settings: ListenTogetherRoomSettings = ListenTogetherRoomSettings(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L
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
    val autoPauseOnJoin: Boolean = false,
    val error: String? = null
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
data class ListenTogetherControlResponse(
    val ok: Boolean,
    val applied: ListenTogetherAppliedEvent? = null,
    val error: String? = null
)

@Serializable
data class ListenTogetherEvent(
    val type: String,
    val eventId: String? = null,
    val clientTimeMs: Long? = null,
    val positionMs: Long? = null,
    val currentIndex: Int? = null,
    val track: ListenTogetherTrack? = null,
    val queue: List<ListenTogetherTrack>? = null,
    val roomSettings: ListenTogetherRoomSettings? = null,
    val shouldPlay: Boolean? = null,
    val state: String? = null,
    val requestTrackStableKey: String? = null
)

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
    val clientTimeMs: Long? = null,
    val requestSequence: Long? = null
)

object ListenTogetherRoomStatuses {
    const val ACTIVE = "active"
    const val CONTROLLER_OFFLINE = "controller_offline"
    const val CLOSED = "closed"
}

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

fun buildListenTogetherWsUrl(baseUrl: String, roomId: String, token: String): String {
    val normalizedBase = baseUrl.normalizeBaseUrl()
    val url = normalizedBase.toHttpUrl().newBuilder()
        .encodedPath("/api/rooms/$roomId/ws")
        .setQueryParameter("token", token)
        .build()
    return url.newBuilder()
        .scheme(if (url.isHttps) "wss" else "ws")
        .build()
        .toString()
}
