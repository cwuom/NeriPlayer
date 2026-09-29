package moe.ouom.neriplayer.ui.screen.tab.explore

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
 * File: moe.ouom.neriplayer.ui.screen.tab.explore/ExploreScreen
 * Created: 2025/8/8
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import moe.ouom.neriplayer.ui.util.shouldAllowCollapsingTopAppBar
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.api.bilibili.model.video.VideoBasicInfo
import moe.ouom.neriplayer.api.youtube.model.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.data.search.ExploreSearchHistoryRepository
import moe.ouom.neriplayer.data.search.exploreSearchHistoryRecordKeyword
import moe.ouom.neriplayer.data.search.exploreSearchHistoryForDisplay
import moe.ouom.neriplayer.data.search.resolveExploreSearchKeyword
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeExploreSearchType
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.shouldLoadExploreSearchMore
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.ui.util.copyPlainTextSafely
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.core.logging.NPLogger
import kotlin.time.Duration.Companion.milliseconds

private const val SEARCH_INPUT_DEBOUNCE_MS = 300L
private val ExplorePrimaryTabShape = RoundedCornerShape(20.dp)
private val ExploreSearchFieldShape = RoundedCornerShape(16.dp)

internal fun exploreSearchSourceDisplayOrder(
    isInternational: Boolean,
    youtubeEnabled: Boolean
): List<SearchSource> {
    return if (!youtubeEnabled) {
        listOf(SearchSource.NETEASE, SearchSource.BILIBILI, SearchSource.LINK_RECOGNITION)
    } else if (isInternational) {
        listOf(
            SearchSource.YOUTUBE_MUSIC,
            SearchSource.NETEASE,
            SearchSource.BILIBILI,
            SearchSource.LINK_RECOGNITION
        )
    } else {
        listOf(
            SearchSource.NETEASE,
            SearchSource.BILIBILI,
            SearchSource.YOUTUBE_MUSIC,
            SearchSource.LINK_RECOGNITION
        )
    }
}

internal fun shouldClearExploreSearchQuery(
    previous: SearchSource,
    current: SearchSource
): Boolean {
    return previous != current && (
        previous == SearchSource.LINK_RECOGNITION ||
            current == SearchSource.LINK_RECOGNITION
        )
}

internal fun exploreSearchScrollContextKey(
    keyword: String,
    source: SearchSource,
    neteaseSearchType: NeteaseExploreSearchType,
    youtubeSearchType: YouTubeExploreSearchType = YouTubeExploreSearchType.SONG
): String? {
    val normalizedKeyword = keyword.trim()
    if (normalizedKeyword.isBlank()) return null
    val sourceType = when (source) {
        SearchSource.NETEASE -> neteaseSearchType.name
        SearchSource.YOUTUBE_MUSIC -> youtubeSearchType.name
        else -> "-"
    }
    return "${source.name}|$sourceType|$normalizedKeyword"
}

internal fun shouldResetExploreSearchScroll(
    previousContextKey: String?,
    currentContextKey: String?
): Boolean {
    return currentContextKey != null && previousContextKey != currentContextKey
}

internal fun shouldRenderExploreSearchResults(
    page: Int,
    currentPage: Int
): Boolean = page == currentPage

internal fun exploreSearchResultsBottomPadding(miniPlayerHeight: Dp): Dp =
    16.dp + miniPlayerHeight

internal fun shouldShowBiliPartsPicker(song: SongItem): Boolean {
    return song.album == PlayerManager.BILI_SOURCE_TAG ||
        song.album.startsWith("${PlayerManager.BILI_SOURCE_TAG}|")
}

@Composable
private fun searchSourceLabel(source: SearchSource): String {
    return when (source) {
        SearchSource.YOUTUBE_MUSIC -> stringResource(R.string.explore_tab_youtube)
        SearchSource.NETEASE -> stringResource(R.string.platform_netease_short)
        SearchSource.BILIBILI -> stringResource(R.string.platform_bilibili)
        SearchSource.LINK_RECOGNITION -> stringResource(R.string.explore_tab_links)
    }
}

@Composable
private fun neteaseSearchTypeLabel(type: NeteaseExploreSearchType): String {
    return when (type) {
        NeteaseExploreSearchType.SONG -> stringResource(R.string.explore_search_type_song)
        NeteaseExploreSearchType.PLAYLIST -> stringResource(R.string.explore_search_type_playlist)
        NeteaseExploreSearchType.ARTIST -> stringResource(R.string.explore_search_type_artist)
    }
}

private fun neteaseSearchTypeIcon(type: NeteaseExploreSearchType): ImageVector {
    return when (type) {
        NeteaseExploreSearchType.SONG -> Icons.Outlined.MusicNote
        NeteaseExploreSearchType.PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
        NeteaseExploreSearchType.ARTIST -> Icons.Filled.AccountCircle
    }
}

@Composable
private fun youtubeSearchTypeLabel(type: YouTubeExploreSearchType): String {
    return when (type) {
        YouTubeExploreSearchType.SONG -> stringResource(R.string.explore_search_type_song)
        YouTubeExploreSearchType.VIDEO -> stringResource(R.string.explore_search_type_video)
        YouTubeExploreSearchType.CREATOR -> stringResource(R.string.explore_search_type_creator)
    }
}

private fun youtubeSearchTypeIcon(type: YouTubeExploreSearchType): ImageVector {
    return when (type) {
        YouTubeExploreSearchType.SONG -> Icons.Outlined.MusicNote
        YouTubeExploreSearchType.VIDEO -> Icons.Filled.PlayCircle
        YouTubeExploreSearchType.CREATOR -> Icons.Filled.AccountCircle
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ExploreScreen(
    gridState: LazyGridState,
    topAppBarState: TopAppBarState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchListState: LazyListState,
    searchScrollContextKey: String?,
    onSearchScrollContextKeyChange: (String?) -> Unit,
    offlineMode: Boolean = false,
    onPlay: (PlaylistSummary) -> Unit,
    onBiliPlaylistClick: (BiliPlaylist) -> Unit = {},
    onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    onYouTubeCreatorClick: (YouTubeMusicCreatorSummary) -> Unit = {},
    onNeteaseArtistClick: (NeteaseArtistSummary) -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onSongPlayPreservingQueue: (SongItem) -> Unit = {},
    onSongPlayNext: (SongItem) -> Unit = {},
    onSongAddToQueueEnd: (SongItem) -> Unit = {},
    onPlayParts: (VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val clipboard = LocalClipboard.current
    if (offlineMode) {
        ExploreOfflineContent(topAppBarState)
        return
    }

    val vm: ExploreViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ExploreViewModel(context.applicationContext as Application) }
        }
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val searchHistoryRepository = remember(context) {
        ExploreSearchHistoryRepository(context)
    }
    val searchHistory by searchHistoryRepository.historyFlow.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )
    val searchHistoryEnabled by AppContainer.settingsRepo.exploreSearchHistoryEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val availableSearchHistory = remember(searchHistoryEnabled, searchHistory) {
        exploreSearchHistoryForDisplay(searchHistoryEnabled, searchHistory)
    }
    val effectiveSearchKeyword = remember(searchQuery, availableSearchHistory, ui.selectedSearchSource) {
        if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
            searchQuery.trim()
        } else {
            resolveExploreSearchKeyword(searchQuery, availableSearchHistory)
        }
    }
    val visibleSearchHistory = remember(availableSearchHistory, ui.selectedSearchSource) {
        if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
            emptyList()
        } else {
            filteredExploreSearchHistory(availableSearchHistory)
        }
    }
    val backgroundImageUri by AppContainer.settingsRepo.backgroundImageUriFlow.collectAsStateWithLifecycle(
        initialValue = null
    )

    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allLocalPlaylists by repo.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylistsReady by repo.initializationReadyFlow.collectAsStateWithLifecycle(
        initialValue = false
    )
    val favoriteSongKeys = remember(allLocalPlaylists, context) {
        FavoritesPlaylist.firstOrNull(allLocalPlaylists, context)
            ?.songs
            .orEmpty()
            .mapTo(mutableSetOf()) { it.stableKey() }
    }
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val favoriteKeys = remember(favorites) {
        favorites.mapTo(mutableSetOf()) { "${it.source}:${it.id}" }
    }

    var showPartsSheet by remember { mutableStateOf(false) }
    var partsInfo by remember { mutableStateOf<VideoBasicInfo?>(null) }
    var clickedSongCoverUrl by remember { mutableStateOf("") }
    val partsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var partsSelectionMode by remember { mutableStateOf(false) }
    var selectedParts by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var showExportSheet by remember { mutableStateOf(false) }

    val isInternational by AppContainer.settingsRepo.internationalizationEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val youtubeEnabled by AppContainer.settingsRepo.youtubeEnabledFlow
        .collectAsStateWithLifecycle(initialValue = YouTubeFeatureGate.isEnabled())
    val orderedSearchSources = remember(isInternational, youtubeEnabled) {
        exploreSearchSourceDisplayOrder(isInternational, youtubeEnabled)
    }
    val initialSearchPage = remember(orderedSearchSources, ui.selectedSearchSource) {
        orderedSearchSources.indexOf(ui.selectedSearchSource).takeIf { it >= 0 } ?: 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialSearchPage,
        pageCount = { orderedSearchSources.size }
    )
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val snackbarHostState = remember { SnackbarHostState() }
    val windowWidthDp = currentWindowWidthDp()
    val isTabletLayout = windowWidthDp >= 720.dp
    val searchPanelHorizontalPadding = if (isTabletLayout) 28.dp else 16.dp
    val searchResultHorizontalPadding = if (isTabletLayout) 88.dp else 0.dp
    val youtubeGridState = rememberLazyGridState()
    val tagChipSelectedAlpha = if (backgroundImageUri == null) 1f else 0.86f
    val tagChipUnselectedAlpha = if (backgroundImageUri == null) 1f else 0.74f
    val tagChipBorderAlpha = if (backgroundImageUri == null) 1f else 0.58f
    var previousSearchSource by remember { mutableStateOf(ui.selectedSearchSource) }
    val isExploreContentScrolled by remember(
        searchQuery,
        ui.selectedSearchSource,
        searchListState,
        gridState,
        youtubeGridState,
        topAppBarState
    ) {
        derivedStateOf {
            when {
                topAppBarState.collapsedFraction > 0f -> true
                searchQuery.isNotBlank() -> searchListState.canScrollBackward
                ui.selectedSearchSource == SearchSource.NETEASE -> gridState.canScrollBackward
                ui.selectedSearchSource == SearchSource.YOUTUBE_MUSIC -> {
                    youtubeGridState.canScrollBackward
                }
                else -> false
            }
        }
    }
    val shouldShowSearchHistory = shouldShowExploreSearchHistory(
        history = visibleSearchHistory,
        contentScrolled = isExploreContentScrolled
    )
    val searchTypeBarSource = exploreSearchTypeBarSource(
        selectedSearchSource = ui.selectedSearchSource,
        contentScrolled = isExploreContentScrolled
    )

    val shouldLoadMoreSearch by remember(
        searchListState,
        ui.searchItems.size,
        ui.searchHasMore,
        ui.searching,
        ui.searchLoadingMore,
        ui.searchLoadMoreError
    ) {
        derivedStateOf {
            shouldLoadExploreSearchMore(
                resultCount = ui.searchItems.size,
                lastVisibleItemIndex = searchListState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index,
                hasMore = ui.searchHasMore,
                searching = ui.searching,
                loadingMore = ui.searchLoadingMore,
                loadMoreFailed = ui.searchLoadMoreError != null
            )
        }
    }

    fun exitPartsSelection() {
        partsSelectionMode = false
        selectedParts = emptySet()
    }

    LaunchedEffect(Unit) {
        if (ui.playlists.isEmpty()) vm.loadHighQuality()
    }

    LaunchedEffect(ui.selectedSearchSource, orderedSearchSources) {
        val targetPage = orderedSearchSources.indexOf(ui.selectedSearchSource)
            .takeIf { it >= 0 }
            ?: return@LaunchedEffect
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage, orderedSearchSources, ui.selectedSearchSource) {
        val currentSource = orderedSearchSources.getOrNull(pagerState.currentPage)
            ?: return@LaunchedEffect
        if (ui.selectedSearchSource != currentSource) {
            vm.setSearchSource(currentSource)
        }
        if (currentSource == SearchSource.YOUTUBE_MUSIC && ui.ytMusicPlaylists.isEmpty()) {
            vm.loadYtMusicPlaylists()
        }
    }

    // 国际化模式默认跳到 YouTube Music 标签
    LaunchedEffect(isInternational, youtubeEnabled) {
        if (isInternational && youtubeEnabled) {
            if (ui.selectedSearchSource != SearchSource.YOUTUBE_MUSIC) {
                vm.setSearchSource(SearchSource.YOUTUBE_MUSIC)
            }
            if (ui.ytMusicPlaylists.isEmpty()) {
                vm.loadYtMusicPlaylists()
            }
        }
    }

    // Tag keys for API calls
    val tagKeys = listOf(
        "tag_all", "tag_pop", "tag_soundtrack", "tag_chinese", "tag_nostalgia", "tag_rock", "tag_acg", "tag_western", "tag_fresh", "tag_night", "tag_children", "tag_folk", "tag_japanese", "tag_romantic",
        "tag_study", "tag_korean", "tag_work", "tag_electronic", "tag_cantonese", "tag_dance", "tag_sad", "tag_game", "tag_afternoon_tea", "tag_healing", "tag_rap", "tag_light_music"
    )

    // Translated tag labels for display
    val tagLabels = listOf(
        stringResource(R.string.tag_all), stringResource(R.string.tag_pop), stringResource(R.string.tag_soundtrack), stringResource(R.string.tag_chinese), stringResource(R.string.tag_nostalgia), stringResource(R.string.tag_rock), stringResource(R.string.tag_acg), stringResource(R.string.tag_western), stringResource(R.string.tag_fresh), stringResource(R.string.tag_night), stringResource(R.string.tag_children), stringResource(R.string.tag_folk), stringResource(R.string.tag_japanese), stringResource(R.string.tag_romantic),
        stringResource(R.string.tag_study), stringResource(R.string.tag_korean), stringResource(R.string.tag_work), stringResource(R.string.tag_electronic), stringResource(R.string.tag_cantonese), stringResource(R.string.tag_dance), stringResource(R.string.tag_sad), stringResource(R.string.tag_game), stringResource(R.string.tag_afternoon_tea), stringResource(R.string.tag_healing), stringResource(R.string.tag_rap), stringResource(R.string.tag_light_music)
    )

    // Initialize with default tag
    LaunchedEffect(Unit) {
        if (ui.selectedTag == "tag_all" && ui.playlists.isEmpty()) {
            vm.loadHighQuality("tag_all")
        }
    }

    var lastRecordedSearchKeyword by remember { mutableStateOf<String?>(null) }
    var pendingSearchHistoryRecord by remember { mutableStateOf<String?>(null) }

    fun queueExploreSearchRecord(query: String) {
        if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
            return
        }
        val keyword = exploreSearchHistoryRecordKeyword(
            query = query,
            enabled = searchHistoryEnabled,
            history = searchHistory
        ) ?: return
        if (keyword.equals(lastRecordedSearchKeyword, ignoreCase = true)) {
            return
        }
        lastRecordedSearchKeyword = keyword
        pendingSearchHistoryRecord = keyword
    }

    LaunchedEffect(pendingSearchHistoryRecord) {
        val keyword = pendingSearchHistoryRecord ?: return@LaunchedEffect
        searchHistoryRepository.record(keyword)
        if (pendingSearchHistoryRecord == keyword) {
            pendingSearchHistoryRecord = null
        }
    }

    LaunchedEffect(
        searchQuery,
        effectiveSearchKeyword,
        searchHistoryEnabled,
        ui.selectedSearchSource,
        ui.selectedNeteaseSearchType,
        ui.selectedYouTubeMusicSearchType
    ) {
        if (searchQuery.isBlank()) {
            lastRecordedSearchKeyword = null
            pendingSearchHistoryRecord = null
            vm.search("")
            return@LaunchedEffect
        }
        delay(SEARCH_INPUT_DEBOUNCE_MS.milliseconds)
        val displayQuery = searchQuery.trim()
        if (
            ui.searchKeyword != effectiveSearchKeyword ||
            ui.searchDisplayQuery != displayQuery
        ) {
            vm.search(effectiveSearchKeyword, displayQuery = displayQuery)
        }
    }

    LaunchedEffect(ui.selectedSearchSource) {
        if (shouldClearExploreSearchQuery(previousSearchSource, ui.selectedSearchSource)) {
            onSearchQueryChange("")
        }
        previousSearchSource = ui.selectedSearchSource
    }

    val currentSearchScrollContextKey = remember(
        effectiveSearchKeyword,
        ui.selectedSearchSource,
        ui.selectedNeteaseSearchType,
        ui.selectedYouTubeMusicSearchType
    ) {
        exploreSearchScrollContextKey(
            keyword = effectiveSearchKeyword,
            source = ui.selectedSearchSource,
            neteaseSearchType = ui.selectedNeteaseSearchType,
            youtubeSearchType = ui.selectedYouTubeMusicSearchType
        )
    }

    LaunchedEffect(
        currentSearchScrollContextKey,
        searchScrollContextKey
    ) {
        if (
            shouldResetExploreSearchScroll(
                previousContextKey = searchScrollContextKey,
                currentContextKey = currentSearchScrollContextKey
            )
        ) {
            searchListState.scrollToItem(0)
        }
        if (searchScrollContextKey != currentSearchScrollContextKey) {
            onSearchScrollContextKeyChange(currentSearchScrollContextKey)
        }
    }

    LaunchedEffect(
        shouldLoadMoreSearch,
        ui.searchItems.size,
        ui.selectedSearchSource,
        ui.selectedNeteaseSearchType,
        ui.selectedYouTubeMusicSearchType
    ) {
        if (shouldLoadMoreSearch) {
            vm.loadMoreSearchResults()
        }
    }

    fun submitExploreSearch(query: String = searchQuery) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        val keyword = if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
            normalizedQuery
        } else {
            resolveExploreSearchKeyword(normalizedQuery, availableSearchHistory)
        }
        onSearchQueryChange(normalizedQuery)
        focusManager.clearFocus()
        vm.search(keyword, displayQuery = normalizedQuery)
        queueExploreSearchRecord(normalizedQuery)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        canScroll = {
            when {
                searchQuery.isNotEmpty() -> shouldAllowCollapsingTopAppBar(
                    canScrollForward = searchListState.canScrollForward,
                    canScrollBackward = searchListState.canScrollBackward,
                    collapsedFraction = topAppBarState.collapsedFraction
                )
                ui.selectedSearchSource == SearchSource.NETEASE ->
                    shouldAllowCollapsingTopAppBar(
                        canScrollForward = gridState.canScrollForward,
                        canScrollBackward = gridState.canScrollBackward,
                        collapsedFraction = topAppBarState.collapsedFraction
                    )
                ui.selectedSearchSource == SearchSource.YOUTUBE_MUSIC ->
                    shouldAllowCollapsingTopAppBar(
                        canScrollForward = youtubeGridState.canScrollForward,
                        canScrollBackward = youtubeGridState.canScrollBackward,
                        collapsedFraction = topAppBarState.collapsedFraction
                    )
                else -> shouldAllowCollapsingTopAppBar(
                    canScrollForward = false,
                    canScrollBackward = false,
                    collapsedFraction = topAppBarState.collapsedFraction
                )
            }
        }
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        snackbarHost = {
            NeriSnackbarHost(
                hostState = snackbarHostState,
                bottomPadding = miniPlayerHeight
            )
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_explore)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier
                    .widthIn(max = 1040.dp)
                    .fillMaxWidth()
                    .padding(horizontal = searchPanelHorizontalPadding, vertical = 8.dp)
            ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            onSearchQueryChange(it)
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
                                        R.string.explore_link_input_label
                                    } else {
                                        R.string.search_keyword
                                    }
                                )
                            )
                        },
                        placeholder = {
                            when {
                                ui.selectedSearchSource == SearchSource.LINK_RECOGNITION -> {
                                    Text(stringResource(R.string.explore_link_input_placeholder))
                                }
                                ui.selectedSearchSource == SearchSource.NETEASE && !ui.isNeteaseLoggedIn -> {
                                    Text(stringResource(R.string.netease_login_required_search_placeholder))
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Search, "Search") },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                HapticIconButton(onClick = {
                                    onSearchQueryChange("")
                                    vm.search("")
                                }) { Icon(Icons.Default.Clear, "Clear") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            submitExploreSearch()
                        }),
                        singleLine = true,
                        shape = ExploreSearchFieldShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExploreSearchHistoryRow(
                        history = visibleSearchHistory,
                        visible = shouldShowSearchHistory,
                        query = searchQuery,
                        onHistoryClick = { item -> submitExploreSearch(item) },
                        onClearHistory = {
                            lastRecordedSearchKeyword = null
                            pendingSearchHistoryRecord = null
                            scope.launch {
                                searchHistoryRepository.clear()
                            }
                        }
                    )
                    if (ui.selectedSearchSource == SearchSource.NETEASE && !ui.isNeteaseLoggedIn) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.netease_login_required_search),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.explore_link_input_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AdvancedGlassSurface(
                        role = AdvancedGlassRole.ScreenTopTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExplorePrimaryTabShape),
                        shape = ExplorePrimaryTabShape
                    ) {
                        PrimaryScrollableTabRow(
                            selectedTabIndex = pagerState.currentPage,
                            edgePadding = 0.dp,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            orderedSearchSources.forEachIndexed { index, source ->
                                Tab(
                                    selected = pagerState.currentPage == index,
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                    text = {
                                        Text(
                                            text = searchSourceLabel(source),
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                    ExploreSearchTypeBar(
                        source = searchTypeBarSource,
                        selectedNeteaseSearchType = ui.selectedNeteaseSearchType,
                        selectedYouTubeSearchType = ui.selectedYouTubeMusicSearchType,
                        onNeteaseSearchTypeClick = vm::setNeteaseSearchType,
                        onYouTubeSearchTypeClick = vm::setYouTubeMusicSearchType,
                        selectedAlpha = tagChipSelectedAlpha,
                        unselectedAlpha = tagChipUnselectedAlpha,
                        borderAlpha = tagChipBorderAlpha
                    )
                }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val currentSource = orderedSearchSources[page]
                if (searchQuery.isNotEmpty()) {
                    if (shouldRenderExploreSearchResults(page, pagerState.currentPage)) {
                        when {
                            ui.searching -> {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = miniPlayerHeight),
                                    Alignment.Center
                                ) { CircularProgressIndicator() }
                            }
                            ui.searchError != null -> {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = miniPlayerHeight),
                                    Alignment.Center
                                ) {
                                    Text(ui.searchError!!, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            ui.searchItems.isEmpty() -> {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = miniPlayerHeight),
                                    Alignment.Center
                                ) { Text(stringResource(R.string.search_no_result)) }
                            }
                            else -> {
                                LazyColumn(
                                    state = searchListState,
                                    contentPadding = PaddingValues(
                                        start = searchResultHorizontalPadding,
                                        end = searchResultHorizontalPadding,
                                        top = 8.dp,
                                        bottom = exploreSearchResultsBottomPadding(miniPlayerHeight)
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                itemsIndexed(
                                    items = ui.searchItems,
                                    key = { _, item -> item.stableKey }
                                ) { index, item ->
                                    when (item) {
                                        is ExploreSearchResult.Song -> {
                                            val song = item.song
                                            val songListIndex = ui.searchResults.indexOfFirst {
                                                it.stableKey() == song.stableKey()
                                            }.takeIf { it >= 0 } ?: index
                                            val isFavoriteSong = favoriteSongKeys.contains(song.stableKey())
                                            SongRow(
                                                state = ExploreSongRowState(
                                                    index = index + 1,
                                                    song = song,
                                                    isFavorite = isFavoriteSong,
                                                    favoriteActionEnabled = localPlaylistsReady,
                                                    offlineMode = offlineMode
                                                ),
                                                actions = ExploreSongRowActions(
                                                    onClick = {
                                                        if (shouldShowBiliPartsPicker(song)) {
                                                            scope.launch {
                                                                try {
                                                                    val info =
                                                                        vm.getVideoInfoByAvid(song.id)
                                                                    if (info.pages.size <= 1) {
                                                                        onSongClick(
                                                                            ui.searchResults,
                                                                            songListIndex
                                                                        )
                                                                    } else {
                                                                        partsInfo = info
                                                                        clickedSongCoverUrl =
                                                                            song.coverUrl ?: ""
                                                                        showPartsSheet = true
                                                                    }
                                                                } catch (e: Exception) {
                                                                    NPLogger.e(
                                                                        "ExploreScreen",
                                                                        composeResources.getString(R.string.search_error),
                                                                        e
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            onSongClick(
                                                                ui.searchResults,
                                                                songListIndex
                                                            )
                                                        }
                                                    },
                                                    onPlayNow = { onSongPlayPreservingQueue(song) },
                                                    onPlayNext = { onSongPlayNext(song) },
                                                    onAddToQueueEnd = { onSongAddToQueueEnd(song) },
                                                    onDownload = {
                                                        GlobalDownloadManager.startDownload(
                                                            context,
                                                            song
                                                        )
                                                        scope.launch {
                                                            snackbarHostState.showNeriSnackbar(
                                                                composeResources.getString(
                                                                    R.string.download_starting,
                                                                    song.displayName()
                                                                )
                                                            )
                                                        }
                                                    },
                                                    onToggleFavorite = {
                                                        if (localPlaylistsReady) {
                                                            scope.launchLocalPlaylistMutation(
                                                                "toggleFavoriteFromExplore"
                                                            ) {
                                                                val isFavoriteAtAction =
                                                                    FavoritesPlaylist
                                                                        .firstOrNull(
                                                                            repo.playlists.value,
                                                                            context
                                                                        )
                                                                        ?.songs
                                                                        ?.any {
                                                                            it.sameIdentityAs(
                                                                                song
                                                                            )
                                                                        } == true
                                                                if (isFavoriteAtAction) {
                                                                    repo.removeFromFavorites(song)
                                                                } else {
                                                                    repo.addToFavorites(song)
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onCopyInfo = {
                                                        scope.launch {
                                                            val result =
                                                                clipboard.copyPlainTextSafely(
                                                                    label = "text",
                                                                    text = buildExploreSongInfo(
                                                                        song
                                                                    )
                                                                )
                                                            snackbarHostState.showNeriSnackbar(
                                                                composeResources.getString(
                                                                    exploreClipboardMessageRes(
                                                                        result
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                            )
                                        }
                                        is ExploreSearchResult.Playlist -> {
                                            NeteasePlaylistSearchRow(
                                                playlist = item.playlist,
                                                offlineMode = offlineMode,
                                                onClick = { onPlay(item.playlist) }
                                            )
                                        }
                                        is ExploreSearchResult.YouTubePlaylist -> {
                                            YouTubePlaylistSearchRow(
                                                playlist = item.playlist,
                                                offlineMode = offlineMode,
                                                onClick = { onYouTubeMusicPlaylistClick(item.playlist) }
                                            )
                                        }
                                        is ExploreSearchResult.BilibiliPlaylist -> {
                                            BiliPlaylistSearchRow(
                                                playlist = item.playlist,
                                                offlineMode = offlineMode,
                                                onClick = { onBiliPlaylistClick(item.playlist) }
                                            )
                                        }
                                        is ExploreSearchResult.Artist -> {
                                            NeteaseArtistSearchRow(
                                                result = item.result,
                                                offlineMode = offlineMode,
                                                onClick = { onNeteaseArtistClick(item.result.artist) }
                                            )
                                        }
                                        is ExploreSearchResult.YouTubeCreator -> {
                                            YouTubeCreatorSearchRow(
                                                creator = item.creator,
                                                offlineMode = offlineMode,
                                                onClick = { onYouTubeCreatorClick(item.creator) }
                                            )
                                        }
                                        is ExploreSearchResult.Notice -> {
                                            ExploreSearchNoticeRow(
                                                item
                                            )
                                        }
                                    }
                                }
                                if (ui.searchLoadingMore) {
                                    item(key = "search-loading-more") {
                                        SearchLoadingMoreRow()
                                    }
                                } else if (ui.searchLoadMoreError != null) {
                                    item(key = "search-load-more-error") {
                                        SearchLoadMoreErrorRow(
                                            error = ui.searchLoadMoreError!!,
                                            onRetry = vm::loadMoreSearchResults
                                        )
                                    }
                                }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                } else {
                    when (currentSource) {
                        SearchSource.NETEASE -> {
                            NeteaseDefaultContent(
                                gridState = gridState,
                                browse = NeteaseBrowseState(
                                    selectedTag = ui.selectedTag,
                                    playlists = ui.playlists,
                                    loading = ui.loading,
                                    error = ui.error
                                ),
                                tagKeys = tagKeys,
                                tagLabels = tagLabels,
                                favoriteKeys = favoriteKeys,
                                onTagSelected = vm::loadHighQuality,
                                onPlay = onPlay,
                                tagChipSelectedAlpha = tagChipSelectedAlpha,
                                tagChipUnselectedAlpha = tagChipUnselectedAlpha,
                                tagChipBorderAlpha = tagChipBorderAlpha,
                                isTabletLayout = isTabletLayout
                            )
                        }
                        SearchSource.BILIBILI -> {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Text(stringResource(R.string.explore_bili_desc), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        SearchSource.YOUTUBE_MUSIC -> {
                            YouTubeMusicExploreContent(
                                browse = YouTubeBrowseState(
                                    playlists = ui.ytMusicPlaylists,
                                    loading = ui.ytMusicPlaylistsLoading,
                                    error = ui.ytMusicPlaylistsError
                                ),
                                onRetry = vm::loadYtMusicPlaylists,
                                onClick = onYouTubeMusicPlaylistClick,
                                offlineMode = offlineMode,
                                isTabletLayout = isTabletLayout,
                                gridState = youtubeGridState
                            )
                        }
                        SearchSource.LINK_RECOGNITION -> {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.explore_link_recognition_placeholder),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPartsSheet && partsInfo != null) {
        val currentPartsInfo = partsInfo!!
        BackHandler(enabled = partsSelectionMode) { exitPartsSelection() }
        ModalBottomSheet(
            onDismissRequest = {
                showPartsSheet = false
                exitPartsSelection()
            },
            sheetState = partsSheetState,
            sheetGesturesEnabled = false
        ) {
            Column(
                Modifier
                    .bottomSheetScrollGuard()
                    .padding(bottom = 12.dp)
            ) {
                AnimatedVisibility(visible = partsSelectionMode) {
                    val allSelected = selectedParts.size == currentPartsInfo.pages.size
                    TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(
                                R.plurals.common_selected_count,
                                selectedParts.size,
                                selectedParts.size
                            )
                        )
                    },
                        navigationIcon = {
                            HapticIconButton(onClick = { exitPartsSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_exit_selection))
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = {
                                selectedParts = if (allSelected) {
                                    emptySet()
                                } else {
                                    currentPartsInfo.pages.map { it.page }.toSet()
                                }
                            }) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = if (allSelected) stringResource(R.string.explore_deselect_all) else stringResource(R.string.explore_select_all)
                                )
                            }
                            HapticIconButton(
                                onClick = {
                                    if (selectedParts.isNotEmpty()) {
                                        scope.launch { partsSheetState.hide() }.invokeOnCompletion {
                                            if (!partsSheetState.isVisible) {
                                                showPartsSheet = false
                                                showExportSheet = true
                                            }
                                        }
                                    }
                                },
                                enabled = selectedParts.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.explore_export_to_playlist))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }

                AnimatedVisibility(visible = !partsSelectionMode) {
                    Text(
                        text = currentPartsInfo.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider()

                LazyColumn {
                    itemsIndexed(currentPartsInfo.pages, key = { _, page -> page.page }) { index, page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (partsSelectionMode) {
                                            selectedParts = if (selectedParts.contains(page.page)) {
                                                selectedParts - page.page
                                            } else {
                                                selectedParts + page.page
                                            }
                                        } else {
                                            onPlayParts(currentPartsInfo, index, clickedSongCoverUrl)
                                            scope.launch { partsSheetState.hide() }.invokeOnCompletion {
                                                if (!partsSheetState.isVisible) showPartsSheet = false
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!partsSelectionMode) {
                                            partsSelectionMode = true
                                            selectedParts = setOf(page.page)
                                        }
                                    }
                                )
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (partsSelectionMode) {
                                Checkbox(
                                    checked = selectedParts.contains(page.page),
                                    onCheckedChange = {
                                        selectedParts = if (selectedParts.contains(page.page)) {
                                            selectedParts - page.page
                                        } else {
                                            selectedParts + page.page
                                        }
                                    }
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                            Text(
                                text = "P${page.page}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(48.dp)
                            )
                            Text(
                                text = page.part,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExportSheet) {
        PlaylistExportSheet(
            title = stringResource(R.string.playlist_export_to_local),
            playlists = allLocalPlaylists.filterNot {
                LocalFilesPlaylist.isSystemPlaylist(it, context)
            },
            selectedCount = selectedParts.size,
            onDismissRequest = { showExportSheet = false },
            onCreateAndExport = { name ->
                val songs = partsInfo!!.pages
                    .filter { selectedParts.contains(it.page) }
                    .map { page -> vm.toSongItem(page, partsInfo!!, clickedSongCoverUrl) }
                scope.launchLocalPlaylistMutation(
                    operation = "createPlaylistFromExplore",
                    onResult = { result ->
                        scope.showPlaylistBatchExportCreatedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = repo,
                            result = result
                        )
                    }
                ) {
                    repo.createPlaylistWithSongs(name, songs)
                }
                exitPartsSelection()
            },
            onExportToPlaylist = { playlist ->
                val songs = partsInfo!!.pages
                    .filter { selectedParts.contains(it.page) }
                    .map { page -> vm.toSongItem(page, partsInfo!!, clickedSongCoverUrl) }
                scope.launchLocalPlaylistMutation(
                    operation = "exportSongsFromExplore",
                    onResult = { result ->
                        scope.showPlaylistBatchExportAddedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = repo,
                            targetPlaylistId = playlist.id,
                            targetPlaylistName = playlist.name,
                            result = result
                        )
                    }
                ) {
                    repo.addSongsToPlaylistWithResult(playlist.id, songs)
                }
                exitPartsSelection()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreOfflineContent(topAppBarState: TopAppBarState) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        canScroll = {
            shouldAllowCollapsingTopAppBar(
                canScrollForward = false,
                canScrollBackward = false,
                collapsedFraction = topAppBarState.collapsedFraction
            )
        }
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_explore)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 32.dp, end = 32.dp, bottom = miniPlayerHeight),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.offline_mode_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.explore_offline_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreSearchHistoryRow(
    history: List<String>,
    visible: Boolean,
    query: String,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    AnimatedVisibility(visible = visible && history.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.search_history),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (query.isBlank()) {
                    HapticTextButton(onClick = onClearHistory) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
            val historyListState = rememberLazyListState()
            val showStartFade by remember(historyListState) {
                derivedStateOf { historyListState.canScrollBackward }
            }
            val showEndFade by remember(historyListState) {
                derivedStateOf { historyListState.canScrollForward }
            }
            LazyRow(
                state = historyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .exploreHorizontalEdgeFade(
                        showStartFade = showStartFade,
                        showEndFade = showEndFade
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(history) { _, item ->
                    ExploreSearchHistoryChip(
                        text = item,
                        onClick = { onHistoryClick(item) }
                    )
                }
            }
        }
    }
}

internal fun Modifier.exploreHorizontalEdgeFade(
    showStartFade: Boolean,
    showEndFade: Boolean,
    fadeWidth: Dp = 28.dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val resolvedFadeWidth = fadeWidth.toPx().coerceAtMost(size.width / 2f)
        if (resolvedFadeWidth <= 0f) return@drawWithContent
        if (showStartFade) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = resolvedFadeWidth
                ),
                topLeft = Offset.Zero,
                size = Size(resolvedFadeWidth, size.height),
                blendMode = BlendMode.DstIn
            )
        }
        if (showEndFade) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - resolvedFadeWidth,
                    endX = size.width
                ),
                topLeft = Offset(size.width - resolvedFadeWidth, 0f),
                size = Size(resolvedFadeWidth, size.height),
                blendMode = BlendMode.DstIn
            )
        }
    }

@Composable
private fun ExploreSearchHistoryChip(
    text: String,
    onClick: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    ExploreGlassPillSurface(
        fallbackColor = containerColor,
        tintColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun filteredExploreSearchHistory(history: List<String>): List<String> {
    return history.take(EXPLORE_HISTORY_DISPLAY_LIMIT)
}

internal fun shouldShowExploreSearchHistory(
    history: List<String>,
    contentScrolled: Boolean
): Boolean {
    return history.isNotEmpty() && !contentScrolled
}

internal fun shouldShowExploreNeteaseSearchTypeBar(
    selectedSearchSource: SearchSource,
    contentScrolled: Boolean
): Boolean {
    return exploreSearchTypeBarSource(selectedSearchSource, contentScrolled) ==
        SearchSource.NETEASE
}

internal fun shouldShowExploreYouTubeSearchTypeBar(
    selectedSearchSource: SearchSource,
    contentScrolled: Boolean
): Boolean {
    return exploreSearchTypeBarSource(selectedSearchSource, contentScrolled) ==
        SearchSource.YOUTUBE_MUSIC
}

internal fun exploreSearchTypeBarSource(
    selectedSearchSource: SearchSource,
    contentScrolled: Boolean
): SearchSource? {
    if (contentScrolled) return null
    return selectedSearchSource.takeIf {
        it == SearchSource.NETEASE || it == SearchSource.YOUTUBE_MUSIC
    }
}

internal fun isExploreSearchTypeBarSourceSwap(
    initialSource: SearchSource?,
    targetSource: SearchSource?
): Boolean {
    return initialSource != targetSource &&
        initialSource in EXPLORE_SEARCH_TYPE_BAR_SOURCES &&
        targetSource in EXPLORE_SEARCH_TYPE_BAR_SOURCES
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExploreSearchTypeBar(
    source: SearchSource?,
    selectedNeteaseSearchType: NeteaseExploreSearchType,
    selectedYouTubeSearchType: YouTubeExploreSearchType,
    onNeteaseSearchTypeClick: (NeteaseExploreSearchType) -> Unit,
    onYouTubeSearchTypeClick: (YouTubeExploreSearchType) -> Unit,
    selectedAlpha: Float,
    unselectedAlpha: Float,
    borderAlpha: Float
) {
    AnimatedContent(
        targetState = source,
        modifier = Modifier.testTag(EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG),
        transitionSpec = {
            val sourceSwap = isExploreSearchTypeBarSourceSwap(initialState, targetState)
            val enter = if (sourceSwap) {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + slideInVertically(
                    initialOffsetY = { height ->
                        if (targetState == SearchSource.YOUTUBE_MUSIC) {
                            height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        } else {
                            -height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        }
                    },
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            } else {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            }
            val exit = if (sourceSwap) {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + slideOutVertically(
                    targetOffsetY = { height ->
                        if (targetState == SearchSource.YOUTUBE_MUSIC) {
                            -height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        } else {
                            height / EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR
                        }
                    },
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            } else {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                )
            }
            enter togetherWith exit using SizeTransform(
                clip = true,
                sizeAnimationSpec = { _, _ ->
                    tween(
                        durationMillis = EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                }
            )
        },
        label = "explore_search_type_bar"
    ) { displayedSource ->
        when (displayedSource) {
            SearchSource.NETEASE -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(NeteaseExploreSearchType.entries) { _, type ->
                        ExploreTagChip(
                            label = neteaseSearchTypeLabel(type),
                            icon = neteaseSearchTypeIcon(type),
                            selected = selectedNeteaseSearchType == type,
                            onClick = {
                                if (source == displayedSource) {
                                    onNeteaseSearchTypeClick(type)
                                }
                            },
                            selectedAlpha = selectedAlpha,
                            unselectedAlpha = unselectedAlpha,
                            borderAlpha = borderAlpha
                        )
                    }
                }
            }

            SearchSource.YOUTUBE_MUSIC -> {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(YouTubeExploreSearchType.entries) { _, type ->
                        ExploreTagChip(
                            label = youtubeSearchTypeLabel(type),
                            icon = youtubeSearchTypeIcon(type),
                            selected = selectedYouTubeSearchType == type,
                            onClick = {
                                if (source == displayedSource) {
                                    onYouTubeSearchTypeClick(type)
                                }
                            },
                            selectedAlpha = selectedAlpha,
                            unselectedAlpha = unselectedAlpha,
                            borderAlpha = borderAlpha
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

private const val EXPLORE_HISTORY_DISPLAY_LIMIT = 15
private val EXPLORE_SEARCH_TYPE_BAR_SOURCES = setOf(
    SearchSource.NETEASE,
    SearchSource.YOUTUBE_MUSIC
)
private const val EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS = 180
private const val EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS = 140
private const val EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS = 220
private const val EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR = 5
internal const val EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG = "explore_search_type_bar"
internal const val EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG = "explore_netease_search_type_bar"
internal const val EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG = "explore_youtube_search_type_bar"
