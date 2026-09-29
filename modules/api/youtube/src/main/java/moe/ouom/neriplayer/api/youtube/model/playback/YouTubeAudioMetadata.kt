package moe.ouom.neriplayer.api.youtube.model.playback

data class YouTubeAudioMetadata(
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val contentLength: Long? = null
)
