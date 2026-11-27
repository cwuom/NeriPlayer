package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenu
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenuItem
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest

@Composable
internal fun InternationalHomeArtworkCard(
    entry: InternationalHomeEntry,
    modifier: Modifier,
    isFavorite: Boolean,
    onClick: () -> Unit,
    downloadPresenceVersion: Int,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val isArtist = entry is InternationalHomeEntry.Artist
    val typeIcon = when (entry.kind) {
        InternationalHomeKind.TRACK -> Icons.Rounded.MusicNote
        InternationalHomeKind.PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
        InternationalHomeKind.ALBUM -> Icons.Rounded.Album
        InternationalHomeKind.ARTIST -> Icons.Rounded.Person
    }
    val typeLabel = stringResource(
        when (entry.kind) {
            InternationalHomeKind.TRACK -> CoreCommonR.string.explore_search_type_song
            InternationalHomeKind.PLAYLIST -> CoreCommonR.string.playlist_title
            InternationalHomeKind.ALBUM -> CoreCommonR.string.local_song_detail_album
            InternationalHomeKind.ARTIST -> CoreCommonR.string.library_favorite_source_artist
        }
    )
    val cover = if (entry is InternationalHomeEntry.Track) {
        rememberHomeOnlineSongCoverUrl(entry.song, downloadPresenceVersion)
    } else entry.item.coverUrl
    var menuMounted by remember(entry.item) { mutableStateOf(false) }
    var showMenu by remember(entry.item) { mutableStateOf(false) }
    val click = if (isArtist) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClickLabel = stringResource(CoreCommonR.string.common_more_actions),
            onLongClick = {
                menuMounted = true
                showMenu = true
            }
        )
    }
    Column(
        modifier = modifier.clip(NeriDesign.tokens.cardShape).then(click).padding(bottom = 6.dp),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            InternationalHomeCover(
                url = cover,
                fallback = typeIcon,
                shape = if (isArtist) CircleShape else NeriDesign.tokens.coverLargeShape,
                sizePx = 384,
                offlineMode = offlineMode,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
            if (entry is InternationalHomeEntry.Track) {
                Icon(
                    Icons.Rounded.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(5.dp)
                )
            }
            if (menuMounted) {
                when (entry) {
                    is InternationalHomeEntry.Track -> SongRowMiniMenu(
                        expanded = showMenu, song = entry.song, isFavorite = isFavorite,
                        onDismiss = { showMenu = false }, onFavoriteToggle = onFavoriteToggle, onShowSnackbar = onShowSnackbar
                    )
                    is InternationalHomeEntry.Collection -> YtMusicPlaylistFavoriteMenu(
                        expanded = showMenu, playlist = entry.playlist, isFavorite = isFavorite,
                        onDismiss = { showMenu = false }, onShowSnackbar = onShowSnackbar
                    )
                    is InternationalHomeEntry.Artist -> Unit
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 3.dp),
            horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
        ) {
            Text(
                text = entry.item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val subtitle = entry.item.subtitle
                Icon(
                    typeIcon, contentDescription = typeLabel.takeIf { subtitle.isNotBlank() },
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle.ifBlank { typeLabel }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InternationalHomeCover(
    url: String?,
    fallback: ImageVector,
    shape: Shape,
    sizePx: Int,
    offlineMode: Boolean,
    modifier: Modifier
) {
    val context = LocalContext.current
    val request = remember(context, url, sizePx, offlineMode) {
        url?.takeIf(String::isNotBlank)?.let {
            fastScrollableImageRequest(context, it, sizePx = sizePx, offlineMode = offlineMode)
        }
    }
    Box(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (request != null) {
            AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun YtMusicPlaylistFavoriteMenu(
    expanded: Boolean,
    playlist: YouTubeMusicPlaylist,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val playlistFavoriteId = remember(playlist.playlistId, playlist.browseId) {
        playlist.favoriteId()
    }
    val unfavoritedText = stringResource(CoreCommonR.string.home_unfavorited)
    val favoriteSuccessText = stringResource(CoreCommonR.string.favorite_success)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    if (isFavorite) {
                        stringResource(CoreCommonR.string.home_unfavorite_playlist)
                    } else {
                        stringResource(CoreCommonR.string.home_favorite_playlist)
                    }
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (isFavorite) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Outlined.FavoriteBorder
                    },
                    contentDescription = null
                )
            },
            onClick = {
                onDismiss()
                scope.launch {
                    if (isFavorite) {
                        favoriteRepo.removeFavorite(playlistFavoriteId, "youtubeMusic")
                        onShowSnackbar(unfavoritedText)
                    } else {
                        favoriteRepo.addFavorite(
                            id = playlistFavoriteId,
                            name = playlist.title,
                            coverUrl = playlist.coverUrl,
                            trackCount = playlist.trackCount,
                            source = "youtubeMusic",
                            browseId = playlist.browseId,
                            playlistId = playlist.playlistId,
                            subtitle = playlist.subtitle,
                            songs = emptyList()
                        )
                        onShowSnackbar(favoriteSuccessText)
                    }
                }
            }
        )
    }
}
