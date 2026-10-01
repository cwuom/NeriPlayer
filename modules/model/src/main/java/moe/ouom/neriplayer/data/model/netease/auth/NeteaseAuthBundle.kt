package moe.ouom.neriplayer.data.model.netease.auth

val NETEASE_LOGIN_COOKIE_KEYS = listOf(
    "MUSIC_U"
)

data class NeteaseAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean {
        return NETEASE_LOGIN_COOKIE_KEYS.any { key -> !cookies[key].isNullOrBlank() }
    }

    fun normalized(savedAt: Long = this.savedAt): NeteaseAuthBundle {
        return copy(
            cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
            savedAt = savedAt
        )
    }

    companion object
}
