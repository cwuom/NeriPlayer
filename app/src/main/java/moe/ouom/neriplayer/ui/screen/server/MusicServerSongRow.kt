package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.playlist.PlayingIndicator
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.util.format.formatDuration

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MusicServerSongRow(
    song: SongItem,
    index: Int,
    current: Boolean,
    playing: Boolean,
    favorite: Boolean,
    favoriteBusy: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    val cover = rememberSongDisplayCoverUrl(song, resolveLocalFallback = false)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = if (selected || current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
            else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .semantics { this.selected = selected || current }
                .combinedClickable(onClick = { if (selectionMode) onSelect() else onClick() }, onLongClick = onSelect)
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onSelect() })
            else Text(index.toString(), Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.size(48.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                if (cover.isNullOrBlank()) Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.MusicNote, null)
                } else AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                Text(song.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(song.displayArtist(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (favorite) Icon(Icons.Filled.Favorite, stringResource(CoreCommonR.string.favorite_my_music),
                    Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            }
            if (current) PlayingIndicator(color = MaterialTheme.colorScheme.primary, animate = playing)
            else if (song.durationMs > 0) Text(formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!selectionMode) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(CoreCommonR.string.cd_more_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreCommonR.string.local_playlist_play_next)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null) },
                        onClick = { menuOpen = false; onPlayNext() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreCommonR.string.playlist_add_to_end)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                        onClick = { menuOpen = false; onAddToQueue() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (favorite) CoreCommonR.string.favorite_remove else CoreCommonR.string.favorite_add)) },
                        leadingIcon = { Icon(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null) },
                        enabled = !favoriteBusy,
                        onClick = { menuOpen = false; onFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreCommonR.string.playlist_add_to)) },
                        leadingIcon = { Icon(Icons.Outlined.LibraryAdd, null) },
                        onClick = { menuOpen = false; onAddToPlaylist() }
                    )
                    DropdownMenuItem(text = { Text(stringResource(CoreCommonR.string.action_enter_multi_select)) },
                        onClick = { menuOpen = false; onSelect() })
                }
            }
        }
    }
}
