package moe.ouom.neriplayer.data.model.settings.navigation

/** 未设置启动页或读取失败时使用的兜底路由, 与设置项 defaultString 保持一致. */
const val DEFAULT_START_DESTINATION = "home"

/**
 * 启动页偏好的同步快照.
 *
 * DataStore 的首次发射是异步的, 只靠它会让首帧落在兜底路由再补一次跳转;
 * 这份快照让首帧就能拿到用户已确定的选择.
 */
data class StartDestinationSnapshot(
    val startDestination: String = DEFAULT_START_DESTINATION
)
