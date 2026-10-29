package moe.ouom.neriplayer.data.model.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

@Serializable data class LanPermissions(val next: Boolean = false, val playPause: Boolean = false, val seek: Boolean = false)
@Serializable data class LanMember(@SerialName("clientId") val id: String, @SerialName("username") val name: String, @SerialName("isAdmin") val host: Boolean = false, @SerialName("rtt") val rttMs: Double = 0.0, val volume: Float = 1f)
@Serializable data class LanPlayback(val track: ListenTogetherTrack? = null, val positionSeconds: Double = 0.0, val playing: Boolean = false, val executeAtMs: Double = 0.0, val revision: Long = 0)
@Serializable data class LanRoom(val id: String, val name: String, val members: List<LanMember> = emptyList(), val permissions: LanPermissions = LanPermissions(), val volume: Float = 1f, val playback: LanPlayback = LanPlayback())
@Serializable data class LanAction(val type: String, val audioSource: String, val trackTimeSeconds: Double, val track: ListenTogetherTrack? = null)

@Serializable data class LanSource(val url: String)
@Serializable data class LanRoomEvent(val type: String, val audioSourceToPlay: LanSource? = null,
    val clients: List<LanMember> = emptyList(), val room: LanRoom? = null, val track: ListenTogetherTrack? = null,
    val trackTimeSeconds: Double? = null, val loadGeneration: Long? = null, val revision: Long? = null, val sourceUrls: List<String> = emptyList())

/** LAN extension envelope. Credentials never appear in LanRoom/UI state. */
@Serializable data class LanMessage(
    val type: String,
    val protocolVersion: Int = 2,
    val roomId: String? = null,
    val name: String? = null,
    val hostToken: String? = null,
    val clientId: String? = null,
    val room: LanRoom? = null,
    val event: LanRoomEvent? = null,
    val source: LanSource? = null,
    val sourceUrls: List<String> = emptyList(),
    val contentDigest: String? = null,
    val scheduledAction: LanAction? = null,
    val serverTimeToExecute: Double? = null,
    val revision: Long? = null,
    val track: ListenTogetherTrack? = null,
    val audioSource: String? = null,
    val trackTimeSeconds: Double? = null,
    val intent: String? = null,
    val loadGeneration: Long? = null,
    val permissions: LanPermissions? = null,
    val volume: Float? = null,
    val code: String? = null,
    val t0: Double? = null, val t1: Double? = null, val t2: Double? = null,
    val probeGroupId: Int? = null, val probeGroupIndex: Int? = null,
    val clientRTT: Double? = null, val clientCompensationMs: Double? = null, val clientNudgeMs: Double? = null
)

data class LanDiscoveredRoom(val key: String, val name: String, val address: String, val port: Int)
data class LanSessionState(
    val phase: String = "IDLE", val room: LanRoom? = null, val selfId: String? = null,
    val hosting: Boolean = false, val address: String = "", val port: Int = 0,
    val synced: Boolean = false, val rttMs: Double = 0.0, val nudgeMs: Int = 0,
    val error: String? = null, val preparing: Boolean = false
) { val active: Boolean get() = phase != "IDLE" }
