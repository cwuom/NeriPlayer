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

import android.app.Application
import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.DeleteForever
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.res.pluralStringResource
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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.playlist.usage.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.buildLocalPlaylistUsageLookup
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.ui.util.shouldAllowCollapsingTopAppBar
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
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
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicParser
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.util.format.formatPlayCount
import kotlin.math.ceil
import kotlin.math.min
import java.util.Locale

private const val HomeContinueHorizontalPaddingDp = 8f
private const val HomeContinueCardSpacingDp = 12f
private const val HomeContinueCardMaxWidthDp = 140f
private const val HomeContinueEntryLimit = 12
private const val HomeLocalPlaylistCoverCandidateLimit = 24
private const val HomeDownloadedCoverSourceLimit = 64
private const val HomeContinueThreeSlotWidthDp = 300f
private const val HomeContinueTabletWidthDp = 600f
private const val HomeScrollKeyContinueHeader = "home:continue:header"
private const val HomeScrollKeyContinueContent = "home:continue:content"
private const val HomeScrollKeyYtGuess = "home:ytmusic:guess"
private const val HomeScrollKeyYtDaily = "home:ytmusic:daily"
private const val HomeScrollKeyYtMoreHeader = "home:ytmusic:more:header"
private const val HomeScrollKeyYtMoreLoading = "home:ytmusic:more:loading"
private const val HomeScrollKeyYtMoreError = "home:ytmusic:more:error"
private const val HomeScrollKeyYtShelvesLoading = "home:ytmusic:shelves:loading"
private const val HomeScrollKeyYtShelvesError = "home:ytmusic:shelves:error"
private const val HomeScrollKeyYtEmptyFeedLoading = "home:ytmusic:empty-feed:loading"
private const val HomeScrollKeyYtEmptyFeedError = "home:ytmusic:empty-feed:error"
private const val HomeScrollKeyNeteaseRadarPlaylists = "home:netease:radar-playlists"
private const val HomeScrollKeyNeteaseRadarPlaylistsHeader = "$HomeScrollKeyNeteaseRadarPlaylists:header"
private const val HomeScrollKeyNeteaseRadarPlaylistsContent = "$HomeScrollKeyNeteaseRadarPlaylists:content"

internal fun shouldShowHomeContinueSection(
    showContinueCard: Boolean,
    usageLoaded: Boolean,
    hasUsage: Boolean
): Boolean = showContinueCard && (!usageLoaded || hasUsage)

internal fun resolveHomeContinuePagerPage(savedPage: Int, pageCount: Int): Int {
    return savedPage.coerceIn(0, pageCount.coerceAtLeast(1) - 1)
}

internal fun shouldResolveHomeContinueLocalCoverFallback(
    persistedCoverUrl: String?,
    localPlaylist: LocalPlaylist?
): Boolean = localPlaylist != null && shouldValidateHomeContinueCoverReference(persistedCoverUrl)

internal fun shouldValidateHomeContinueCoverReference(
    persistedCoverUrl: String?
): Boolean {
    val normalized = persistedCoverUrl?.trim().orEmpty()
    return normalized.isEmpty() ||
        (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true))
}

internal fun homeLocalFilesCoverCandidates(
    downloadedSongs: List<DownloadedSong>
): List<SongItem> {
    return downloadedSongs.asSequence()
        .take(HomeDownloadedCoverSourceLimit)
        .filter { song ->
            !song.customCoverUrl.isNullOrBlank() ||
                !song.coverPath.isNullOrBlank() ||
                !song.coverUrl.isNullOrBlank()
        }
        .take(HomeLocalPlaylistCoverCandidateLimit)
        .map(DownloadedSong::toPlaybackSongItem)
        .toList()
}

private fun homeNeteaseSongSectionKey(group: String, source: NeteaseHomeSongSource): String {
    return "home:netease:$group:${source.name.lowercase(Locale.ROOT)}"
}

private fun homeNeteasePlaylistSectionKey(source: NeteaseHomePlaylistSource): String {
    return "home:netease:playlist:${source.name.lowercase(Locale.ROOT)}"
}

internal fun homeNeteasePlaylistScrollKey(sectionKey: String, id: Long): String {
    return "$sectionKey:playlist:$id"
}

internal fun homeNeteaseRadarPlaylistScrollKey(id: Long): String {
    return "$HomeScrollKeyNeteaseRadarPlaylists:playlist:$id"
}

private fun homeYtMusicPlaylistScrollKey(playlist: YouTubeMusicPlaylist): String {
    return "home:ytmusic:playlist:${playlist.favoriteId()}"
}

private fun homeYtMusicShelfScrollKey(shelfIndex: Int, title: String): String {
    return "home:ytmusic:shelf:$shelfIndex:${title.hashCode()}"
}

private fun homeYtMusicHomeItemScrollKey(
    shelfKey: String,
    itemIndex: Int,
    item: YouTubeMusicHomeItem
): String {
    val stableId = item.browseId.ifBlank { item.videoId }.ifBlank { item.title }
    return "$shelfKey:item:$itemIndex:${stableId.hashCode()}"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    showContinueCard: Boolean = true,
    showTrendingCard: Boolean = true,
    showRadarCard: Boolean = true,
    showRecommendedCard: Boolean = true,
    usageEntries: List<UsageEntry> = emptyList(),
    usageLoaded: Boolean = true,
    offlineMode: Boolean = false,
    continuePagerPage: Int = 0,
    onContinuePagerPageChanged: (Int) -> Unit = {},
    onItemClick: (PlaylistSummary) -> Unit = {},
    onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    gridState: LazyGridState,
    radarPlaylistListState: LazyListState,
    topAppBarState: TopAppBarState,
    onScrollAnchorIndexesChanged: (Map<String, Int>) -> Unit = {},
    onOpenRecent: (UsageEntry) -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = appContext as Application
                HomeViewModel(app)
            }
        }
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    var localPlaylistRepo by remember { mutableStateOf<LocalPlaylistRepository?>(null) }
    var localPlaylists by remember {
        mutableStateOf<List<LocalPlaylist>>(
            value = emptyList(),
            policy = referentialEqualityPolicy()
        )
    }
    var localPlaylistsReady by remember { mutableStateOf(false) }
    var localPlaylistSnapshotVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(appContext) {
        localPlaylistsReady = false
        val initializedRepo = withContext(Dispatchers.IO) {
            LocalPlaylistRepository.getInstance(appContext)
                .takeIf { repository -> repository.awaitInitialized() }
        }
        localPlaylistRepo = initializedRepo
        if (initializedRepo == null) return@LaunchedEffect
        initializedRepo.playlists.collect { playlists ->
            localPlaylists = playlists
            localPlaylistSnapshotVersion += 1
            localPlaylistsReady = true
        }
    }
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val downloadedSongs by GlobalDownloadManager.downloadedSongs.collectAsStateWithLifecycle()
    val downloadedPlaybackCoverCandidates = homeLocalFilesCoverCandidates(downloadedSongs)
    val favoriteKeys = remember(favorites) {
        favorites.mapTo(mutableSetOf()) { "${it.source}:${it.id}" }
    }
    val favoriteSongs = remember(localPlaylistSnapshotVersion, context) {
        localPlaylists
            .firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, context) }
            ?.songs
            .orEmpty()
    }
    val localPlaylistCoverFallbackIds = usageEntries.asSequence()
        .take(HomeContinueEntryLimit)
        .filter { entry ->
            entry.source == PlaylistUsageRepository.SOURCE_LOCAL &&
                shouldValidateHomeContinueCoverReference(entry.picUrl)
        }
        .mapTo(linkedSetOf()) { entry -> entry.id }
    val localPlaylistUsageLookup by produceState<Map<Long, LocalPlaylist>>(
        initialValue = emptyMap(),
        key1 = localPlaylistSnapshotVersion,
        key2 = localPlaylistCoverFallbackIds,
        key3 = appContext
    ) {
        value = if (localPlaylistCoverFallbackIds.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) {
                buildLocalPlaylistUsageLookup(
                    playlists = localPlaylists,
                    context = appContext,
                    requestedPlaylistIds = localPlaylistCoverFallbackIds,
                    maxSongsPerPlaylist = HomeLocalPlaylistCoverCandidateLimit
                )
            }
        }
    }

    val hasLocalPlaylistUsage = usageEntries.any { entry ->
        entry.source == PlaylistUsageRepository.SOURCE_LOCAL
    }
    val hasLocalArtistUsage = usageEntries.any { entry ->
        entry.source == PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
    }
    LaunchedEffect(
        hasLocalPlaylistUsage,
        hasLocalArtistUsage,
        localPlaylistsReady,
        localPlaylistSnapshotVersion,
        downloadedPlaybackCoverCandidates
    ) {
        if ((hasLocalPlaylistUsage || hasLocalArtistUsage) && localPlaylistsReady) {
            withContext(Dispatchers.Default) {
                if (hasLocalPlaylistUsage) {
                    AppContainer.playlistUsageRepo.syncLocalEntries(
                        playlists = localPlaylists,
                        localFilesCoverCandidates = downloadedPlaybackCoverCandidates,
                        resolveLocalMetadataFallback = false
                    )
                }
                if (hasLocalArtistUsage) {
                    AppContainer.playlistUsageRepo.syncLocalArtistEntries(
                        playlists = localPlaylists,
                        resolveLocalMetadataFallback = false
                    )
                }
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        canScroll = {
            shouldAllowCollapsingTopAppBar(
                canScrollForward = gridState.canScrollForward,
                canScrollBackward = gridState.canScrollBackward,
                collapsedFraction = topAppBarState.collapsedFraction
            )
        }
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val guessYouLikeTitle = stringResource(R.string.home_ytmusic_guess_you_like)
    val dailyDiscoverTitle = stringResource(R.string.home_ytmusic_daily_discover)
    val moreRecommendationsTitle = stringResource(R.string.home_ytmusic_more_recommendations)
    val favoriteAddedText = stringResource(R.string.favorite_added)
    val favoriteRemovedText = stringResource(R.string.favorite_removed)
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
    val showContinue = shouldShowHomeContinueSection(
        showContinueCard = showContinueCard,
        usageLoaded = usageLoaded,
        hasUsage = usageEntries.isNotEmpty()
    )
    val isInternational = ui.internationalizationEnabled
    val showNeteaseTrending = showTrendingCard
    val showNeteaseRadar = showRadarCard
    val showOnlineFeeds = !offlineMode
    var wasOffline by remember { mutableStateOf(offlineMode) }
    val windowWidthDp = currentWindowWidthDp()
    val isTabletLayout = windowWidthDp >= 720.dp
    val pageHorizontalPadding = if (isTabletLayout) 28.dp else 16.dp
    val gridMinCellSize = if (isTabletLayout) 156.dp else 120.dp
    val gridContentPadding = if (isTabletLayout) 14.dp else 8.dp
    val gridSpacing = if (isTabletLayout) 14.dp else 10.dp

    fun toggleHomeSongFavorite(song: SongItem, isFavorite: Boolean) {
        scope.launch {
            val repository = localPlaylistRepo ?: withContext(Dispatchers.IO) {
                LocalPlaylistRepository.getInstance(appContext)
            }
            if (isFavorite) {
                repository.removeFromFavorites(song)
                snackbarHostState.showNeriSnackbar(favoriteRemovedText)
            } else {
                repository.addToFavorites(song)
                snackbarHostState.showNeriSnackbar(favoriteAddedText)
            }
        }
    }
    val showHomeSnackbar: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.showNeriSnackbar(message)
        }
    }

    LaunchedEffect(offlineMode, isInternational) {
        vm.setOfflineMode(offlineMode)
        if (offlineMode) {
            wasOffline = true
            return@LaunchedEffect
        }

        if (!wasOffline) return@LaunchedEffect

        wasOffline = false
        if (isInternational) {
            vm.refreshYtMusicHome()
        } else {
            vm.refreshNeteaseHome()
        }
    }

    val hasVisibleSections =
            showContinue || (showOnlineFeeds && (showNeteaseTrending || showNeteaseRadar || showRecommendedCard || isInternational))

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
                        enabled = !offlineMode,
                        onClick = {
                            if (isInternational) {
                                vm.refreshYtMusicHome()
                            } else {
                                vm.refreshNeteaseHome()
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

            Box(
                modifier = Modifier
                    .padding(horizontal = pageHorizontalPadding, vertical = 4.dp)
                    .widthIn(max = 1240.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
            ) {
                if (!hasVisibleSections) {
                    SideEffect {
                        onScrollAnchorIndexesChanged(emptyMap())
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (offlineMode) {
                                stringResource(R.string.home_offline_no_continue)
                            } else {
                                stringResource(R.string.home_all_cards_hidden)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Box
                }

                val miniPlayerHeight = LocalMiniPlayerHeight.current
                val homeLoadingText = stringResource(R.string.home_loading)
                val scrollAnchorIndexes = linkedMapOf<String, Int>()
                var nextGridItemIndex = 0
                fun registerGridItemKey(key: String): String {
                    scrollAnchorIndexes[key] = nextGridItemIndex
                    nextGridItemIndex += 1
                    return key
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(gridMinCellSize),
                    contentPadding = PaddingValues(
                        start = gridContentPadding,
                        end = gridContentPadding,
                        top = gridContentPadding,
                        bottom = gridContentPadding + miniPlayerHeight
                    ),
                    verticalArrangement = Arrangement.spacedBy(gridSpacing),
                    horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (showContinue) {
                        item(
                            key = registerGridItemKey(HomeScrollKeyContinueHeader),
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            SectionHeader(
                                icon = Icons.Outlined.History,
                                title = stringResource(R.string.player_continue)
                            )
                        }
                        item(
                            key = registerGridItemKey(HomeScrollKeyContinueContent),
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            if (usageLoaded) {
                                ContinueSection(
                                    items = usageEntries.take(HomeContinueEntryLimit),
                                    localPlaylistLookup = localPlaylistUsageLookup,
                                    localFilesCoverCandidates = downloadedPlaybackCoverCandidates,
                                    onClick = { entry -> onOpenRecent(entry) },
                                    savedPage = continuePagerPage,
                                    onPageChanged = onContinuePagerPageChanged,
                                    offlineMode = offlineMode
                                )
                            } else {
                                SectionLoadingState(homeLoadingText)
                            }
                        }
                    }

                    if (showOnlineFeeds) {
                        if (isInternational) {
                            if (showNeteaseTrending && ytmSections.guessYouLike != null) {
                                addYouTubeMusicSongShelfSection(
                                    sectionKey = HomeScrollKeyYtGuess,
                                    registerKey = ::registerGridItemKey,
                                    shelf = ytmSections.guessYouLike,
                                    icon = Icons.Outlined.Bolt,
                                    title = guessYouLikeTitle,
                                    onSongClick = onSongClick,
                                    favoriteSongs = favoriteSongs,
                                    onFavoriteToggle = ::toggleHomeSongFavorite,
                                    onShowSnackbar = showHomeSnackbar,
                                    offlineMode = offlineMode
                                )
                            }

                            if (showNeteaseRadar && ytmSections.dailyDiscover != null) {
                                addYouTubeMusicSongShelfSection(
                                    sectionKey = HomeScrollKeyYtDaily,
                                    registerKey = ::registerGridItemKey,
                                    shelf = ytmSections.dailyDiscover,
                                    icon = Icons.Outlined.Explore,
                                    title = dailyDiscoverTitle,
                                    onSongClick = onSongClick,
                                    favoriteSongs = favoriteSongs,
                                    onFavoriteToggle = ::toggleHomeSongFavorite,
                                    onShowSnackbar = showHomeSnackbar,
                                    offlineMode = offlineMode
                                )
                            }

                            if (showRecommendedCard) {
                                item(
                                    key = registerGridItemKey(HomeScrollKeyYtMoreHeader),
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    SectionHeader(
                                        icon = Icons.Outlined.Star,
                                        title = moreRecommendationsTitle
                                    )
                                }

                                when {
                                    ui.ytMusicPlaylists.items.isNotEmpty() -> {
                                        ui.ytMusicPlaylists.items.forEach { playlist ->
                                            item(
                                                key = registerGridItemKey(
                                                    homeYtMusicPlaylistScrollKey(playlist)
                                                )
                                            ) {
                                                YtMusicPlaylistCard(
                                                    playlist = playlist,
                                                    isFavorite = favoriteKeys.contains("youtubeMusic:${playlist.favoriteId()}"),
                                                    onClick = { onYouTubeMusicPlaylistClick(playlist) },
                                                    onShowSnackbar = { message ->
                                                        scope.launch {
                                                            snackbarHostState.showNeriSnackbar(message)
                                                        }
                                                    },
                                                    offlineMode = offlineMode
                                                )
                                            }
                                        }
                                    }
                                    ui.ytMusicPlaylists.loading -> {
                                        item(
                                            key = registerGridItemKey(HomeScrollKeyYtMoreLoading),
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            SectionLoadingState(homeLoadingText)
                                        }
                                    }
                                    ui.ytMusicPlaylists.error != null -> {
                                        item(
                                            key = registerGridItemKey(HomeScrollKeyYtMoreError),
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            SectionErrorState(detail = ui.ytMusicPlaylists.error ?: "")
                                        }
                                    }
                                }

                                when {
                                    ytmSections.remaining.any { shelf ->
                                        shelf.shouldRenderAsSongShelf() || shelf.hasRenderablePlaylistItems()
                                    } -> {
                                        ytmSections.remaining.forEachIndexed { shelfIndex, shelf ->
                                            val shelfKey = homeYtMusicShelfScrollKey(
                                                shelfIndex = shelfIndex,
                                                title = shelf.title
                                            )
                                            if (shelf.shouldRenderAsSongShelf()) {
                                                addYouTubeMusicSongShelfSection(
                                                    sectionKey = shelfKey,
                                                    registerKey = ::registerGridItemKey,
                                                    shelf = shelf,
                                                    icon = Icons.Outlined.Explore,
                                                    title = shelf.title,
                                                    onSongClick = onSongClick,
                                                    favoriteSongs = favoriteSongs,
                                                    onFavoriteToggle = ::toggleHomeSongFavorite,
                                                    onShowSnackbar = showHomeSnackbar,
                                                    offlineMode = offlineMode
                                                )
                                            } else {
                                                val playlistItems = shelf.items.filter { it.isPlaylistItem() }
                                                if (playlistItems.isEmpty()) {
                                                    return@forEachIndexed
                                                }
                                                item(
                                                    key = registerGridItemKey("$shelfKey:header"),
                                                    span = { GridItemSpan(maxLineSpan) }
                                                ) {
                                                    SectionHeader(
                                                        icon = Icons.Outlined.Explore,
                                                        title = shelf.title
                                                    )
                                                }
                                                playlistItems.forEachIndexed { itemIndex, homeItem ->
                                                    item(
                                                        key = registerGridItemKey(
                                                            homeYtMusicHomeItemScrollKey(
                                                                shelfKey = shelfKey,
                                                                itemIndex = itemIndex,
                                                                item = homeItem
                                                            )
                                                        )
                                                    ) {
                                                        YtMusicHomeItemCard(
                                                            item = homeItem,
                                                            isFavorite = homeItem.toPlaylist()
                                                                ?.favoriteId()
                                                                ?.let { favoriteKeys.contains("youtubeMusic:$it") } == true,
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
                                                                    snackbarHostState.showNeriSnackbar(message)
                                                                }
                                                            },
                                                            offlineMode = offlineMode
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    ui.ytMusicHomeShelves.loading -> {
                                        item(
                                            key = registerGridItemKey(HomeScrollKeyYtShelvesLoading),
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            SectionLoadingState(homeLoadingText)
                                        }
                                    }
                                    ui.ytMusicHomeShelves.error != null -> {
                                        item(
                                            key = registerGridItemKey(HomeScrollKeyYtShelvesError),
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            SectionErrorState(detail = ui.ytMusicHomeShelves.error ?: "")
                                        }
                                    }
                                }
                            }

                            if (!hasVisibleYtMusicFeed && (showNeteaseTrending || showNeteaseRadar || showRecommendedCard)) {
                                when {
                                    ui.ytMusicHomeShelves.loading -> {
                                        item(
                                            key = registerGridItemKey(HomeScrollKeyYtEmptyFeedLoading),
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            SectionLoadingState(homeLoadingText)
                                        }
                                    }
                                    ui.ytMusicHomeShelves.error != null -> {
                                        item(
                                            key = registerGridItemKey(HomeScrollKeyYtEmptyFeedError),
                                            span = { GridItemSpan(maxLineSpan) }
                                        ) {
                                            SectionErrorState(detail = ui.ytMusicHomeShelves.error ?: "")
                                        }
                                    }
                                }
                            }
                        } else {
                            if (showNeteaseRadar) {
                                ui.radarSongSections
                                    .filter { it.source == NeteaseHomeSongSource.PERSONAL_RADAR }
                                    .forEach { sectionState ->
                                    val sectionKey = homeNeteaseSongSectionKey(
                                        group = "radar",
                                        source = sectionState.source
                                    )
                                    addNeteaseSongSection(
                                        sectionKey = sectionKey,
                                        registerKey = ::registerGridItemKey,
                                        sectionState = sectionState,
                                        icon = neteaseSongSectionIcon(sectionState.source),
                                        loadingText = homeLoadingText,
                                        onSongClick = onSongClick,
                                        favoriteSongs = favoriteSongs,
                                        onFavoriteToggle = ::toggleHomeSongFavorite,
                                        onShowSnackbar = showHomeSnackbar,
                                        offlineMode = offlineMode
                                    )
                                }

                                val radarPlaylistState = ui.radarPlaylists
                                item(
                                    key = registerGridItemKey(
                                        HomeScrollKeyNeteaseRadarPlaylistsHeader
                                    ),
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    SectionHeader(
                                        icon = Icons.Outlined.Explore,
                                        title = stringResource(R.string.home_netease_radar_playlists)
                                    )
                                }
                                sectionContent(
                                    section = radarPlaylistState,
                                    loadingText = homeLoadingText,
                                    errorDetail = radarPlaylistState.error,
                                    keyPrefix = HomeScrollKeyNeteaseRadarPlaylists,
                                    registerKey = ::registerGridItemKey
                                ) {
                                    item(
                                        key = registerGridItemKey(HomeScrollKeyNeteaseRadarPlaylistsContent),
                                        span = { GridItemSpan(maxLineSpan) }
                                    ) {
                                        RadarPlaylistStrip(
                                            playlists = radarPlaylistState.items,
                                            favoriteKeys = favoriteKeys,
                                            listState = radarPlaylistListState,
                                            onClick = onItemClick,
                                            onShowSnackbar = { message ->
                                                scope.launch {
                                                    snackbarHostState.showNeriSnackbar(message)
                                                }
                                            },
                                            offlineMode = offlineMode
                                        )
                                    }
                                }

                                ui.radarSongSections
                                    .filter { it.source != NeteaseHomeSongSource.PERSONAL_RADAR }
                                    .forEach { sectionState ->
                                        val sectionKey = homeNeteaseSongSectionKey(
                                            group = "radar",
                                            source = sectionState.source
                                        )
                                        addNeteaseSongSection(
                                            sectionKey = sectionKey,
                                            registerKey = ::registerGridItemKey,
                                            sectionState = sectionState,
                                            icon = neteaseSongSectionIcon(sectionState.source),
                                            loadingText = homeLoadingText,
                                            onSongClick = onSongClick,
                                            favoriteSongs = favoriteSongs,
                                            onFavoriteToggle = ::toggleHomeSongFavorite,
                                            onShowSnackbar = showHomeSnackbar,
                                            offlineMode = offlineMode
                                        )
                                    }
                            }

                            if (showNeteaseTrending) {
                                ui.trendingSongSections.forEach { sectionState ->
                                    val sectionKey = homeNeteaseSongSectionKey(
                                        group = "trending",
                                        source = sectionState.source
                                    )
                                    addNeteaseSongSection(
                                        sectionKey = sectionKey,
                                        registerKey = ::registerGridItemKey,
                                        sectionState = sectionState,
                                        icon = neteaseSongSectionIcon(sectionState.source),
                                        loadingText = homeLoadingText,
                                        onSongClick = onSongClick,
                                        favoriteSongs = favoriteSongs,
                                        onFavoriteToggle = ::toggleHomeSongFavorite,
                                        onShowSnackbar = showHomeSnackbar,
                                        offlineMode = offlineMode
                                    )
                                }
                            }

                            if (showRecommendedCard) {
                                ui.playlistSections.forEach { sectionState ->
                                    addNeteasePlaylistSection(
                                        sectionKey = homeNeteasePlaylistSectionKey(sectionState.source),
                                        registerKey = ::registerGridItemKey,
                                        sectionState = sectionState,
                                        loadingText = homeLoadingText,
                                        favoriteKeys = favoriteKeys,
                                        onItemClick = onItemClick,
                                        onShowSnackbar = { message ->
                                            scope.launch {
                                                snackbarHostState.showNeriSnackbar(message)
                                            }
                                        },
                                        offlineMode = offlineMode
                                    )
                                }
                            }
                        }
                    }
                }
                SideEffect {
                    onScrollAnchorIndexesChanged(scrollAnchorIndexes.toMap())
                }
            }
        }

        NeriOverlaySnackbarHost(
            hostState = snackbarHostState,
            bottomPadding = LocalMiniPlayerHeight.current
        )
    }
}

private fun <T> LazyGridScope.sectionContent(
    section: HomeSectionState<T>,
    loadingText: String,
    errorDetail: String?,
    keyPrefix: String,
    registerKey: (String) -> String,
    content: LazyGridScope.() -> Unit
) {
    when {
        section.items.isNotEmpty() -> content()
        section.loading -> {
            item(
                key = registerKey("$keyPrefix:loading"),
                span = { GridItemSpan(maxLineSpan) }
            ) {
                SectionLoadingState(loadingText)
            }
        }

        !errorDetail.isNullOrBlank() -> {
            item(
                key = registerKey("$keyPrefix:error"),
                span = { GridItemSpan(maxLineSpan) }
            ) {
                SectionErrorState(errorDetail)
            }
        }
    }
}

private fun LazyGridScope.addNeteaseSongSection(
    sectionKey: String,
    registerKey: (String) -> String,
    sectionState: HomeNeteaseSongSectionState,
    icon: ImageVector,
    loadingText: String,
    onSongClick: (List<SongItem>, Int) -> Unit,
    favoriteSongs: List<SongItem>,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    item(
        key = registerKey("$sectionKey:header"),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        SectionHeader(
            icon = icon,
            title = stringResource(sectionState.source.titleRes)
        )
    }
    sectionContent(
        section = sectionState.section,
        loadingText = loadingText,
        errorDetail = sectionState.section.error,
        keyPrefix = sectionKey,
        registerKey = registerKey
    ) {
        item(
            key = registerKey("$sectionKey:content"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            ResponsiveSongPagerList(
                songs = sectionState.section.items,
                onSongClick = onSongClick,
                favoriteSongs = favoriteSongs,
                onFavoriteToggle = onFavoriteToggle,
                onShowSnackbar = onShowSnackbar,
                offlineMode = offlineMode
            )
        }
    }
}

private fun LazyGridScope.addNeteasePlaylistSection(
    sectionKey: String,
    registerKey: (String) -> String,
    sectionState: HomeNeteasePlaylistSectionState,
    loadingText: String,
    favoriteKeys: Set<String>,
    onItemClick: (PlaylistSummary) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    item(
        key = registerKey("$sectionKey:header"),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        SectionHeader(
            icon = Icons.Outlined.Star,
            title = stringResource(sectionState.source.titleRes)
        )
    }
    when {
        sectionState.section.items.isNotEmpty() -> {
            sectionState.section.items.forEach { playlist ->
                item(
                    key = registerKey(
                        homeNeteasePlaylistScrollKey(sectionKey, playlist.id)
                    )
                ) {
                    PlaylistCard(
                        playlist = playlist,
                        isFavorite = favoriteKeys.contains("netease:${playlist.id}"),
                        onClick = { onItemClick(playlist) },
                        onShowSnackbar = onShowSnackbar,
                        offlineMode = offlineMode
                    )
                }
            }
        }
        sectionState.section.loading -> {
            item(
                key = registerKey("$sectionKey:loading"),
                span = { GridItemSpan(maxLineSpan) }
            ) {
                SectionLoadingState(loadingText)
            }
        }
        !sectionState.section.error.isNullOrBlank() -> {
            item(
                key = registerKey("$sectionKey:error"),
                span = { GridItemSpan(maxLineSpan) }
            ) {
                SectionErrorState(detail = sectionState.section.error.orEmpty())
            }
        }
    }
}

private fun neteaseSongSectionIcon(source: NeteaseHomeSongSource): ImageVector {
    return when (source) {
        NeteaseHomeSongSource.PERSONAL_RADAR -> Icons.Outlined.Radar
        NeteaseHomeSongSource.TOP_SOARING,
        NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS,
        NeteaseHomeSongSource.TOP_HOT,
        NeteaseHomeSongSource.TOP_NEW -> Icons.Outlined.Bolt
        NeteaseHomeSongSource.DAILY_RECOMMEND,
        NeteaseHomeSongSource.PRIVATE_FM -> Icons.Outlined.Explore
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
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
    onClick: () -> Unit,
    isFavorite: Boolean,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val coverUrl = rememberSongDisplayCoverUrl(song)
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
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
                model = fastScrollableImageRequest(
                    context = context,
                    data = coverUrl,
                    sizePx = 128,
                    offlineMode = offlineMode
                ),
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

        Column(
            Modifier
                .weight(1f)
                .padding(end = 4.dp)
        ) {
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

        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.common_more_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_playlist_play_next)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        PlayerManager.addToQueueNext(song)
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_add_to_end)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        PlayerManager.addToQueueEnd(song)
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFavorite) {
                                    R.string.favorite_remove
                                } else {
                                    R.string.favorite_add
                                }
                            )
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
                        onFavoriteToggle(song, isFavorite)
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_song_info)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText("text", buildHomeSongInfo(song))
                                )
                            )
                            onShowSnackbar(composeResources.getString(R.string.toast_copied))
                        }
                        showMenu = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RadarPlaylistStrip(
    playlists: List<PlaylistSummary>,
    favoriteKeys: Set<String>,
    listState: LazyListState,
    onClick: (PlaylistSummary) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val cardWidth = if (currentWindowWidthDp() >= 600.dp) 172.dp else 148.dp
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        playlists.forEach { playlist ->
            item(key = homeNeteaseRadarPlaylistScrollKey(playlist.id)) {
                RadarPlaylistCard(
                    playlist = playlist,
                    isFavorite = favoriteKeys.contains("netease:${playlist.id}"),
                    onClick = { onClick(playlist) },
                    onShowSnackbar = onShowSnackbar,
                    offlineMode = offlineMode,
                    modifier = Modifier.width(cardWidth)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadarPlaylistCard(
    playlist: PlaylistSummary,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    var showMenu by remember { mutableStateOf(false) }
    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (playlist.picUrl.isNotBlank()) {
                AsyncImage(
                    model = fastScrollableImageRequest(
                        context = context,
                        data = playlist.picUrl,
                        sizePx = 384,
                        offlineMode = offlineMode
                    ),
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(42.dp)
                )
            }
            Text(
                text = stringResource(R.string.home_netease_radar_badge),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.86f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    playlist: PlaylistSummary,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onShowSnackbar: (String) -> Unit = {},
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    var showMenu by remember { mutableStateOf(false) }

    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
        AsyncImage(
            model = fastScrollableImageRequest(
                context = context,
                data = playlist.picUrl,
                sizePx = 384,
                offlineMode = offlineMode
            ),
            contentDescription = playlist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
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
    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
    ) {
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
                .clip(RoundedCornerShape(8.dp))
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
private fun YtMusicHomeItemCard(
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
    val unfavoritedText = stringResource(R.string.home_unfavorited)
    val favoriteSuccessText = stringResource(R.string.favorite_success)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
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
                .clip(RoundedCornerShape(8.dp))
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
    sectionKey: String,
    registerKey: (String) -> String,
    shelf: YouTubeMusicHomeShelf,
    icon: ImageVector,
    title: String,
    onSongClick: (List<SongItem>, Int) -> Unit,
    favoriteSongs: List<SongItem>,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val songs = shelf.items.mapNotNull { it.toPlayableSongItem(shelf.title) }
    if (songs.isEmpty()) {
        return
    }
    item(
        key = registerKey("$sectionKey:header"),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        SectionHeader(
            icon = icon,
            title = title
        )
    }
    item(
        key = registerKey("$sectionKey:content"),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        val warmupKey = remember(songs) {
            songs.joinToString("|") { song ->
                song.audioId ?: song.mediaUri.orEmpty()
            }
        }
        LaunchedEffect(warmupKey) {
            PlayerManager.prefetchYouTubePlayableUrlWindow(
                playlist = songs,
                startIndex = 0,
                source = "yt_home_shelf_visible"
            )
        }
        ResponsiveSongPagerList(
            songs = songs,
            onSongClick = onSongClick,
            favoriteSongs = favoriteSongs,
            onFavoriteToggle = onFavoriteToggle,
            onShowSnackbar = onShowSnackbar,
            offlineMode = offlineMode
        )
    }
}

@Composable
private fun ContinueSection(
    items: List<UsageEntry>,
    localPlaylistLookup: Map<Long, LocalPlaylist>,
    localFilesCoverCandidates: List<SongItem>,
    onClick: (UsageEntry) -> Unit,
    savedPage: Int,
    onPageChanged: (Int) -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardsPerPage = remember(maxWidth) {
            resolveHomeContinueCardsPerPage(maxWidth.value)
        }
        val cardWidth = remember(maxWidth, cardsPerPage) {
            resolveHomeContinueCardWidthDp(
                containerWidthDp = maxWidth.value,
                cardsPerPage = cardsPerPage
            ).dp
        }
        val pageCount = remember(items.size, cardsPerPage) {
            ceil(items.size / cardsPerPage.toFloat()).toInt().coerceAtLeast(1)
        }
        val initialPage = remember(savedPage, pageCount) {
            resolveHomeContinuePagerPage(savedPage, pageCount)
        }
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = { pageCount }
        )
        LaunchedEffect(pagerState.currentPage) {
            onPageChanged(pagerState.currentPage)
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
        ) { page ->
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HomeContinueHorizontalPaddingDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(HomeContinueCardSpacingDp.dp)
                ) {
                    repeat(cardsPerPage) { slot ->
                        val entry = items.getOrNull(page * cardsPerPage + slot)
                        if (entry == null) {
                            Spacer(Modifier.width(cardWidth))
                        } else {
                            val localPlaylist = if (
                                entry.source == PlaylistUsageRepository.SOURCE_LOCAL
                            ) {
                                localPlaylistLookup[entry.id]
                            } else {
                                null
                            }
                            ContinueCard(
                                entry = entry,
                                localPlaylist = localPlaylist,
                                localFilesCoverCandidates = localFilesCoverCandidates,
                                onClick = { onClick(entry) },
                                onRemove = {
                                    AppContainer.launchBackgroundIo {
                                        AppContainer.playlistUsageRepo.removeEntry(
                                            entry.id,
                                            entry.source,
                                            entry.subtype
                                        )
                                    }
                                },
                                offlineMode = offlineMode,
                                modifier = Modifier.width(cardWidth)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    entry: UsageEntry,
    localPlaylist: LocalPlaylist?,
    localFilesCoverCandidates: List<SongItem>,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = androidx.compose.ui.platform.LocalView.current
    var showMenu by remember { mutableStateOf(false) }
    val displayName = remember(entry.id, entry.name, entry.source, configuration) {
        SystemLocalPlaylists.resolve(entry.id, entry.name, context)?.currentName ?: entry.name
    }
    val shouldResolveLocalCover = shouldResolveHomeContinueLocalCoverFallback(
        persistedCoverUrl = entry.picUrl,
        localPlaylist = localPlaylist
    )
    val resolvedLocalCoverUrl = if (shouldResolveLocalCover) {
        val playlist = requireNotNull(localPlaylist)
        rememberPlaylistDisplayCoverUrl(
            playlist = playlist,
            additionalCoverCandidates = if (playlist.id == LocalFilesPlaylist.SYSTEM_ID) {
                localFilesCoverCandidates
            } else {
                emptyList()
            },
            preferredCoverUrl = entry.picUrl
        )
    } else {
        null
    }
    val coverUrl = resolvedLocalCoverUrl
        ?.takeIf { it.isNotBlank() }
        ?: entry.picUrl?.takeIf { it.isNotBlank() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showMenu = true
                }
            )
    ) {
        AsyncImage(
            model = fastScrollableImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 384,
                offlineMode = offlineMode
            ),
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
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
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = null
                    )
                },
                onClick = {
                    showMenu = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun ResponsiveSongPagerList(
    songs: List<SongItem>,
    onSongClick: (List<SongItem>, Int) -> Unit,
    favoriteSongs: List<SongItem>,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val widthDp = currentWindowWidthDp().value
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
                                onClick = { onSongClick(songs, absoluteIndex) },
                                isFavorite = favoriteSongs.any { it.sameIdentityAs(song) },
                                onFavoriteToggle = onFavoriteToggle,
                                onShowSnackbar = onShowSnackbar,
                                offlineMode = offlineMode
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

internal fun resolveHomeContinueCardsPerPage(containerWidthDp: Float): Int {
    val preferredMinimumSlots = if (containerWidthDp >= HomeContinueThreeSlotWidthDp) 3 else 2
    val availableWidth = (containerWidthDp - HomeContinueHorizontalPaddingDp * 2f)
        .coerceAtLeast(0f)
    val slotsNeededToAvoidSlack = ceil(
        (availableWidth + HomeContinueCardSpacingDp) /
            (HomeContinueCardMaxWidthDp + HomeContinueCardSpacingDp)
    ).toInt()
    val tabletMinimumSlots = if (containerWidthDp >= HomeContinueTabletWidthDp) 4 else 0
    return maxOf(preferredMinimumSlots, tabletMinimumSlots, slotsNeededToAvoidSlack, 1)
}

internal fun resolveHomeContinueCardWidthDp(
    containerWidthDp: Float,
    cardsPerPage: Int
): Float {
    val slots = cardsPerPage.coerceAtLeast(1)
    val availableWidth = containerWidthDp -
        HomeContinueHorizontalPaddingDp * 2f -
        HomeContinueCardSpacingDp * (slots - 1)
    return (availableWidth / slots)
        .coerceAtLeast(0f)
}

internal fun buildHomeSongInfo(song: SongItem): String {
    return "${song.displayName()}-${song.displayArtist()}"
}
