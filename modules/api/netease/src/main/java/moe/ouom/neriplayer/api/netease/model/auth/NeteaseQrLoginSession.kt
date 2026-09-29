package moe.ouom.neriplayer.api.netease.model.auth

data class NeteaseQrLoginSession(
    val key: String,
    val chainId: String,
    val ydDeviceToken: String,
    val seedCookieKeys: Set<String>,
    val qrContent: String
)
