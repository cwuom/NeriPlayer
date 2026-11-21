package moe.ouom.neriplayer.data.model.lan

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

const val LAN_PROTOCOL_VERSION = 4

@Serializable(with = LanPermissionsSerializer::class)
data class LanPermissions(
    val playPause: Boolean = true,
    val seek: Boolean = true,
    val volume: Boolean = false
) {
    fun allowsMemberDeviceVolume(selfId: String?, target: LanMember): Boolean =
        selfId != null && (target.id == selfId || (volume && target.host))
}

internal object LanPermissionsSerializer : KSerializer<LanPermissions> {
    @Serializable
    private data class WirePermissions(
        val next: Boolean = true,
        val playPause: Boolean = true,
        val seek: Boolean = true,
        val volume: Boolean = false
    )

    private val wire = WirePermissions.serializer()
    override val descriptor = wire.descriptor

    override fun serialize(encoder: Encoder, value: LanPermissions) {
        val flags = WirePermissions(next = value.seek, playPause = value.playPause, seek = value.seek, volume = value.volume)
        encoder.encodeSerializableValue(wire, flags)
    }

    override fun deserialize(decoder: Decoder): LanPermissions {
        val flags = decoder.decodeSerializableValue(wire)
        // 旧版房主可以分别关闭切歌和拖动进度，合并时保留任一限制
        return LanPermissions(playPause = flags.playPause, seek = flags.next && flags.seek, volume = flags.volume)
    }
}

@Serializable data class LanMember(@SerialName("clientId") val id: String, @SerialName("username") val name: String, @SerialName("isAdmin") val host: Boolean = false, @SerialName("rtt") val rttMs: Double = 0.0, val volume: Float = 1f)
@Serializable data class LanPlayback(val track: ListenTogetherTrack? = null, val positionSeconds: Double = 0.0, val playing: Boolean = false, val executeAtMs: Double = 0.0, val revision: Long = 0, val preparing: Boolean = false)
@Serializable data class LanRoom(val id: String, val name: String, val members: List<LanMember> = emptyList(), val permissions: LanPermissions = LanPermissions(), val volume: Float = 1f, val playback: LanPlayback = LanPlayback())
@Serializable data class LanAction(val type: String, val audioSource: String, val trackTimeSeconds: Double, val track: ListenTogetherTrack? = null)

@Serializable data class LanRoomEvent(val type: String,
    val clients: List<LanMember> = emptyList(), val room: LanRoom? = null, val track: ListenTogetherTrack? = null,
    val trackTimeSeconds: Double? = null, val loadGeneration: Long? = null, val revision: Long? = null,
    val streamResource: LanStreamResource? = null)

/** LAN extension envelope. Credentials never appear in LanRoom/UI state. */
@Serializable data class LanMessage(
    val type: String,
    // v2 omitted its default from the wire, so missing HELLO/WELCOME versions still decode as v2
    val protocolVersion: Int = 2,
    val roomId: String? = null,
    val name: String? = null,
    val hostToken: String? = null,
    val clientId: String? = null,
    val room: LanRoom? = null,
    val event: LanRoomEvent? = null,
    val streamResource: LanStreamResource? = null,
    val mediaId: String? = null,
    val mediaPort: Int? = null,
    val mediaToken: String? = null,
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
    val presentationResource: LanFileResource? = null,
    val t0: Double? = null, val t1: Double? = null, val t2: Double? = null,
    val probeGroupId: Int? = null, val probeGroupIndex: Int? = null,
    val clientRTT: Double? = null, val clientCompensationMs: Double? = null, val clientNudgeMs: Double? = null
)

data class LanDiscoveredRoom(val key: String, val name: String, val address: String, val port: Int)
data class LanSessionState(
    val phase: String = "IDLE", val room: LanRoom? = null, val selfId: String? = null,
    val hosting: Boolean = false, val address: String = "", val port: Int = 0,
    val synced: Boolean = false, val rttMs: Double = 0.0, val nudgeMs: Int = 0,
    val error: String? = null, val preparing: Boolean = false,
    val receivedBytes: Long = 0, val totalBytes: Long = 0, val audioSuspended: Boolean = false
) { val active: Boolean get() = phase != "IDLE" }
