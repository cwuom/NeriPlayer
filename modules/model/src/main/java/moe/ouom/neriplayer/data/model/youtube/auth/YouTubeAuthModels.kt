package moe.ouom.neriplayer.data.model.youtube.auth

const val YOUTUBE_MUSIC_ORIGIN: String = "https://music.youtube.com"

data class YouTubeAuthBundle(
    val cookieHeader: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val authorization: String = "",
    val xGoogAuthUser: String = "",
    val origin: String = YOUTUBE_MUSIC_ORIGIN,
    val userAgent: String = "",
    val savedAt: Long = 0L
) {
    companion object
}

enum class YouTubeAuthState {
    Missing,
    Valid
}

data class YouTubeAuthHealth(
    val state: YouTubeAuthState = YouTubeAuthState.Missing,
    val savedAt: Long = 0L,
    val checkedAt: Long = 0L,
    val ageMs: Long = Long.MAX_VALUE,
    val loginCookieKeys: List<String> = emptyList(),
    val activeCookieKeys: List<String> = emptyList()
) {
    val shouldPromptRelogin: Boolean
        get() = false
}
