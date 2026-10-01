package moe.ouom.neriplayer.data.model.netease.auth

data class NeteaseCookieValidationResult(
    val sanitizedCookies: Map<String, String> = emptyMap(),
    val rejectedKeys: List<String> = emptyList()
) {
    val hasLoginCookie: Boolean
        get() = NETEASE_LOGIN_COOKIE_KEYS.any { key -> !sanitizedCookies[key].isNullOrBlank() }

    val isAccepted: Boolean
        get() = sanitizedCookies.isNotEmpty() && hasLoginCookie
}
