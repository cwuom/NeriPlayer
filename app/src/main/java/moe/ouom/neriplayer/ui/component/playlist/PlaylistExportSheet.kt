package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

private class PendingPlaylistExport(
    val targetName: String,
    val onConfirm: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistExportSheet(
    title: String,
    playlists: List<LocalPlaylist>,
    selectedCount: Int,
    onDismissRequest: () -> Unit,
    onCreateAndExport: (String) -> Unit,
    onExportToPlaylist: (LocalPlaylist) -> Unit,
    createActionLabel: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<PendingPlaylistExport?>(null) }
    var visibleExportConfirmation by remember { mutableStateOf<PendingPlaylistExport?>(null) }
    val resolvedCreateActionLabel =
        createActionLabel ?: stringResource(R.string.playlist_create_and_export)

    fun clearPendingExport() {
        pendingExport = null
        visibleExportConfirmation = null
    }

    fun dismissAnimated() {
        clearPendingExport()
        scope.launch {
            runCatching { sheetState.hide() }
            onDismissRequest()
        }
    }

    fun runThenDismiss(action: () -> Unit) {
        action()
        dismissAnimated()
    }

    fun requestExportConfirmation(targetName: String, action: () -> Unit) {
        visibleExportConfirmation = null
        pendingExport = PendingPlaylistExport(targetName) {
            runThenDismiss(action)
        }
    }

    LaunchedEffect(pendingExport) {
        val export = pendingExport ?: return@LaunchedEffect
        withFrameNanos { }
        if (pendingExport === export) {
            visibleExportConfirmation = export
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissAnimated() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        scrimColor = Color.Black.copy(alpha = 0.46f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .bottomSheetScrollGuard()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.common_selected_count,
                        selectedCount,
                        selectedCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                MiuixSettingsTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.playlist_create_name)) },
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    MiuixSettingsButton(
                        enabled = newName.isNotBlank() && selectedCount > 0,
                        onClick = {
                            val name = newName.trim()
                            if (name.isBlank()) return@MiuixSettingsButton
                            requestExportConfirmation(name) { onCreateAndExport(name) }
                        }
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(resolvedCreateActionLabel)
                    }
                }

                HorizontalDivider(
                    thickness = DividerDefaults.Thickness,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.playlistExportListHeight()) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistExportRow(
                        playlist = playlist,
                        onClick = {
                            context.performHapticFeedback()
                            requestExportConfirmation(playlist.name) {
                                onExportToPlaylist(playlist)
                            }
                        }
                    )
                }
            }
        }
    }

    visibleExportConfirmation?.let { export ->
        AlertDialog(
            onDismissRequest = {
                clearPendingExport()
            },
            title = { Text(stringResource(R.string.playlist_batch_export_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.playlist_batch_export_confirm_message,
                        selectedCount,
                        selectedCount,
                        export.targetName
                    )
                )
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        val action = export.onConfirm
                        clearPendingExport()
                        action()
                    }
                ) {
                    Text(stringResource(R.string.playlist_batch_export_confirm_button))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = {
                        clearPendingExport()
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun PlaylistExportRow(
    playlist: LocalPlaylist,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = pluralStringResource(
                R.plurals.explore_song_count,
                playlist.songs.size,
                playlist.songs.size
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
