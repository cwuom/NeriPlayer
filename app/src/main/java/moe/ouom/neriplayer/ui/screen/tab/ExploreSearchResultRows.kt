package moe.ouom.neriplayer.ui.screen.tab

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseSearchArtistResult
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.ClipboardCopyResult
import moe.ouom.neriplayer.ui.util.copyPlainTextSafely
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

@Composable
internal fun NeteasePlaylistSearchRow(
    playlist: PlaylistSummary,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    LinkedCollectionRow(
        title = playlist.name,
        subtitle = stringResource(
            R.string.playlist_play_count_format,
            formatPlayCount(context, playlist.playCount),
            playlist.trackCount
        ),
        coverUrl = playlist.picUrl,
        offlineMode = offlineMode,
        fallbackIcon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        },
        onClick = onClick
    )
}

@Composable
internal fun BiliPlaylistSearchRow(
    playlist: BiliPlaylist,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    LinkedCollectionRow(
        title = playlist.title,
        subtitle = biliCollectionSubtitle(
            playlist.subtitle,
            pluralStringResource(R.plurals.bili_content_count, playlist.count, playlist.count)
        ),
        coverUrl = playlist.coverUrl,
        offlineMode = offlineMode,
        fallbackIcon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        },
        onClick = onClick
    )
}

@Composable
internal fun YouTubePlaylistSearchRow(
    playlist: YouTubeMusicPlaylist,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    LinkedCollectionRow(
        title = playlist.title,
        subtitle = youtubeCollectionSubtitle(
            playlist.subtitle,
            playlist.trackCount,
            pluralStringResource(
                R.plurals.count_songs_format,
                playlist.trackCount,
                playlist.trackCount
            )
        ),
        coverUrl = playlist.coverUrl,
        offlineMode = offlineMode,
        fallbackIcon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        },
        onClick = onClick
    )
}

internal fun biliCollectionSubtitle(subtitle: String, countLabel: String): String =
    listOfNotNull(subtitle.takeIf { it.isNotBlank() }, countLabel).joinToString(" · ")

internal fun youtubeCollectionSubtitle(
    subtitle: String,
    trackCount: Int,
    countLabel: String
): String = listOfNotNull(
    subtitle.takeIf { it.isNotBlank() },
    countLabel.takeIf { trackCount > 0 }
).joinToString(" · ")

@Composable
internal fun NeteaseArtistSearchRow(
    result: NeteaseSearchArtistResult,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    LinkedCollectionRow(
        title = result.artist.name,
        subtitle = listOf(
            pluralStringResource(
                R.plurals.artist_song_count,
                result.musicSize,
                result.musicSize
            ),
            pluralStringResource(
                R.plurals.artist_album_count,
                result.albumSize,
                result.albumSize
            )
        ).joinToString(" · "),
        coverUrl = result.picUrl,
        offlineMode = offlineMode,
        fallbackIcon = {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp)
            )
        },
        onClick = onClick
    )
}

@Composable
internal fun YouTubeCreatorSearchRow(
    creator: YouTubeMusicCreatorSummary,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    LinkedCollectionRow(
        title = creator.title,
        subtitle = creator.subtitle.ifBlank {
            stringResource(R.string.explore_search_type_creator)
        },
        coverUrl = creator.coverUrl,
        offlineMode = offlineMode,
        fallbackIcon = {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp)
            )
        },
        onClick = onClick
    )
}

@Composable
private fun LinkedCollectionRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    offlineMode: Boolean,
    fallbackIcon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.performHapticFeedback()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinkedCollectionCover(title, coverUrl, offlineMode, fallbackIcon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LinkedCollectionCover(
    title: String,
    coverUrl: String?,
    offlineMode: Boolean,
    fallbackIcon: @Composable () -> Unit
) {
    val context = LocalContext.current
    val visibleCoverUrl = exploreVisibleCoverUrl(coverUrl)
    Box(
        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (visibleCoverUrl != null) {
            AsyncImage(
                model = fastScrollableImageRequest(
                    context = context,
                    data = visibleCoverUrl,
                    sizePx = 144,
                    offlineMode = offlineMode
                ),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            fallbackIcon()
        }
    }
}

internal fun exploreVisibleCoverUrl(coverUrl: String?): String? {
    if (coverUrl == null || coverUrl.isBlank()) return null
    return coverUrl
}

@Composable
internal fun ExploreSearchNoticeRow(item: ExploreSearchResult.Notice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = item.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SearchLoadingMoreRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
internal fun SearchLoadMoreErrorRow(
    error: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        HapticTextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
internal fun SongRow(
    state: ExploreSongRowState,
    actions: ExploreSongRowActions
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.performHapticFeedback()
                actions.onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongRowIndex(state.index)
        SongRowCover(state.song, state.offlineMode)
        SongRowIdentity(state.song, Modifier.weight(1f))
        SongRowDuration(state.song.durationMs)
        Spacer(Modifier.width(8.dp))
        SongRowMoreButton(state, actions)
    }
}

internal class ExploreSongRowState(
    val index: Int,
    val song: SongItem,
    val isFavorite: Boolean,
    val favoriteActionEnabled: Boolean,
    val offlineMode: Boolean
)

internal class ExploreSongRowActions(
    val onClick: () -> Unit,
    val onPlayNow: () -> Unit,
    val onPlayNext: () -> Unit,
    val onAddToQueueEnd: () -> Unit,
    val onDownload: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onCopyInfo: () -> Unit
)

@Composable
private fun SongRowMoreButton(state: ExploreSongRowState, actions: ExploreSongRowActions) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Box {
        HapticIconButton(onClick = { showMoreMenu = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.cd_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ExploreSongActionMenu(
            expanded = showMoreMenu,
            isFavorite = state.isFavorite,
            favoriteActionEnabled = state.favoriteActionEnabled,
            onDismiss = { showMoreMenu = false },
            onPlayNow = actions.onPlayNow,
            onPlayNext = actions.onPlayNext,
            onAddToQueueEnd = actions.onAddToQueueEnd,
            onDownload = actions.onDownload,
            onToggleFavorite = actions.onToggleFavorite,
            onCopyInfo = actions.onCopyInfo
        )
    }
}

@Composable
private fun SongRowIndex(index: Int) {
    Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SongRowCover(song: SongItem, offlineMode: Boolean) {
    val context = LocalContext.current
    val coverUrl = exploreVisibleCoverUrl(rememberSongDisplayCoverUrl(song))
    if (coverUrl != null) {
        AsyncImage(
            model = fastScrollableImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 128,
                offlineMode = offlineMode
            ),
            contentDescription = song.displayName(),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
        )
    }
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun SongRowIdentity(song: SongItem, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Text(
            text = song.displayName(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = exploreSongSubtitle(song.displayArtist(), song.displayAlbum(context)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun exploreSongSubtitle(artist: String, album: String): String =
    listOfNotNull(
        artist.takeIf { it.isNotBlank() },
        album.takeIf { it.isNotBlank() }
    ).joinToString(" · ")

@Composable
private fun SongRowDuration(durationMs: Long) {
    if (durationMs <= 0L) return
    Text(
        text = formatDuration(durationMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ExploreSongActionMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    favoriteActionEnabled: Boolean,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueueEnd: () -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyInfo: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SongMenuAction(
            textRes = R.string.search_result_play_keep_queue,
            icon = Icons.Filled.PlayCircle,
            enabled = true,
            onClick = { onPlayNow(); onDismiss() }
        )
        SongMenuAction(
            textRes = R.string.local_playlist_play_next,
            icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
            enabled = true,
            onClick = { onPlayNext(); onDismiss() }
        )
        SongMenuAction(
            textRes = R.string.search_result_add_to_current_queue,
            icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
            enabled = true,
            onClick = { onAddToQueueEnd(); onDismiss() }
        )
        SongMenuAction(
            textRes = favoriteActionLabel(isFavorite),
            icon = favoriteActionIcon(isFavorite),
            enabled = favoriteActionEnabled,
            onClick = { onToggleFavorite(); onDismiss() }
        )
        SongMenuAction(
            textRes = R.string.download_to_local,
            icon = Icons.Outlined.Download,
            enabled = true,
            onClick = { onDownload(); onDismiss() }
        )
        SongInfoCopyMenuItem(onClick = { onCopyInfo(); onDismiss() })
    }
}

@StringRes
private fun favoriteActionLabel(isFavorite: Boolean): Int =
    if (isFavorite) R.string.favorite_remove else R.string.favorite_add

private fun favoriteActionIcon(isFavorite: Boolean): ImageVector =
    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder

@Composable
private fun SongMenuAction(
    @StringRes textRes: Int,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    DropdownMenuItem(
        text = { Text(stringResource(textRes)) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        enabled = enabled,
        onClick = {
            context.performHapticFeedback()
            onClick()
        }
    )
}

@Composable
private fun SongInfoCopyMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_copy_song_info)) },
        leadingIcon = {
            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null)
        },
        onClick = onClick
    )
}

@StringRes
internal fun exploreClipboardMessageRes(result: ClipboardCopyResult): Int =
    when (result) {
        is ClipboardCopyResult.Copied ->
            if (result.wasTruncated) R.string.toast_copy_truncated else R.string.toast_copied
        ClipboardCopyResult.TransactionTooLarge -> R.string.toast_copy_failed
    }

internal fun buildExploreSongInfo(song: SongItem): String {
    return "${song.displayName()}-${song.displayArtist()}"
}
