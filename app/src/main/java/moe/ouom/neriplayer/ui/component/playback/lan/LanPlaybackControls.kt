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
    val state by AppContainer.lanListenTogether.state.collectAsStateWithLifecycle()
    return lanPlaybackControls(state)
}
