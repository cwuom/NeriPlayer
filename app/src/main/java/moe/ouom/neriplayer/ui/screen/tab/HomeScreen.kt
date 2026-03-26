package moe.ouom.neriplayer.ui.screen.tab

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab/HomeScreen
 * Created: 2025/8/8
 */

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicParser
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.formatPlayCount
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import kotlin.math.ceil
import kotlin.math.min
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    showContinueCard: Boolean = true,
    showTrendingCard: Boolean = true,
    showRadarCard: Boolean = true,
    showRecommendedCard: Boolean = true,
    onItemClick: (NeteasePlaylist) -> Unit = {},
    onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    gridState: LazyGridState,
    onOpenRecent: (UsageEntry) -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                HomeViewModel(app)
            }
        }
    )
    val ui by vm.uiState.collectAsState()
    val usage by AppContainer.playlistUsageRepo.frequentPlaylistsFlow.collectAsState(initial = emptyList())
    val localPlaylistRepo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val localPlaylists by localPlaylistRepo.playlists.collectAsState()

    val hasLocalUsage = remember(usage) {
        usage.any { it.source == PlaylistUsageRepository.SOURCE_LOCAL }
    }
    LaunchedEffect(hasLocalUsage, localPlaylists) {
        if (hasLocalUsage) {
            withContext(Dispatchers.Default) {
                AppContainer.playlistUsageRepo.syncLocalEntries(localPlaylists)
            }
        }
    }

    val titleOptions = listOf(
        stringResource(R.string.app_name),
        stringResource(R.string.home_title_brand_loud),
        stringResource(R.string.home_title_brand_wave),
        stringResource(R.string.home_title_brand_call)
    ).distinct()
    val titleSeed = rememberSaveable { (0..Int.MAX_VALUE).random() }
    val appBarTitle = titleOptions[titleSeed % titleOptions.size]
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val snackbarHostState = remember { SnackbarHostState() }
    val guessYouLikeTitle = stringResource(R.string.home_ytmusic_guess_you_like)
    val dailyDiscoverTitle = stringResource(R.string.home_ytmusic_daily_discover)
    val moreRecommendationsTitle = stringResource(R.string.home_ytmusic_more_recommendations)
    val ytmSections = remember(ui.ytMusicHomeShelves.items) {
        classifyYouTubeMusicShelves(ui.ytMusicHomeShelves.items)
    }
    val hasVisibleYtMusicFeed = remember(ytmSections) {
        ytmSections.guessYouLike != null ||
            ytmSections.dailyDiscover != null ||
            ytmSections.remaining.any { shelf ->
                shelf.shouldRenderAsSongShelf() || shelf.hasRenderablePlaylistItems()
            }
    }
    val scope = rememberCoroutineScope()
    val showContinue = showContinueCard && usage.isNotEmpty()
    val isInternational = ui.internationalizationEnabled
    val hasVisibleSections =
        showContinue || showTrendingCard || showRadarCard || showRecommendedCard || isInternational

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            LargeTopAppBar(
                title = { Text(appBarTitle) },
                actions = {
                    HapticIconButton(
                        onClick = {
                            if (isInternational) {
                                vm.refreshYtMusicPlaylists()
                                vm.refreshYtMusicHomeFeed()
                            } else {
                                vm.refreshRecommend()
                                vm.loadHomeRecommendations(force = true)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.recommend_refresh)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.0f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxSize()
            ) {
                if (!hasVisibleSections) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.home_all_cards_hidden),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Card
                }

                val miniPlayerHeight = LocalMiniPlayerHeight.current
                val homeLoadingText = stringResource(R.string.home_loading)
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp + miniPlayerHeight
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (showContinue) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                icon = Icons.Outlined.History,
                                title = stringResource(R.string.player_continue)
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueSection(
                                items = usage.take(12),
                                onClick = { entry -> onOpenRecent(entry) }
                            )
                        }
                    }

                    if (isInternational) {
                        if (showTrendingCard && ytmSections.guessYouLike != null) {
                            addYouTubeMusicSongShelfSection(
                                shelf = ytmSections.guessYouLike,
                                icon = Icons.Outlined.Bolt,
                                title = guessYouLikeTitle,
                                onSongClick = onSongClick
                            )
                        }

                        if (showRadarCard && ytmSections.dailyDiscover != null) {
                            addYouTubeMusicSongShelfSection(
                                shelf = ytmSections.dailyDiscover,
                                icon = Icons.Outlined.Explore,
                                title = dailyDiscoverTitle,
                                onSongClick = onSongClick
                            )
                        }

                        if (showRecommendedCard) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    icon = Icons.Outlined.Star,
                                    title = moreRecommendationsTitle
                                )
                            }

                            when {
                                ui.ytMusicPlaylists.items.isNotEmpty() -> {
                                    items(
                                        items = ui.ytMusicPlaylists.items,
                                        key = { it.browseId }
                                    ) { playlist ->
                                        YtMusicPlaylistCard(
                                            playlist = playlist,
                                            onClick = { onYouTubeMusicPlaylistClick(playlist) },
                                            onShowSnackbar = { message ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(message)
                                                }
                                            }
                                        )
                                    }
                                }
                                ui.ytMusicPlaylists.loading -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionLoadingState(homeLoadingText)
                                    }
                                }
                                ui.ytMusicPlaylists.error != null -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionErrorState(detail = ui.ytMusicPlaylists.error ?: "")
                                    }
                                }
                            }

                            when {
                                ytmSections.remaining.any { shelf ->
                                    shelf.shouldRenderAsSongShelf() || shelf.hasRenderablePlaylistItems()
                                } -> {
                                    ytmSections.remaining.forEach { shelf ->
                                        if (shelf.shouldRenderAsSongShelf()) {
                                            addYouTubeMusicSongShelfSection(
                                                shelf = shelf,
                                                icon = Icons.Outlined.Explore,
                                                title = shelf.title,
                                                onSongClick = onSongClick
                                            )
                                        } else {
                                            val playlistItems = shelf.items.filter { it.isPlaylistItem() }
                                            if (playlistItems.isEmpty()) {
                                                return@forEach
                                            }
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                SectionHeader(
                                                    icon = Icons.Outlined.Explore,
                                                    title = shelf.title
                                                )
                                            }
                                            items(
                                                items = playlistItems,
                                                key = { shelf.title + it.title + it.browseId + it.videoId }
                                            ) { homeItem ->
                                                YtMusicHomeItemCard(
                                                    item = homeItem,
                                                    onClick = {
                                                        val playlist = homeItem.toPlaylist()
                                                        if (playlist != null) {
                                                            onYouTubeMusicPlaylistClick(playlist)
                                                        } else if (homeItem.videoId.isNotBlank()) {
                                                            val songs = listOfNotNull(
                                                                homeItem.toPlayableSongItem(shelf.title)
                                                            )
                                                            if (songs.isNotEmpty()) {
                                                                onSongClick(songs, 0)
                                                            }
                                                        }
                                                    },
                                                    onShowSnackbar = { message ->
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(message)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                ui.ytMusicHomeShelves.loading -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionLoadingState(homeLoadingText)
                                    }
                                }
                                ui.ytMusicHomeShelves.error != null -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionErrorState(detail = ui.ytMusicHomeShelves.error ?: "")
                                    }
                                }
                            }
                        }

                        if (!hasVisibleYtMusicFeed && (showTrendingCard || showRadarCard || showRecommendedCard)) {
                            when {
                                ui.ytMusicHomeShelves.loading -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionLoadingState(homeLoadingText)
                                    }
                                }
                                ui.ytMusicHomeShelves.error != null -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionErrorState(detail = ui.ytMusicHomeShelves.error ?: "")
                                    }
                                }
                            }
                        }
                    } else {
                        if (showTrendingCard) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    icon = Icons.Outlined.Bolt,
                                    title = stringResource(R.string.recommend_trending)
                                )
                            }
                            sectionContent(
                                section = ui.hotSongs,
                                loadingText = homeLoadingText,
                                errorDetail = ui.hotSongs.error
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    ResponsiveSongPagerList(
                                        songs = ui.hotSongs.items,
                                        onSongClick = onSongClick
                                    )
                                }
                            }
                        }

                        if (showRadarCard) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    icon = Icons.Outlined.Radar,
                                    title = stringResource(R.string.recommend_radar)
                                )
                            }
                            sectionContent(
                                section = ui.radarSongs,
                                loadingText = homeLoadingText,
                                errorDetail = ui.radarSongs.error
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    ResponsiveSongPagerList(
                                        songs = ui.radarSongs.items,
                                        onSongClick = onSongClick
                                    )
                                }
                            }
                        }

                        if (showRecommendedCard) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    icon = Icons.Outlined.Star,
                                    title = stringResource(R.string.recommend_for_you)
                                )
                            }
                            when {
                                ui.playlists.items.isNotEmpty() -> {
                                    items(items = ui.playlists.items, key = { it.id }) { item ->
                                        PlaylistCard(
                                            playlist = item,
                                            onClick = { onItemClick(item) },
                                            onShowSnackbar = { message ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(message)
                                                }
                                            }
                                        )
                                    }
                                }

                                ui.playlists.loading -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionLoadingState(homeLoadingText)
                                    }
                                }

                                ui.playlists.error != null -> {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        SectionErrorState(detail = ui.playlists.error ?: "")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalMiniPlayerHeight.current)
        )
    }
}

private fun <T> LazyGridScope.sectionContent(
    section: HomeSectionState<T>,
    loadingText: String,
    errorDetail: String?,
    content: LazyGridScope.() -> Unit
) {
    when {
        section.items.isNotEmpty() -> content()
        section.loading -> {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLoadingState(loadingText)
            }
        }

        !errorDetail.isNullOrBlank() -> {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionErrorState(errorDetail)
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SectionLoadingState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionErrorState(detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = detail,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.home_retry_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SongRowMini(
    index: Int,
    song: SongItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val coverUrl = song.displayCoverUrl(context)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(28.dp),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )

        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = offlineCachedImageRequest(context, coverUrl),
                contentDescription = song.displayName(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(10.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = song.displayName(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = listOfNotNull(
                    song.displayArtist().takeIf { it.isNotBlank() },
                    song.displayAlbum(context).takeIf { it.isNotBlank() }
                ).joinToString(" / "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    playlist: NeteasePlaylist,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val isFavorite = remember(favorites, playlist.id) {
        favoriteRepo.isFavorite(playlist.id, "netease")
    }
    var showMenu by remember { mutableStateOf(false) }

    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(playlist.picUrl).crossfade(true).build(),
            contentDescription = playlist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        )
        Column(modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = playlist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(
                    R.string.home_play_count_format,
                    formatPlayCount(context, playlist.playCount),
                    playlist.trackCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (isFavorite) {
                            stringResource(R.string.home_unfavorite_playlist)
                        } else {
                            stringResource(R.string.home_favorite_playlist)
                        }
                    )
                },
                onClick = {
                    showMenu = false
                    scope.launch {
                        if (isFavorite) {
                            favoriteRepo.removeFavorite(playlist.id, "netease")
                            onShowSnackbar(unfavoritedText)
                        } else {
                            favoriteRepo.addFavorite(
                                id = playlist.id,
                                name = playlist.name,
                                coverUrl = playlist.picUrl,
                                trackCount = playlist.trackCount,
                                source = "netease",
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
private fun YtMusicPlaylistCard(
    playlist: YouTubeMusicPlaylist,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val playlistFavoriteId = remember(playlist.playlistId, playlist.browseId) {
        playlist.favoriteId()
    }
    val isFavorite = remember(favorites, playlistFavoriteId) {
        favoriteRepo.isFavorite(playlistFavoriteId, "youtubeMusic")
    }
    var showMenu by remember { mutableStateOf(false) }
    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(playlist.coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = playlist.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
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

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (isFavorite) {
                            stringResource(R.string.home_unfavorite_playlist)
                        } else {
                            stringResource(R.string.home_favorite_playlist)
                        }
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
private fun YtMusicHomeItemCard(
    item: YouTubeMusicHomeItem,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val playlist = remember(item) { item.toPlaylist() }
    val favorites by favoriteRepo.favorites.collectAsState()
    val playlistFavoriteId = remember(playlist?.playlistId, playlist?.browseId) {
        playlist?.favoriteId()
    }
    val isFavorite = remember(favorites, playlistFavoriteId) {
        playlistFavoriteId?.let { favoriteRepo.isFavorite(it, "youtubeMusic") } == true
    }
    var showMenu by remember { mutableStateOf(false) }
    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
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
            model = ImageRequest.Builder(context)
                .data(item.coverUrl)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
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
                                stringResource(R.string.home_unfavorite_playlist)
                            } else {
                                stringResource(R.string.home_favorite_playlist)
                            }
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

private data class ClassifiedYouTubeMusicShelves(
    val guessYouLike: YouTubeMusicHomeShelf?,
    val dailyDiscover: YouTubeMusicHomeShelf?,
    val remaining: List<YouTubeMusicHomeShelf>
)

private val YouTubeMusicGuessYouLikeKeywords = listOf(
    "猜你喜欢",
    "guess you like",
    "recommended for you"
)

private val YouTubeMusicDailyDiscoverKeywords = listOf(
    "每日发现",
    "daily discover",
    "discover daily"
)

private val YouTubeMusicSongShelfKeywords = listOf(
    "再听一遍",
    "老歌重温",
    "翻唱与混音",
    "每日发现",
    "猜你喜欢",
    "listen again",
    "oldies",
    "covers and remixes",
    "daily discover"
)

private fun classifyYouTubeMusicShelves(
    shelves: List<YouTubeMusicHomeShelf>
): ClassifiedYouTubeMusicShelves {
    val guessYouLike = shelves.firstOrNull { shelf ->
        shelf.shouldRenderAsSongShelf() &&
            shelf.title.matchesYouTubeMusicShelfKeywords(YouTubeMusicGuessYouLikeKeywords)
    }
    val dailyDiscover = shelves.firstOrNull { shelf ->
        shelf != guessYouLike &&
            shelf.shouldRenderAsSongShelf() &&
            shelf.title.matchesYouTubeMusicShelfKeywords(YouTubeMusicDailyDiscoverKeywords)
    }
    val remaining = shelves.filterNot { shelf ->
        shelf == guessYouLike || shelf == dailyDiscover
    }
    return ClassifiedYouTubeMusicShelves(
        guessYouLike = guessYouLike,
        dailyDiscover = dailyDiscover,
        remaining = remaining
    )
}

private fun YouTubeMusicHomeShelf.shouldRenderAsSongShelf(): Boolean {
    if (items.isEmpty()) {
        return false
    }
    val playableCount = items.count { it.videoId.isNotBlank() }
    if (playableCount == 0) {
        return false
    }
    if (playableCount == items.size) {
        return true
    }
    return title.matchesYouTubeMusicShelfKeywords(YouTubeMusicSongShelfKeywords)
}

private fun YouTubeMusicHomeShelf.hasRenderablePlaylistItems(): Boolean {
    return items.any { it.isPlaylistItem() }
}

private fun YouTubeMusicHomeItem.isPlaylistItem(): Boolean {
    val normalizedPageType = pageType.uppercase(Locale.US)
    return when {
        normalizedPageType.contains("PLAYLIST") -> true
        normalizedPageType.isNotBlank() -> false
        else -> browseId.startsWith("VL")
    }
}

private fun YouTubeMusicHomeItem.toPlaylist(): YouTubeMusicPlaylist? {
    if (!isPlaylistItem()) {
        return null
    }
    return YouTubeMusicPlaylist(
        browseId = browseId,
        playlistId = browseId.removePrefix("VL"),
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl,
        trackCount = 0
    )
}

private fun String.matchesYouTubeMusicShelfKeywords(keywords: List<String>): Boolean {
    val normalized = lowercase(Locale.ROOT)
        .replace(Regex("[\\s·•・/\\\\|:_-]+"), "")
    return keywords.any { keyword ->
        val normalizedKeyword = keyword.lowercase(Locale.ROOT)
            .replace(Regex("[\\s·•・/\\\\|:_-]+"), "")
        normalized.contains(normalizedKeyword)
    }
}

internal fun YouTubeMusicHomeItem.toPlayableSongItem(sectionTitle: String): SongItem? {
    if (videoId.isBlank()) {
        return null
    }
    val metadata = YouTubeMusicParser.parseHomeSongMetadata(
        subtitle = subtitle,
        fallbackAlbum = sectionTitle
    )
    val playlistId = browseId.removePrefix("VL").ifBlank { null }
    return SongItem(
        id = stableYouTubeMusicId(videoId),
        name = title,
        artist = metadata.artist,
        album = metadata.album,
        albumId = stableYouTubeMusicId((playlistId ?: sectionTitle).ifBlank { videoId }),
        durationMs = durationMs,
        coverUrl = coverUrl.ifBlank { null },
        mediaUri = buildYouTubeMusicMediaUri(
            videoId = videoId,
            playlistId = playlistId
        ),
        originalName = title,
        originalArtist = metadata.artist,
        originalCoverUrl = coverUrl.ifBlank { null }
    )
}

private fun LazyGridScope.addYouTubeMusicSongShelfSection(
    shelf: YouTubeMusicHomeShelf,
    icon: ImageVector,
    title: String,
    onSongClick: (List<SongItem>, Int) -> Unit
) {
    val songs = shelf.items.mapNotNull { it.toPlayableSongItem(shelf.title) }
    if (songs.isEmpty()) {
        return
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(
            icon = icon,
            title = title
        )
    }
    item(span = { GridItemSpan(maxLineSpan) }) {
        ResponsiveSongPagerList(
            songs = songs,
            onSongClick = onSongClick
        )
    }
}

@Composable
private fun ContinueSection(items: List<UsageEntry>, onClick: (UsageEntry) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.source + ":" + it.id }) { entry ->
                ContinueCard(
                    entry = entry,
                    onClick = { onClick(entry) },
                    onRemove = {
                        AppContainer.playlistUsageRepo.removeEntry(entry.id, entry.source)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: UsageEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = androidx.compose.ui.platform.LocalView.current
    var showMenu by remember { mutableStateOf(false) }
    val displayName = remember(entry.id, entry.name, entry.source, configuration) {
        SystemLocalPlaylists.resolve(entry.id, entry.name, context)?.currentName ?: entry.name
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showMenu = true
                }
            )
            .width(150.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(entry.picUrl).build(),
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
        )
        Column(modifier = Modifier.padding(6.dp)) {
            Text(
                text = displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                                text = pluralStringResource(
                                    R.plurals.home_song_count_format,
                                    entry.trackCount,
                                    entry.trackCount
                                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.continue_playing_remove)) },
                onClick = {
                    showMenu = false
                    onRemove()
                }
            )
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun ResponsiveSongPagerList(
    songs: List<SongItem>,
    onSongClick: (List<SongItem>, Int) -> Unit
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val columns = when {
        widthDp >= 840 -> 3
        widthDp >= 600 -> 2
        else -> 1
    }
    val rowsPerColumn = 3
    val perPage = (columns * rowsPerColumn).coerceAtLeast(1)

    val pageCount = ceil(songs.size / perPage.toFloat()).toInt().coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) { page ->
        val start = page * perPage
        val end = min(start + perPage, songs.size)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (columnIndex in 0 until columns) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (rowIndex in 0 until rowsPerColumn) {
                        val absoluteIndex = start + (columnIndex * rowsPerColumn + rowIndex)
                        if (absoluteIndex < end) {
                            val song = songs[absoluteIndex]
                            SongRowMini(
                                index = absoluteIndex + 1,
                                song = song,
                                onClick = { onSongClick(songs, absoluteIndex) }
                            )
                        } else {
                            Spacer(Modifier.height(0.dp))
                        }
                    }
                }
            }
        }
    }
}
