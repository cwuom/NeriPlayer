package moe.ouom.neriplayer.data.youtube.model.auth

/** 服务端没告诉我们周期时的兜底, 与它自己声明的 600 秒一致 */
internal const val ROTATION_DEFAULT_INTERVAL_MS = 600_000L

data class YouTubeCookieRotationState(
    val lastRotatedAtMs: Long = 0L,
    val rotationIntervalMs: Long = ROTATION_DEFAULT_INTERVAL_MS,
    val consecutiveRejections: Int = 0
)
