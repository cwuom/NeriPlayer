package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import android.app.Application
import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.buildLocalPlaylistUsageLookup
import moe.ouom.neriplayer.data.playlist.usage.resolveLocalPlaylistUsageId
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.ui.util.shouldAllowCollapsingTopAppBar
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteasePlaylistSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteaseSongSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomePlaylistSource
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.platform.youtube.api.parser.YouTubeMusicParser
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.util.format.formatPlayCount
import kotlin.math.ceil
import kotlin.math.min
import java.util.Locale

@Composable
internal fun YtMusicPlaylistCard(
    playlist: YouTubeMusicPlaylist,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val playlistFavoriteId = remember(playlist.playlistId, playlist.browseId) {
        playlist.favoriteId()
    }
    var showMenu by remember { mutableStateOf(false) }
    val unfavoritedText = stringResource(CoreCommonR.string.home_unfavorited)
    val favoriteSuccessText = stringResource(CoreCommonR.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(neriRoundedShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        if (NeriDesign.isClean) {
            HomeFeaturedArtwork(
                model = fastScrollableImageRequest(
                    context = context,
                    data = playlist.coverUrl,
                    sizePx = 384,
                    offlineMode = offlineMode
                ),
                title = playlist.title,
                subtitle = playlist.subtitle
            )
        } else {
            AsyncImage(
                model = fastScrollableImageRequest(
                    context = context,
                    data = playlist.coverUrl,
                    sizePx = 384,
                    offlineMode = offlineMode
                ),
                contentDescription = playlist.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(neriRoundedShape(8.dp))
            )
            Column(modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                Text(
                    text = playlist.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (playlist.subtitle.isNotBlank()) {
                    Text(
                        text = playlist.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
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
                    showMenu = false
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
}

@Composable
internal fun YtMusicHomeItemCard(
    item: YouTubeMusicHomeItem,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val playlist = remember(item) { item.toPlaylist() }
    val playlistFavoriteId = remember(playlist?.playlistId, playlist?.browseId) {
        playlist?.favoriteId()
    }
    var showMenu by remember { mutableStateOf(false) }
    val unfavoritedText = stringResource(CoreCommonR.string.home_unfavorited)
    val favoriteSuccessText = stringResource(CoreCommonR.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(neriRoundedShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (playlist != null) {
                        showMenu = true
                    }
                }
            )
    ) {
        AsyncImage(
            model = fastScrollableImageRequest(
                context = context,
                data = item.coverUrl,
                sizePx = 384,
                offlineMode = offlineMode
            ),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(neriRoundedShape(8.dp))
        )
        Column(modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            playlist?.let { resolvedPlaylist ->
                val favoriteId = playlistFavoriteId ?: return@let
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
                        showMenu = false
                        scope.launch {
                            if (isFavorite) {
                                favoriteRepo.removeFavorite(favoriteId, "youtubeMusic")
                                onShowSnackbar(unfavoritedText)
                            } else {
                                favoriteRepo.addFavorite(
                                    id = favoriteId,
                                    name = resolvedPlaylist.title,
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    trackCount = resolvedPlaylist.trackCount,
                                    source = "youtubeMusic",
                                    browseId = resolvedPlaylist.browseId,
                                    playlistId = resolvedPlaylist.playlistId,
                                    subtitle = resolvedPlaylist.subtitle,
                                    songs = emptyList()
                                )
                                onShowSnackbar(favoriteSuccessText)
                            }
                        }
                    }
                )
            }
        }
    }
}
