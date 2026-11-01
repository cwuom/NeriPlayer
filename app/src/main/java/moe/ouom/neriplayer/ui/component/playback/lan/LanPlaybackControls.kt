package moe.ouom.neriplayer.ui.component.playback.lan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.lan.LanPermissions
import moe.ouom.neriplayer.data.model.lan.LanSessionState

/** Keep the UI closed while joining/reconnecting, until the authoritative room arrives. */
internal fun lanPlaybackControls(state: LanSessionState): LanPermissions = when {
    !state.active -> LanPermissions(true, true, true)
    !state.synced || state.room == null -> LanPermissions()
    state.hosting -> LanPermissions(true, true, true)
    else -> state.room?.permissions ?: LanPermissions()
}

@Composable
internal fun rememberLanPlaybackControls(): LanPermissions {
    // AppContainer.initialize 之前（如纯 UI 测试）没有 LAN 会话，直接返回全允许的默认态
    if (!AppContainer.isInitialized()) return lanPlaybackControls(LanSessionState())
    val state by AppContainer.lanListenTogether.state.collectAsStateWithLifecycle()
    return lanPlaybackControls(state)
}
