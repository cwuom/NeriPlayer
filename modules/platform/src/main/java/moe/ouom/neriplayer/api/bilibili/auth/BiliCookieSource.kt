package moe.ouom.neriplayer.api.bilibili.auth

fun interface BiliCookieSource {
    fun getCookiesOnce(): Map<String, String>
}
