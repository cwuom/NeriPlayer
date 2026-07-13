package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import moe.ouom.neriplayer.data.model.SongItem

@Composable
fun LocalSongDetailsDialog(
    song: SongItem,
    onDismiss: () -> Unit
) {
    moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog(
        song = song,
        onDismiss = onDismiss
    )
}

@Composable
fun LocalSongSyncConfirmDialog(
    actionLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog(
        actionLabel = actionLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

