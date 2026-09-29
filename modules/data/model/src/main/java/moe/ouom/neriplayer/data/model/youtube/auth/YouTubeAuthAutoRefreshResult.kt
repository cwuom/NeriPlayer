package moe.ouom.neriplayer.data.model.youtube.auth

data class YouTubeAuthAutoRefreshResult(
    val attempted: Boolean = false,
    val refreshed: Boolean = false,
    val authChanged: Boolean = false,
    val reason: String = ""
)
