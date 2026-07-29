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
    // streamUrl remains the first candidate for older clients and servers
    val streamUrl: String? = null,
    val streamUrls: List<String> = emptyList(),
    val name: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val coverUrl: String? = null
)
