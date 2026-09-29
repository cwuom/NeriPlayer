package moe.ouom.neriplayer.api.netease.model.auth

internal data class NeteaseYdDeviceSnapshot(
    val token: String = "",
    val sDeviceId: String = "",
    val cookies: Map<String, String> = emptyMap()
)
