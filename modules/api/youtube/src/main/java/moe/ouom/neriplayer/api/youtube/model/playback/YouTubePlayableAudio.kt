package moe.ouom.neriplayer.api.youtube.model.playback

enum class YouTubePlayableStreamType {
    DIRECT,
    HLS
}

data class YouTubePlayableAudio(
    val url: String,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null,
    val streamType: YouTubePlayableStreamType = YouTubePlayableStreamType.DIRECT,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null
)
