package moe.ouom.neriplayer.platform.bilibili.api.auth

fun interface BiliCookieSource {
    fun getCookiesOnce(): Map<String, String>
}
