package moe.ouom.neriplayer.core.player.service.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class StatusBarLyricNotificationState(
    val enabled: Boolean,
    val line: String?,
) {
    val hasTicker: Boolean
        get() = enabled && line != null
}

internal fun resolveStatusBarLyricNotificationState(
    enabled: Boolean,
    line: String?,
): StatusBarLyricNotificationState {
    val normalizedLine = line?.takeIf { it.isNotBlank() && it != "null" }
    return StatusBarLyricNotificationState(
        enabled = enabled,
        line = normalizedLine.takeIf { enabled },
    )
}

/**
 * @param deviceSupported 设备是否支持状态栏歌词。不支持时开关一律视为关闭，
 *   避免用户从备份恢复出 `status_bar_lyrics_enabled = true` 之后，
 *   通知仍按歌词行重建（内容不会变，纯属空转）。
 */
internal fun statusBarLyricNotificationStateFlow(
    enabledFlow: Flow<Boolean>,
    lineFlow: Flow<String?>,
    deviceSupported: Boolean,
): Flow<StatusBarLyricNotificationState> {
    return combine(enabledFlow, lineFlow) { enabled, line ->
        resolveStatusBarLyricNotificationState(enabled && deviceSupported, line)
    }.distinctUntilChanged()
}
