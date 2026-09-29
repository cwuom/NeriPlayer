package moe.ouom.neriplayer.api.netease.model.auth

data class NeteaseQrLoginCheckResult(
    val code: Int,
    val message: String,
    val cookies: Map<String, String> = emptyMap()
) {
    val isConfirmed: Boolean
        get() = code == 803
}
