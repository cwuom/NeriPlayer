package moe.ouom.neriplayer.api.youtube.model.auth

data class YouTubeAuthAutoRefreshResult(
    val attempted: Boolean = false,
    val refreshed: Boolean = false,
    val authChanged: Boolean = false,
    val reason: String = ""
)
