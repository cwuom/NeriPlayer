package moe.ouom.neriplayer.api.youtube.model.playback

import okhttp3.Request

data class PreparedYouTubePlayerRequest(
    val request: Request,
    val clientVersion: String,
    val webRemixOriginalUrl: String,
    val webRemixWatchUrl: String
)
