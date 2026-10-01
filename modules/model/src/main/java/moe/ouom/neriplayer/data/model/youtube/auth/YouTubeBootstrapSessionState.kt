package moe.ouom.neriplayer.data.model.youtube.auth

data class YouTubeBootstrapSessionState(
    val origin: String = "",
    val loggedIn: Boolean = false,
    val sessionIndex: String = "",
    val delegatedSessionId: String = "",
    val userSessionId: String = ""
) {
    fun hasLiveSessionSignal(): Boolean {
        return loggedIn ||
            delegatedSessionId.isNotBlank() ||
            userSessionId.isNotBlank()
    }
}
