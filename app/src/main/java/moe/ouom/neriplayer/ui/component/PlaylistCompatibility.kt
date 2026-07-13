package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.ui.component.playlist.playlistExportListHeight as newPlaylistExportListHeight

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
    moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet(
        title = title,
        playlists = playlists,
        selectedCount = selectedCount,
        onDismissRequest = onDismissRequest,
        onCreateAndExport = onCreateAndExport,
        onExportToPlaylist = onExportToPlaylist,
        createActionLabel = createActionLabel
    )
}

internal fun Modifier.playlistExportListHeight(): Modifier =
    newPlaylistExportListHeight()
