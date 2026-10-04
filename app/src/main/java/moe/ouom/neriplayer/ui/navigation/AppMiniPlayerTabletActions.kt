package moe.ouom.neriplayer.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.playback.MiniPlayerTabletControls
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherRoomPanel
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingQueueSheet
import moe.ouom.neriplayer.ui.screen.nowplaying.VolumeControlSheetContent
import moe.ouom.neriplayer.ui.screen.playback.resolveListenTogetherProgressSeekEnabled

private enum class MiniPlayerSheet { Volume, ListenTogether, Queue }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMiniPlayerTabletActions(
    visible: Boolean,
    offlineMode: Boolean,
    onOpenCurrentPlaybackSource: (() -> Unit)?
): MiniPlayerTabletControls {
    val position by PlayerManager.playbackPositionFlow.collectAsStateWithLifecycle()
    val duration by PlayerManager.playbackDurationFlow.collectAsStateWithLifecycle()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val shuffle by PlayerManager.shuffleModeFlow.collectAsStateWithLifecycle()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsStateWithLifecycle()
    val sessionManager = remember { AppContainer.listenTogetherSessionManager }
    val session by sessionManager.sessionState.collectAsStateWithLifecycle()
    val room by sessionManager.roomState.collectAsStateWithLifecycle()
    val seekEnabled = resolveListenTogetherProgressSeekEnabled(
        sessionUserUuid = session.userUuid,
        fallbackRole = session.role,
        roomId = session.roomId,
        controllerUserUuid = room?.controllerUserUuid,
        controllerUserId = room?.controllerUserId,
        allowMemberControl = room?.settings?.allowMemberControl
    )
    var sheet by remember { mutableStateOf<MiniPlayerSheet?>(null) }
    LaunchedEffect(visible) {
        if (!visible) sheet = null
    }
    if (visible) {
        when (sheet) {
            MiniPlayerSheet.Volume -> DensityScaledModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                sheetGesturesEnabled = false
            ) { VolumeControlSheetContent() }
            MiniPlayerSheet.ListenTogether -> DensityScaledModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                sheetGesturesEnabled = false
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .bottomSheetScrollGuard()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) { ListenTogetherRoomPanel(Modifier.fillMaxWidth()) }
            }
            MiniPlayerSheet.Queue -> MiniPlayerQueueSheet(
                seekEnabled = seekEnabled,
                offlineMode = offlineMode,
                onDismissRequest = { sheet = null },
                onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
            )
            null -> Unit
        }
    }
    val trackKey = currentSong?.stableKey()
    return MiniPlayerTabletControls(
        trackKey = trackKey,
        positionMs = position,
        durationMs = duration,
        seekEnabled = seekEnabled,
        shuffleEnabled = shuffle,
        repeatMode = repeatMode,
        onSeek = { target ->
            // 拖动中切歌时不把上一首的进度写到新歌曲
            if (trackKey != null && PlayerManager.currentSongFlow.value?.stableKey() == trackKey) {
                PlayerManager.seekTo(target)
            }
        },
        onShuffle = { PlayerManager.setShuffle(!PlayerManager.shuffleModeFlow.value) },
        onRepeat = { PlayerManager.cycleRepeatMode() },
        onVolume = { sheet = MiniPlayerSheet.Volume },
        onListenTogether = { sheet = MiniPlayerSheet.ListenTogether },
        onQueue = { sheet = MiniPlayerSheet.Queue }
    )
}

@Composable
private fun MiniPlayerQueueSheet(
    seekEnabled: Boolean,
    offlineMode: Boolean,
    onDismissRequest: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)?
) {
    val queue by PlayerManager.currentQueueFlow.collectAsStateWithLifecycle()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val shuffle by PlayerManager.shuffleModeFlow.collectAsStateWithLifecycle()
    val revision by PlayerManager.currentQueueDisplayRevisionFlow.collectAsStateWithLifecycle()
    val snapshot = remember(queue, currentSong, shuffle, revision) {
        PlayerManager.currentQueueDisplaySnapshot()
    }
    NowPlayingQueueSheet(
        displayedQueueItems = snapshot.items,
        currentIndexInDisplay = snapshot.currentDisplayIndex,
        offlineMode = offlineMode,
        allowQueueReorder = seekEnabled,
        onDismissRequest = onDismissRequest,
        onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
    )
}
