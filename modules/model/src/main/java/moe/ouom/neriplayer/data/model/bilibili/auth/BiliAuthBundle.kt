package moe.ouom.neriplayer.data.model.bilibili.auth

data class BiliAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean {
        return !cookies["SESSDATA"].isNullOrBlank()
    }

    fun normalized(savedAt: Long = this.savedAt): BiliAuthBundle {
        return copy(
            cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
            savedAt = savedAt
        )
    }

    /** 补写 DedeUserID, 其它 Cookie 和保存时间保持不变 */
    fun withUserMid(mid: Long): BiliAuthBundle {
        return copy(cookies = cookies + ("DedeUserID" to mid.toString()))
    }

    companion object
}
