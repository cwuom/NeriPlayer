package moe.ouom.neriplayer.api.bilibili.model.auth

data class BiliQrLoginCheckResult(
    val code: Int,
    val message: String,
    val cookies: Map<String, String> = emptyMap()
) {
    val isConfirmed: Boolean
        get() = code == 0
}
