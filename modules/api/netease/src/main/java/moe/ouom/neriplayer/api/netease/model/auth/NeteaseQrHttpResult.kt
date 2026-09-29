package moe.ouom.neriplayer.api.netease.model.auth

internal data class NeteaseQrHttpResult(
    val text: String,
    val refreshToken: String = ""
)
