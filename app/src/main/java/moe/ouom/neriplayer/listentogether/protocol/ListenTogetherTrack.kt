package moe.ouom.neriplayer.listentogether.protocol

import kotlinx.serialization.Serializable

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
