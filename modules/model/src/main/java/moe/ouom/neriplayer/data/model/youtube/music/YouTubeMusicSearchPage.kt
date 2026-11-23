package moe.ouom.neriplayer.data.model.youtube.music

data class YouTubeMusicSearchCursor(
    val token: String,
    val locale: YouTubeMusicRequestLocale,
    val visitedTokens: Set<String>
)

data class YouTubeMusicSearchPage<T>(
    val items: List<T>,
    val next: YouTubeMusicSearchCursor? = null
)
