package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.playback.ListenTogetherPanel
import moe.ouom.neriplayer.ui.component.playback.ListenTogetherPageTransition
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard

@Composable
internal fun rememberPlaybackRoomActive(): Boolean {
    if (!AppContainer.isInitialized()) return false
    val lan = AppContainer.lanListenTogether.state.collectAsStateWithLifecycle()
    val cloud = AppContainer.listenTogetherSessionManager.sessionState.collectAsStateWithLifecycle()
    return remember(lan, cloud) {
        derivedStateOf { lan.value.active || !cloud.value.roomId.isNullOrBlank() }
    }.value
}

@Composable
internal fun PlaybackOutputIcon(
    roomActive: Boolean,
    device: Pair<String, ImageVector>,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = if (roomActive) Icons.Outlined.WifiTethering else device.second,
        contentDescription = if (roomActive) stringResource(CoreR.string.lan_manage)
            else stringResource(CoreR.string.playback_output_and_listen_together, device.first),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackOutputSheet(onDismiss: () -> Unit) {
    DensityScaledModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false
    ) {
        PlaybackOutputSheetContent(
            roomActive = rememberPlaybackRoomActive(),
            volumeContent = { VolumeControlSheetContent() },
            roomContent = { ListenTogetherPanel(Modifier.padding(horizontal = 16.dp), onDismiss = onDismiss) }
        )
    }
}

@Composable
internal fun PlaybackOutputSheetContent(
    roomActive: Boolean,
    volumeContent: @Composable () -> Unit,
    roomContent: @Composable () -> Unit
) {
    var showRoom by rememberSaveable(roomActive) { mutableStateOf(roomActive) }
    ListenTogetherPageTransition(
        showDetails = showRoom,
        pageLayout = { body ->
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                    .bottomSheetScrollGuard().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)
            ) {
                volumeContent()
                body()
            }
        }
    ) { managing ->
        if (managing) {
            roomContent()
        } else {
            OutlinedButton(
                onClick = { showRoom = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            ) {
                Text(stringResource(CoreR.string.lan_manage))
            }
        }
    }
}
