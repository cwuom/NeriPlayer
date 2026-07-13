package moe.ouom.neriplayer.core.player.service

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

internal fun statusBarLyricNotificationStateFlow(
    enabledFlow: Flow<Boolean>,
    lineFlow: Flow<String?>,
): Flow<StatusBarLyricNotificationState> {
    return combine(enabledFlow, lineFlow, ::resolveStatusBarLyricNotificationState)
        .distinctUntilChanged()
}
