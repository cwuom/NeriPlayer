package moe.ouom.neriplayer.ui.screen.tab.home

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
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeScreen
 * Created: 2025/8/8
 */

import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
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
import androidx.compose.foundation.lazy.items
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
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenu
import moe.ouom.neriplayer.ui.component.overlay.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import moe.ouom.neriplayer.data.playlist.usage.buildLocalPlaylistUsageLookup
import moe.ouom.neriplayer.data.playlist.usage.resolveLocalPlaylistUsageId
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.ui.util.shouldAllowCollapsingTopAppBar
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteasePlaylistSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeNeteaseSongSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomePlaylistSource
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
import moe.ouom.neriplayer.util.media.isRemoteImageSource
import moe.ouom.neriplayer.util.format.formatPlayCount
import kotlin.math.ceil
import kotlin.math.min
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    showContinueCard: Boolean = true,
    showTrendingCard: Boolean = true,
    showRadarCard: Boolean = true,
    showRecommendedCard: Boolean = true,
    showBilibiliCard: Boolean = true,
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
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onBiliMusicRankMore: (BiliMusicRankType) -> Unit = {},
    onBiliMusicHotMore: () -> Unit = {},
    onBiliMusicRankPlay: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    onBiliMusicHotPlay: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    /** 点击网易云歌曲小模块标题，进入该小模块的详情页。 */
    onNeteaseSongSectionMore: (NeteaseHomeSongSource) -> Unit = {},
    /** 点击网易云歌单小模块标题，进入该分类的更多歌单页。 */
    onNeteasePlaylistSectionMore: (NeteaseHomePlaylistSource) -> Unit = {}
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
    val downloadedPlaybackCoverCandidates = remember(downloadedSongs) {
        homeLocalFilesCoverCandidates(downloadedSongs)
    }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val favoriteKeys = remember(favorites) {
        favorites.mapTo(mutableSetOf()) { "${it.source}:${it.id}" }
    }
    val favoriteSongLookup = remember(localPlaylistSnapshotVersion, context) {
        FavoriteSongLookup(
            localPlaylists
                .firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, context) }
                ?.songs
                .orEmpty()
        )
    }
    val localPlaylistCoverFallbackIds = remember(usageEntries, appContext) {
        usageEntries.asSequence()
            .take(HomeContinueEntryLimit)
            .filter { entry ->
                entry.source == PlaylistUsageRepository.SOURCE_LOCAL &&
                    shouldValidateHomeContinueCoverReference(entry.picUrl)
            }
            .mapTo(linkedSetOf()) { entry -> resolveLocalPlaylistUsageId(entry, appContext) }
    }
    val localPlaylistUsageLookup by produceState(
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
        stringResource(CoreCommonR.string.app_name),
        stringResource(CoreCommonR.string.home_title_brand_loud),
        stringResource(CoreCommonR.string.home_title_brand_wave),
        stringResource(CoreCommonR.string.home_title_brand_call)
    ).distinct()
    val titleSeed = rememberSaveable { (0..Int.MAX_VALUE).random() }
    val appBarTitle = titleOptions[titleSeed % titleOptions.size]
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        // 松手吸附到完全展开或完全收起, 半折叠时大标题会被裁成一道残影; 临界阻尼不回弹
        snapAnimationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        canScroll = {
            shouldAllowCollapsingTopAppBar(
                canScrollForward = gridState.canScrollForward,
                canScrollBackward = gridState.canScrollBackward,
                collapsedFraction = topAppBarState.collapsedFraction
            )
        }
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val guessYouLikeTitle = stringResource(CoreCommonR.string.home_ytmusic_guess_you_like)
    val dailyDiscoverTitle = stringResource(CoreCommonR.string.home_ytmusic_daily_discover)
    val moreRecommendationsTitle = stringResource(CoreCommonR.string.home_ytmusic_more_recommendations)
    val favoriteAddedText = stringResource(CoreCommonR.string.favorite_added)
    val favoriteRemovedText = stringResource(CoreCommonR.string.favorite_removed)
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
    val showBiliMusic = showBilibiliCard && ui.biliMusicHomeEnabled
    val showNeteaseTrending = showTrendingCard
    val showNeteaseRadar = showRadarCard
    val showOnlineFeeds = !offlineMode
    var wasOffline by remember { mutableStateOf(offlineMode) }
    val windowWidthDp = currentWindowWidthDp()
    val isTabletLayout = windowWidthDp >= 720.dp
    val isTabletDevice =
        LocalConfiguration.current.smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP
    val cleanStyle = NeriDesign.isClean
    val gridSpec = homeGridSpec(isTabletLayout, cleanStyle)
    val pageHorizontalPadding = gridSpec.pageHorizontalPadding
    val gridContentPadding = gridSpec.contentPadding
    val gridSpacing = gridSpec.spacing
    val gridColumns = remember(gridSpec) { gridSpec.columns }
    // 大标题与卡片左边缘对齐，扣掉 TopAppBar 自带的标题内边距
    val appBarTitleInset = (pageHorizontalPadding + gridContentPadding - TopAppBarTitleInset).coerceAtLeast(0.dp)

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
    val showHomeSnackbar: (String) -> Unit = remember(scope, snackbarHostState) {
        { message ->
            scope.launch {
                snackbarHostState.showNeriSnackbar(message)
            }
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

    val hasVisibleBiliMusicSections = showBiliMusic && showOnlineFeeds && (
            ui.biliMusicRank.songs.isNotEmpty() ||
                    ui.biliMusicRank.loading ||
                    ui.biliMusicRank.error != null ||
                    ui.biliMusicHot.items.isNotEmpty() ||
                    ui.biliMusicHot.loading ||
                    ui.biliMusicHot.error != null
            )

    // 大模块顺序来自设置页
    val majorOrder = remember(ui.layout.majorOrder, isInternational) {
        resolveHomeMajorOrder(
            order = ui.layout.majorOrder,
            internationalEnabled = isInternational
        )
    }

    val hasVisibleSections =
            showContinue || hasVisibleBiliMusicSections ||
                    (showOnlineFeeds && (showNeteaseTrending || showNeteaseRadar || showRecommendedCard || isInternational))

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = if (isTabletDevice) 8.dp else 0.dp)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            val greeting = rememberHomeGreeting()
            LargeTopAppBar(
                title = { Text(appBarTitle, modifier = Modifier.padding(start = appBarTitleInset)) },
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
                            contentDescription = stringResource(CoreCommonR.string.recommend_refresh)
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
            HomeGreetingLine(
                text = greeting,
                collapsedFraction = { topAppBarState.collapsedFraction },
                modifier = Modifier
                    .widthIn(max = 1240.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = pageHorizontalPadding + gridContentPadding)
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
                                stringResource(CoreCommonR.string.home_offline_no_continue)
                            } else {
                                stringResource(CoreCommonR.string.home_all_cards_hidden)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Box
                }

                val miniPlayerHeight = LocalMiniPlayerHeight.current
                val homeLoadingText = stringResource(CoreCommonR.string.home_loading)
                val scrollAnchorIndexes = linkedMapOf<String, Int>()
                var nextGridItemIndex = 0
                fun registerGridItemKey(key: String): String {
                    scrollAnchorIndexes[key] = nextGridItemIndex
                    nextGridItemIndex += 1
                    return key
                }
                val majorEnv = HomeMajorSectionEnv(
                    ui = ui,
                    showContinue = showContinue,
                    showTrending = showNeteaseTrending,
                    showRadar = showNeteaseRadar,
                    showRecommended = showRecommendedCard,
                    showBiliMusic = showBiliMusic,
                    showOnlineFeeds = showOnlineFeeds,
                    usageEntries = usageEntries,
                    usageLoaded = usageLoaded,
                    localPlaylistUsageLookup = localPlaylistUsageLookup,
                    downloadedPlaybackCoverCandidates = downloadedPlaybackCoverCandidates,
                    continuePagerPage = continuePagerPage,
                    onContinuePagerPageChanged = onContinuePagerPageChanged,
                    onOpenRecent = onOpenRecent,
                    onSongClick = onSongClick,
                    onItemClick = onItemClick,
                    onYouTubeMusicPlaylistClick = onYouTubeMusicPlaylistClick,
                    favoriteSongLookup = favoriteSongLookup,
                    downloadPresenceVersion = downloadPresenceVersion,
                    favoriteKeys = favoriteKeys,
                    onFavoriteToggle = ::toggleHomeSongFavorite,
                    onShowSnackbar = showHomeSnackbar,
                    offlineMode = offlineMode,
                    radarPlaylistListState = radarPlaylistListState,
                    loadingText = homeLoadingText,
                    guessYouLikeTitle = guessYouLikeTitle,
                    dailyDiscoverTitle = dailyDiscoverTitle,
                    moreRecommendationsTitle = moreRecommendationsTitle,
                    ytmSections = ytmSections,
                    registerKey = ::registerGridItemKey,
                    onBiliMusicRankMore = { onBiliMusicRankMore(ui.biliMusicRank.type) },
                    onBiliMusicHotMore = onBiliMusicHotMore,
                    onBiliMusicRankPlay = onBiliMusicRankPlay,
                    onBiliMusicHotPlay = onBiliMusicHotPlay,
                    onBiliMusicTypeSelected = vm::selectBiliMusicRankType,
                    onRetryBiliMusicRank = { vm.loadBiliMusicRank(force = true) },
                    onRetryBiliMusicHot = { vm.loadBiliMusicHot(force = true) },
                    onSongSectionMore = onNeteaseSongSectionMore,
                    onPlaylistSectionMore = onNeteasePlaylistSectionMore
                )

                LazyVerticalGrid(
                    state = gridState,
                    columns = gridColumns,
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
                    majorOrder.forEach { majorId ->
                        when (majorId) {
                            HomeMajorSectionIds.CONTINUE -> addHomeContinueBlock(majorEnv)
                            HomeMajorSectionIds.BILIBILI -> addHomeBiliMusicBlock(majorEnv)
                            HomeMajorSectionIds.TRENDING -> if (isInternational) {
                                addHomeYouTubeMusicTrendingBlock(majorEnv)
                            } else {
                                addHomeTrendingBlock(majorEnv)
                            }
                            HomeMajorSectionIds.RADAR -> if (isInternational) {
                                addHomeYouTubeMusicRadarBlock(majorEnv)
                            } else {
                                addHomeRadarBlock(majorEnv)
                            }
                            HomeMajorSectionIds.RECOMMENDED -> if (isInternational) {
                                addHomeYouTubeMusicRecommendedBlock(majorEnv)
                            } else {
                                addHomeRecommendedBlock(majorEnv)
                            }
                        }
                    }
                    if (isInternational) {
                        addHomeYouTubeMusicEmptyFeedBlock(majorEnv)
                    }
                }
                HomeScrollEdgeFadeOverlay(
                    gridState = gridState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
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

internal fun <T> LazyGridScope.sectionContent(
    section: HomeSectionState<T>,
    loadingText: String,
    errorDetail: UiText?,
    keyPrefix: String,
    registerKey: (String) -> String,
    content: LazyGridScope.() -> Unit
) {
    when {
        section.items.isNotEmpty() -> content()
        section.loading -> {
            item(
                key = registerKey("$keyPrefix:loading"),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeLoading
            ) {
                SectionLoadingState(loadingText)
            }
        }

        errorDetail != null -> {
            item(
                key = registerKey("$keyPrefix:error"),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeError
            ) {
                SectionErrorState(errorDetail.asString())
            }
        }
    }
}

internal fun LazyGridScope.addNeteaseSongSection(
    sectionKey: String,
    registerKey: (String) -> String,
    sectionState: HomeNeteaseSongSectionState,
    icon: ImageVector,
    loadingText: String,
    onSongClick: (List<SongItem>, Int) -> Unit,
    favoriteSongLookup: FavoriteSongLookup,
    downloadPresenceVersion: Int,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean,
    onHeaderClick: (() -> Unit)? = null
) {
    item(
        key = registerKey("$sectionKey:header"),
        span = { GridItemSpan(maxLineSpan) },
        contentType = HomeContentTypeHeader
    ) {
        SectionHeader(
            icon = icon,
            title = stringResource(sectionState.source.titleRes),
            onClick = onHeaderClick
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
            span = { GridItemSpan(maxLineSpan) },
            contentType = HomeContentTypeSongPager
        ) {
            ResponsiveSongPagerList(
                songs = sectionState.section.items,
                onSongClick = onSongClick,
                favoriteSongLookup = favoriteSongLookup,
                downloadPresenceVersion = downloadPresenceVersion,
                onFavoriteToggle = onFavoriteToggle,
                onShowSnackbar = onShowSnackbar,
                offlineMode = offlineMode
            )
        }
    }
}

internal fun LazyGridScope.addNeteasePlaylistSection(
    sectionKey: String,
    registerKey: (String) -> String,
    sectionState: HomeNeteasePlaylistSectionState,
    loadingText: String,
    favoriteKeys: Set<String>,
    onItemClick: (PlaylistSummary) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean,
    onHeaderClick: (() -> Unit)? = null
) {
    item(
        key = registerKey("$sectionKey:header"),
        span = { GridItemSpan(maxLineSpan) },
        contentType = HomeContentTypeHeader
    ) {
        SectionHeader(
            icon = Icons.Outlined.Star,
            title = stringResource(sectionState.source.titleRes),
            onClick = onHeaderClick
        )
    }
    when {
        sectionState.section.items.isNotEmpty() -> {
            sectionState.section.items.forEach { playlist ->
                item(
                    key = registerKey(
                        homeNeteasePlaylistScrollKey(sectionKey, playlist.id)
                    ),
                    contentType = HomeContentTypePlaylistCard
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
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeLoading
            ) {
                SectionLoadingState(loadingText)
            }
        }
        sectionState.section.error != null -> {
            item(
                key = registerKey("$sectionKey:error"),
                span = { GridItemSpan(maxLineSpan) },
                contentType = HomeContentTypeError
            ) {
                SectionErrorState(detail = sectionState.section.error.asString())
            }
        }
    }
}

/**
 * 解析首页大模块的实际渲染顺序。
 */
internal fun resolveHomeMajorOrder(
    order: List<String>,
    internationalEnabled: Boolean
): List<String> {
    val defaults = if (internationalEnabled) {
        HomeMajorSectionIds.DEFAULT_ORDER.filterNot { it == HomeMajorSectionIds.BILIBILI }
    } else {
        HomeMajorSectionIds.DEFAULT_ORDER
    }
    val requested = order.filter { it in defaults }.distinct()
    return requested + defaults.filterNot { it in requested }
}

internal fun neteaseSongSectionIcon(source: NeteaseHomeSongSource): ImageVector {
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
internal fun SectionLoadingState(text: String) {
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
internal fun SectionErrorState(detail: String) {
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
            text = stringResource(CoreCommonR.string.home_retry_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun shouldUseHomeOnlineRemoteCoverFastPath(
    directCover: String?,
    hasDownloadedCopy: Boolean
): Boolean = !hasDownloadedCopy &&
    !directCover.isNullOrBlank() &&
    isRemoteImageSource(directCover)

@Composable
private fun rememberHomeOnlineSongCoverUrl(
    song: SongItem,
    downloadPresenceVersion: Int
): String? {
    val directCover = remember(song.customCoverUrl, song.coverUrl, song.originalCoverUrl) {
        song.displayCoverUrl()?.trim()?.takeIf(String::isNotBlank)
    }
    val hasDownloadedCopy = remember(song, downloadPresenceVersion) {
        GlobalDownloadManager.hasDownloadedSongCached(song)
    }
    return if (shouldUseHomeOnlineRemoteCoverFastPath(directCover, hasDownloadedCopy)) {
        directCover
    } else {
        rememberSongDisplayCoverUrl(song)
    }
}

@Composable
private fun SongRowMini(
    index: Int,
    song: SongItem,
    onClick: () -> Unit,
    isFavorite: Boolean,
    downloadPresenceVersion: Int,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val coverUrl = rememberHomeOnlineSongCoverUrl(song, downloadPresenceVersion)
    val coverRequest = remember(context, coverUrl, offlineMode) {
        coverUrl?.let { url ->
            fastScrollableImageRequest(
                context = context,
                data = url,
                sizePx = 128,
                offlineMode = offlineMode
            )
        }
    }
    val subtitle = remember(song.artist, song.customArtist, song.album, context) {
        listOfNotNull(
            song.displayArtist().takeIf { it.isNotBlank() },
            song.displayAlbum(context).takeIf { it.isNotBlank() }
        ).joinToString(" / ")
    }
    var showMenu by remember { mutableStateOf(false) }
    var menuMounted by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neriRoundedShape(8.dp))
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号居中占一格, 和卡片左缘拉开距离, 两位数时也不贴边
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(28.dp)
                .padding(end = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )

        if (coverRequest != null) {
            AsyncImage(
                model = coverRequest,
                contentDescription = song.displayName(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(1f)
                    .clip(neriRoundedShape(8.dp))
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
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(
                onClick = {
                    menuMounted = true
                    showMenu = true
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(CoreCommonR.string.common_more_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (menuMounted || showMenu) {
                SongRowMiniMenu(
                    expanded = showMenu,
                    song = song,
                    isFavorite = isFavorite,
                    onDismiss = { showMenu = false },
                    onFavoriteToggle = onFavoriteToggle,
                    onShowSnackbar = onShowSnackbar
                )
            }
        }
    }
}

@Composable
private fun SongRowMiniMenu(
    expanded: Boolean,
    song: SongItem,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val composeResources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(CoreCommonR.string.local_playlist_play_next)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = null
                )
            },
            onClick = {
                PlayerManager.addToQueueNext(song)
                onDismiss()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(CoreCommonR.string.playlist_add_to_end)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    contentDescription = null
                )
            },
            onClick = {
                PlayerManager.addToQueueEnd(song)
                onDismiss()
            }
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (isFavorite) {
                            CoreCommonR.string.favorite_remove
                        } else {
                            CoreCommonR.string.favorite_add
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
                onDismiss()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(CoreCommonR.string.action_copy_song_info)) },
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
                    onShowSnackbar(composeResources.getString(CoreCommonR.string.toast_copied))
                }
                onDismiss()
            }
        )
    }
}

@Composable
private fun NeteasePlaylistFavoriteMenu(
    expanded: Boolean,
    playlist: PlaylistSummary,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
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

@Composable
internal fun RadarPlaylistStrip(
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
        items(
            items = playlists,
            key = { playlist -> homeNeteaseRadarPlaylistScrollKey(playlist.id) },
            contentType = { HomeContentTypeRadarPlaylistCard }
        ) { playlist ->
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
    var showMenu by remember { mutableStateOf(false) }
    var menuMounted by remember { mutableStateOf(false) }
    val coverRequest = remember(context, playlist.picUrl, offlineMode) {
        if (playlist.picUrl.isBlank()) {
            null
        } else {
            fastScrollableImageRequest(
                context = context,
                data = playlist.picUrl,
                sizePx = 384,
                offlineMode = offlineMode
            )
        }
    }
    val playCountText = remember(context, playlist.playCount) {
        formatPlayCount(context, playlist.playCount)
    }

    Column(
        modifier = modifier
            .clip(neriRoundedShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    menuMounted = true
                    showMenu = true
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(neriRoundedShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (coverRequest != null) {
                AsyncImage(
                    model = coverRequest,
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
                text = stringResource(CoreCommonR.string.home_netease_radar_badge),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(neriRoundedShape(8.dp))
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
                    CoreCommonR.string.home_play_count_format,
                    playCountText,
                    playlist.trackCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }

        if (menuMounted || showMenu) {
            NeteasePlaylistFavoriteMenu(
                expanded = showMenu,
                playlist = playlist,
                isFavorite = isFavorite,
                onDismiss = { showMenu = false },
                onShowSnackbar = onShowSnackbar
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
    var showMenu by remember { mutableStateOf(false) }
    var menuMounted by remember { mutableStateOf(false) }
    val cleanStyle = NeriDesign.isClean
    val cardShape = if (cleanStyle) NeriDesign.tokens.cardShape else neriRoundedShape(8.dp)
    val coverRequest = remember(context, playlist.picUrl, offlineMode) {
        fastScrollableImageRequest(
            context = context,
            data = playlist.picUrl,
            sizePx = 384,
            offlineMode = offlineMode
        )
    }
    val playCountText = remember(context, playlist.playCount) {
        formatPlayCount(context, playlist.playCount)
    }

    Column(
        modifier = Modifier
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    menuMounted = true
                    showMenu = true
                }
            )
    ) {
        if (cleanStyle) {
            val backdropRequest = remember(context, playlist.picUrl, offlineMode) {
                if (playlist.picUrl.isBlank()) {
                    null
                } else {
                    homeFeaturedLabelBackdropRequest(
                        context = context,
                        data = playlist.picUrl,
                        offlineMode = offlineMode
                    )
                }
            }
            HomeFeaturedArtwork(
                model = coverRequest,
                backdropModel = backdropRequest,
                title = playlist.name,
                subtitle = stringResource(
                    CoreCommonR.string.home_play_count_format,
                    playCountText,
                    playlist.trackCount
                ),
                clipToCardShape = false
            )
        } else {
            AsyncImage(
                model = coverRequest,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                        .clip(neriRoundedShape(8.dp))
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
                        CoreCommonR.string.home_play_count_format,
                        playCountText,
                        playlist.trackCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }

        if (menuMounted || showMenu) {
            NeteasePlaylistFavoriteMenu(
                expanded = showMenu,
                playlist = playlist,
                isFavorite = isFavorite,
                onDismiss = { showMenu = false },
                onShowSnackbar = onShowSnackbar
            )
        }
    }
}

internal data class ClassifiedYouTubeMusicShelves(
    val guessYouLike: YouTubeMusicHomeShelf?,
    val dailyDiscover: YouTubeMusicHomeShelf?,
    val remaining: List<YouTubeMusicHomeShelf>
) {
    /** 是否存在任何可渲染的 YouTube Music 内容。 */
    fun hasVisibleFeed(): Boolean {
        return guessYouLike != null ||
            dailyDiscover != null ||
            remaining.any { shelf ->
                shelf.shouldRenderAsSongShelf() || shelf.hasRenderablePlaylistItems()
            }
    }
}

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

internal fun YouTubeMusicHomeShelf.shouldRenderAsSongShelf(): Boolean {
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

internal fun YouTubeMusicHomeShelf.hasRenderablePlaylistItems(): Boolean {
    return items.any { it.isPlaylistItem() }
}

internal fun YouTubeMusicHomeItem.isPlaylistItem(): Boolean {
    val normalizedPageType = pageType.uppercase(Locale.US)
    return when {
        normalizedPageType.contains("PLAYLIST") -> true
        normalizedPageType.isNotBlank() -> false
        else -> browseId.startsWith("VL")
    }
}

internal fun YouTubeMusicHomeItem.toPlaylist(): YouTubeMusicPlaylist? {
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

internal fun LazyGridScope.addYouTubeMusicSongShelfSection(
    sectionKey: String,
    registerKey: (String) -> String,
    shelf: YouTubeMusicHomeShelf,
    icon: ImageVector,
    title: String,
    onSongClick: (List<SongItem>, Int) -> Unit,
    favoriteSongLookup: FavoriteSongLookup,
    downloadPresenceVersion: Int,
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
        span = { GridItemSpan(maxLineSpan) },
        contentType = HomeContentTypeHeader
    ) {
        SectionHeader(
            icon = icon,
            title = title
        )
    }
    item(
        key = registerKey("$sectionKey:content"),
        span = { GridItemSpan(maxLineSpan) },
        contentType = HomeContentTypeSongPager
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
            favoriteSongLookup = favoriteSongLookup,
            downloadPresenceVersion = downloadPresenceVersion,
            onFavoriteToggle = onFavoriteToggle,
            onShowSnackbar = onShowSnackbar,
            offlineMode = offlineMode
        )
    }
}

@Composable
private fun ResponsiveSongPagerList(
    songs: List<SongItem>,
    onSongClick: (List<SongItem>, Int) -> Unit,
    favoriteSongLookup: FavoriteSongLookup,
    downloadPresenceVersion: Int,
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
    val rowsPerColumn = HomeSongPagerRowsPerColumn
    val perPage = (columns * rowsPerColumn).coerceAtLeast(1)
    val clean = NeriDesign.isClean
    val overImage = LocalHasCustomBackground.current
    // 背景图上两种风格都给榜单垫一层卡片: 高动效走毛玻璃, 低动效退回半透明底色
    val chartCard = !clean || overImage
    val chartCardShape = if (clean) NeriDesign.tokens.cardShape else HomeSongPagerCardShape
    val chartCardColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(
        alpha = if (overImage) HomeOverImageCardAlpha else 1f
    )

    // 横滑榜单只展示前 28 首, 点击后仍以整个分区作为播放队列
    val visibleCount = min(songs.size, HomeSongPagerMaxSongs)
    val pageCount = ceil(visibleCount / perPage.toFloat()).toInt().coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = Modifier.fillMaxWidth()) {
    HorizontalPager(
        state = pagerState,
        key = { page -> page },
        // Material 的榜单卡与分区标题左缘对齐; Clean 是平铺行, 留出与标题文字对齐的缩进
        contentPadding = PaddingValues(horizontal = if (clean) 8.dp else 0.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) { page ->
        val start = page * perPage
        val end = min(start + perPage, visibleCount)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (columnIndex in 0 until columns) {
                if (!homeSongPagerColumnHasSongs(start, end, columnIndex, rowsPerColumn)) {
                    // 末页凑不满时空列只占位保持列宽, 不画空卡片
                    Spacer(Modifier.weight(1f))
                    continue
                }
                AdvancedGlassSurface(
                    role = AdvancedGlassRole.SettingsGroup,
                    modifier = Modifier.weight(1f),
                    shape = chartCardShape,
                    fallbackColor = if (chartCard) chartCardColor else Color.Transparent,
                    tintColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    enabled = overImage
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (chartCard) Modifier.padding(vertical = 6.dp) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(if (chartCard) 2.dp else 6.dp)
                ) {
                    for (rowIndex in 0 until rowsPerColumn) {
                        val absoluteIndex = start + (columnIndex * rowsPerColumn + rowIndex)
                        if (absoluteIndex < end) {
                            val song = songs[absoluteIndex]
                            SongRowMini(
                                index = absoluteIndex + 1,
                                song = song,
                                onClick = { onSongClick(songs, absoluteIndex) },
                                isFavorite = favoriteSongLookup.contains(song),
                                downloadPresenceVersion = downloadPresenceVersion,
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
    if (pageCount > 1) {
        // 用页码点提示可以横滑, 不露出下一页的内容
        HomePagerDots(
            pageCount = pageCount,
            currentPage = { pagerState.currentPage },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp)
        )
    }
    }
}

internal fun homeSongPagerColumnHasSongs(
    pageStart: Int,
    pageEnd: Int,
    columnIndex: Int,
    rowsPerColumn: Int
): Boolean = pageStart + columnIndex * rowsPerColumn < pageEnd

private const val HomeSongPagerRowsPerColumn = 4
internal const val HomeSongPagerMaxSongs = 28
// 背景图上的容器底色: 低动效时直接显示这层半透明, 高动效时作为毛玻璃的底色
internal const val HomeOverImageCardAlpha = 0.82f
private val HomeSongPagerCardShape = RoundedCornerShape(24.dp)

internal fun buildHomeSongInfo(song: SongItem): String {
    return "${song.displayName()}-${song.displayArtist()}"
}
