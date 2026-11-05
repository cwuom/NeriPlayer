package moe.ouom.neriplayer.ui.screen.server.album

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.rememberCoroutineScope
import android.content.ClipData
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.playlist.playlistModernListSecondaryContentColor
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
internal fun ServerAlbumSongMenu(
    song: SongItem,
    favorite: Boolean,
    favoriteBusy: Boolean,
    downloaded: Boolean,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit = { PlayerManager.addToQueueNext(song) },
    onAddToQueue: () -> Unit = { PlayerManager.addToQueueEnd(song) }
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert, stringResource(CoreCommonR.string.cd_more_actions),
                tint = playlistModernListSecondaryContentColor()
            )
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.local_playlist_play_next)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null) },
                onClick = { expanded = false; onPlayNext() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.playlist_add_to_end)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                onClick = { expanded = false; onAddToQueue() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (favorite) CoreCommonR.string.favorite_remove else CoreCommonR.string.favorite_add)) },
                leadingIcon = { Icon(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null) },
                enabled = !favoriteBusy,
                onClick = { expanded = false; onFavorite() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.playlist_add_to)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                onClick = { expanded = false; onAddToPlaylist() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (downloaded) CoreCommonR.string.download_manager else CoreCommonR.string.download_to_local)) },
                leadingIcon = { Icon(if (downloaded) Icons.Outlined.DownloadDone else Icons.Outlined.Download, null) },
                onClick = { expanded = false; onDownload() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.action_copy_song_info)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = {
                    expanded = false
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", "${song.displayName()}-${song.displayArtist()}")))
                    }
                }
            )
        }
    }
}

@Composable
internal fun ServerAlbumCollectionMenu(
    canUseSongs: Boolean,
    refreshing: Boolean,
    onDownloadAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRefresh: () -> Unit,
    onDownloads: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HapticIconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(CoreCommonR.string.cd_more_actions))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.download_to_local)) },
                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                enabled = canUseSongs,
                onClick = { expanded = false; onDownloadAll() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.playlist_add_to)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                enabled = canUseSongs,
                onClick = { expanded = false; onAddToPlaylist() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.server_refresh)) },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                enabled = !refreshing,
                onClick = { expanded = false; onRefresh() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.download_manager)) },
                leadingIcon = { Icon(Icons.Outlined.DownloadDone, null) },
                onClick = { expanded = false; onDownloads() }
            )
        }
    }
}
