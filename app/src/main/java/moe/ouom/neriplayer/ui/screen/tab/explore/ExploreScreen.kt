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

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import moe.ouom.neriplayer.ui.theme.neriSearchFieldShape
import moe.ouom.neriplayer.ui.theme.neriSearchFieldColors
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.component.common.NeriSearchFieldGlass
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.sync.mapping.toSongItem
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.data.search.ExploreSearchHistoryRepository
import moe.ouom.neriplayer.data.search.exploreSearchHistoryRecordKeyword
import moe.ouom.neriplayer.data.search.exploreSearchHistoryForDisplay
import moe.ouom.neriplayer.data.search.resolveExploreSearchKeyword
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.media.displayName
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
import moe.ouom.neriplayer.ui.viewmodel.tab.BilibiliExploreSearchType
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
import moe.ouom.neriplayer.common.logging.NPLogger
import kotlin.time.Duration.Companion.milliseconds
import moe.ouom.neriplayer.R as AppR

private const val SEARCH_INPUT_DEBOUNCE_MS = 300L

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

@Composable
internal fun ExploreSearchPagerEffect(
    pager: PagerState,
    sources: List<SearchSource>,
    selectedSource: SearchSource,
    onSourceChange: (SearchSource) -> Unit
) {
    LaunchedEffect(pager, sources, selectedSource) {
        val targetPage = sources.indexOf(selectedSource)
        if (targetPage < 0) return@LaunchedEffect
        // a restored page index can refer to a different source before settings load
        if (pager.currentPage != targetPage) pager.scrollToPage(targetPage)
        snapshotFlow { pager.settledPage }.collect { page ->
            val source = sources.getOrNull(page) ?: return@collect
            if (source != selectedSource) onSourceChange(source)
        }
    }
}

internal fun exploreSearchScrollContextKey(
    keyword: String,
    source: SearchSource,
    neteaseSearchType: NeteaseExploreSearchType,
    youtubeSearchType: YouTubeExploreSearchType = YouTubeExploreSearchType.SONG,
    bilibiliSearchType: BilibiliExploreSearchType = BilibiliExploreSearchType.SONG
): String? {
    val normalizedKeyword = keyword.trim()
    if (normalizedKeyword.isBlank()) return null
    val sourceType = when (source) {
        SearchSource.NETEASE -> neteaseSearchType.name
        SearchSource.BILIBILI -> bilibiliSearchType.name
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

@Composable
internal fun rememberExploreSearchCollapsingHeaderState(
    query: String,
    source: SearchSource,
    searching: Boolean,
    searchError: UiText?,
    hasResults: Boolean
): ExploreCollapsingHeaderState {
    val state = rememberExploreCollapsingHeaderState()
    val showingSearch = query.isNotEmpty()
    val showingResults = showingSearch && !searching && searchError == null && hasResults
    LaunchedEffect(showingSearch, showingResults) {
        // placeholders cannot dispatch the scroll gesture needed to reveal hidden controls
        if (!showingResults) state.expand()
    }
    LaunchedEffect(showingSearch, source) {
        if (!showingSearch && (source == SearchSource.BILIBILI || source == SearchSource.LINK_RECOGNITION)) {
            state.expand()
        }
    }
    return state
}

internal fun exploreSearchResultsBottomPadding(miniPlayerHeight: Dp): Dp =
    16.dp + miniPlayerHeight

internal val ExploreSearchContentMaxWidth = 1040.dp

internal fun exploreSearchResultsHorizontalPadding(isTabletLayout: Boolean): Dp =
    if (isTabletLayout) 28.dp else 0.dp

@Composable
internal fun ExploreSearchResultsList(
    state: LazyListState,
    isTabletLayout: Boolean,
    miniPlayerHeight: Dp,
    content: LazyListScope.() -> Unit
) {
    val horizontalPadding = exploreSearchResultsHorizontalPadding(isTabletLayout)
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(
            start = horizontalPadding,
            end = horizontalPadding,
            top = 8.dp,
            bottom = exploreSearchResultsBottomPadding(miniPlayerHeight)
        ),
        modifier = Modifier.exploreCenteredMaxWidth(ExploreSearchContentMaxWidth),
        content = content
    )
}

internal fun shouldShowBiliPartsPicker(song: SongItem): Boolean {
    return song.album == PlayerManager.BILI_SOURCE_TAG ||
        song.album.startsWith("${PlayerManager.BILI_SOURCE_TAG}|")
}

@Composable
private fun searchSourceLabel(source: SearchSource): String {
    return when (source) {
        SearchSource.YOUTUBE_MUSIC -> stringResource(CoreCommonR.string.explore_tab_youtube)
        SearchSource.NETEASE -> stringResource(AppR.string.platform_netease_short)
        SearchSource.BILIBILI -> stringResource(AppR.string.platform_bilibili)
        SearchSource.LINK_RECOGNITION -> stringResource(CoreCommonR.string.explore_tab_links)
    }
}

@Composable
private fun neteaseSearchTypeLabel(type: NeteaseExploreSearchType): String {
    return when (type) {
        NeteaseExploreSearchType.SONG -> stringResource(CoreCommonR.string.explore_search_type_song)
        NeteaseExploreSearchType.PLAYLIST -> stringResource(CoreCommonR.string.explore_search_type_playlist)
        NeteaseExploreSearchType.ARTIST -> stringResource(CoreCommonR.string.explore_search_type_artist)
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
private fun bilibiliSearchTypeLabel(type: BilibiliExploreSearchType): String {
    return when (type) {
        BilibiliExploreSearchType.SONG -> stringResource(CoreCommonR.string.explore_search_type_song)
        BilibiliExploreSearchType.VIDEO -> stringResource(CoreCommonR.string.explore_search_type_video)
        BilibiliExploreSearchType.CREATOR -> stringResource(CoreCommonR.string.explore_search_type_creator)
    }
}

private fun bilibiliSearchTypeIcon(type: BilibiliExploreSearchType): ImageVector {
    return when (type) {
        BilibiliExploreSearchType.SONG -> Icons.Outlined.MusicNote
        BilibiliExploreSearchType.VIDEO -> Icons.Filled.PlayCircle
        BilibiliExploreSearchType.CREATOR -> Icons.Filled.AccountCircle
    }
}

@Composable
private fun youtubeSearchTypeLabel(type: YouTubeExploreSearchType): String {
    return when (type) {
        YouTubeExploreSearchType.SONG -> stringResource(CoreCommonR.string.explore_search_type_song)
        YouTubeExploreSearchType.VIDEO -> stringResource(CoreCommonR.string.explore_search_type_video)
        YouTubeExploreSearchType.ALBUM -> stringResource(CoreCommonR.string.artist_tab_albums)
        YouTubeExploreSearchType.FEATURED_PLAYLIST -> stringResource(AppR.string.youtube_featured_playlists)
        YouTubeExploreSearchType.COMMUNITY_PLAYLIST -> stringResource(AppR.string.youtube_community_playlists)
        YouTubeExploreSearchType.CREATOR -> stringResource(CoreCommonR.string.explore_search_type_creator)
    }
}

private fun youtubeSearchTypeIcon(type: YouTubeExploreSearchType): ImageVector {
    return when (type) {
        YouTubeExploreSearchType.SONG -> Icons.Outlined.MusicNote
        YouTubeExploreSearchType.VIDEO -> Icons.Filled.PlayCircle
        YouTubeExploreSearchType.ALBUM -> Icons.Filled.Album
        YouTubeExploreSearchType.FEATURED_PLAYLIST,
        YouTubeExploreSearchType.COMMUNITY_PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
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
    onBiliUploaderClick: (BiliUploaderSummary) -> Unit = {},
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
    val favoriteSongLookup = remember(allLocalPlaylists, context) {
        FavoriteSongLookup(FavoritesPlaylist.firstOrNull(allLocalPlaylists, context)?.songs.orEmpty())
    }
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val favoriteKeys = remember(favorites) {
        favorites.mapTo(mutableSetOf()) { "${it.source}:${it.id}" }
    }

    var showPartsSheet by remember { mutableStateOf(false) }
    var pendingPartsLookup by remember { mutableStateOf<Job?>(null) }
    var partsInfo by remember { mutableStateOf<VideoBasicInfo?>(null) }
    var clickedSongCoverUrl by remember { mutableStateOf("") }
    val partsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var partsSelectionMode by remember { mutableStateOf(false) }
    var selectedParts by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var showExportSheet by remember { mutableStateOf(false) }

    val internationalSetting by AppContainer.settingsRepo.internationalizationEnabledFlow
        .collectAsStateWithLifecycle(initialValue = null)
    val isInternational = internationalSetting == true
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
    val youtubeGridState = rememberLazyGridState()
    val tagChipSelectedAlpha = if (backgroundImageUri == null) 1f else 0.86f
    val tagChipUnselectedAlpha = if (backgroundImageUri == null) 1f else 0.74f
    val tagChipBorderAlpha = if (backgroundImageUri == null) 1f else 0.58f
    var previousSearchSource by remember { mutableStateOf(ui.selectedSearchSource) }
    // history and type chips stay put while results scroll; collapsing them mid-gesture
    // shifted the list under the finger
    var searchFieldFocused by remember { mutableStateOf(false) }
    val searchActive = isExploreSearchActive(query = searchQuery, focused = searchFieldFocused)
    // 空闲时只露出历史, 进入搜索后换成类型筛选, 两排不再同时堆在搜索框下面
    val shouldShowSearchHistory = searchQuery.isEmpty() && shouldShowExploreSearchHistory(
        history = visibleSearchHistory,
        contentScrolled = false
    )
    val searchTypeBarSource = if (searchActive) {
        exploreSearchTypeBarSource(
            selectedSearchSource = ui.selectedSearchSource,
            contentScrolled = false
        )
    } else {
        null
    }

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

    ExploreSearchPagerEffect(pagerState, orderedSearchSources, ui.selectedSearchSource, vm::setSearchSource)
    var browseReady by remember(internationalSetting, youtubeEnabled) { mutableStateOf(false) }
    LaunchedEffect(internationalSetting, youtubeEnabled) {
        if (internationalSetting == null) return@LaunchedEffect
        vm.applyBrowseDefault(orderedSearchSources.first(), searchQuery)
        browseReady = true
    }
    val contentPreferences by YouTubeContentSettings.preferences.collectAsStateWithLifecycle()
    val catalogKey = contentPreferences.catalogKey(YouTubeContentSettings.systemLocale())
    val auth by AppContainer.youtubeAuthRepo.authFlow.collectAsStateWithLifecycle(
        initialValue = remember { AppContainer.youtubeAuthRepo.getAuthOnce() }
    )
    val accountKey = remember(auth) { auth.buildRefreshObserverFingerprint() }
    LaunchedEffect(browseReady, ui.selectedSearchSource, catalogKey, accountKey) {
        if (!browseReady) return@LaunchedEffect
        vm.updateYouTubeBrowseContext(catalogKey, accountKey)
        val state = vm.uiState.value
        when (state.selectedSearchSource) {
            SearchSource.YOUTUBE_MUSIC -> if (state.ytMusicPlaylists.isEmpty() && !state.ytMusicPlaylistsLoading) vm.loadYtMusicPlaylists()
            SearchSource.NETEASE -> if (state.playlists.isEmpty() && !state.loading) vm.loadHighQuality()
            else -> Unit
        }
    }

    // Tag keys for API calls
    val tagKeys = listOf(
        "tag_all", "tag_pop", "tag_soundtrack", "tag_chinese", "tag_nostalgia", "tag_rock", "tag_acg", "tag_western", "tag_fresh", "tag_night", "tag_children", "tag_folk", "tag_japanese", "tag_romantic",
        "tag_study", "tag_korean", "tag_work", "tag_electronic", "tag_cantonese", "tag_dance", "tag_sad", "tag_game", "tag_afternoon_tea", "tag_healing", "tag_rap", "tag_light_music"
    )

    // Translated tag labels for display
    val tagLabels = listOf(
        stringResource(AppR.string.tag_all), stringResource(AppR.string.tag_pop), stringResource(AppR.string.tag_soundtrack), stringResource(AppR.string.tag_chinese), stringResource(AppR.string.tag_nostalgia), stringResource(AppR.string.tag_rock), stringResource(AppR.string.tag_acg), stringResource(AppR.string.tag_western), stringResource(AppR.string.tag_fresh), stringResource(AppR.string.tag_night), stringResource(AppR.string.tag_children), stringResource(AppR.string.tag_folk), stringResource(AppR.string.tag_japanese), stringResource(AppR.string.tag_romantic),
        stringResource(AppR.string.tag_study), stringResource(AppR.string.tag_korean), stringResource(AppR.string.tag_work), stringResource(AppR.string.tag_electronic), stringResource(AppR.string.tag_cantonese), stringResource(AppR.string.tag_dance), stringResource(AppR.string.tag_sad), stringResource(AppR.string.tag_game), stringResource(AppR.string.tag_afternoon_tea), stringResource(AppR.string.tag_healing), stringResource(AppR.string.tag_rap), stringResource(AppR.string.tag_light_music)
    )

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
        ui.selectedBilibiliSearchType,
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
        ui.selectedBilibiliSearchType,
        ui.selectedNeteaseSearchType,
        ui.selectedYouTubeMusicSearchType
    ) {
        exploreSearchScrollContextKey(
            keyword = effectiveSearchKeyword,
            source = ui.selectedSearchSource,
            bilibiliSearchType = ui.selectedBilibiliSearchType,
            neteaseSearchType = ui.selectedNeteaseSearchType,
            youtubeSearchType = ui.selectedYouTubeMusicSearchType
        )
    }

    DisposableEffect(currentSearchScrollContextKey) {
        onDispose { pendingPartsLookup?.cancel() }
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
        ui.searchPage,
        ui.selectedSearchSource,
        ui.selectedBilibiliSearchType,
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

    val collapsingHeader = rememberExploreSearchCollapsingHeaderState(
        query = searchQuery,
        source = ui.selectedSearchSource,
        searching = ui.searching,
        searchError = ui.searchError,
        hasResults = ui.searchItems.isNotEmpty()
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        // follow the finger and stay put; a release snap reads as an unwanted bounce
        snapAnimationSpec = null,
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
        // 底部避让由导航宿主和列表留白负责，滚动视口应延伸到系统导航区
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        snackbarHost = {
            NeriSnackbarHost(
                hostState = snackbarHostState,
                bottomPadding = miniPlayerHeight
            )
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(AppR.string.nav_explore)) },
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
                .padding(innerPadding)
                .nestedScroll(collapsingHeader.connection),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier
                    .widthIn(max = ExploreSearchContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = searchPanelHorizontalPadding, vertical = 8.dp)
            ) {
                    val searchFieldLabel = stringResource(
                        if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
                            CoreCommonR.string.explore_link_input_label
                        } else {
                            CoreCommonR.string.search_keyword
                        }
                    )
                    // 两种风格都用无浮动标签的搜索胶囊, 与媒体库, 设置的搜索框同源
                    NeriSearchFieldGlass(
                        modifier = Modifier.fillMaxWidth(),
                        shape = neriSearchFieldShape()
                    ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            onSearchQueryChange(it)
                        },
                        placeholder = {
                            when {
                                ui.selectedSearchSource == SearchSource.LINK_RECOGNITION -> {
                                    Text(stringResource(CoreCommonR.string.explore_link_input_placeholder))
                                }
                                ui.selectedSearchSource == SearchSource.NETEASE && !ui.isNeteaseLoggedIn -> {
                                    Text(stringResource(CoreCommonR.string.netease_login_required_search_placeholder))
                                }
                                else -> Text(searchFieldLabel)
                            }
                        },
                        colors = neriSearchFieldColors(),
                        leadingIcon = { Icon(Icons.Default.Search, stringResource(CoreCommonR.string.action_search)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                HapticIconButton(onClick = {
                                    onSearchQueryChange("")
                                    vm.search("")
                                }) { Icon(Icons.Default.Clear, stringResource(CoreCommonR.string.cd_clear)) }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            submitExploreSearch()
                        }),
                        singleLine = true,
                        shape = neriSearchFieldShape(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { searchFieldFocused = it.isFocused }
                    )
                    }
                    // only the search field stays pinned; everything below scrolls with the list
                    Column(Modifier.fillMaxWidth().exploreCollapsingHeader(collapsingHeader)) {
                    Spacer(Modifier.height(10.dp))
                    if (NeriDesign.isClean) {
                        val sourceLabels = orderedSearchSources.map { searchSourceLabel(it) }
                        CleanSegmentedTabs(
                            labels = sourceLabels,
                            selectedIndex = pagerState.currentPage,
                            onSelected = { index ->
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                        )
                    } else {
                        ExploreMaterialSourceSwitcher(
                            labels = orderedSearchSources.map { searchSourceLabel(it) },
                            selectedIndex = pagerState.currentPage,
                            onSelected = { index ->
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                        )
                    }
                    if (ui.selectedSearchSource == SearchSource.NETEASE && !ui.isNeteaseLoggedIn) {
                        ExploreInlineNotice(
                            text = stringResource(CoreCommonR.string.netease_login_required_search),
                            warning = true
                        )
                    }
                    if (ui.selectedSearchSource == SearchSource.LINK_RECOGNITION) {
                        ExploreInlineNotice(
                            text = stringResource(CoreCommonR.string.explore_link_input_hint),
                            warning = false
                        )
                    }
                    ExploreSearchHistoryRow(
                        history = visibleSearchHistory,
                        visible = shouldShowSearchHistory,
                        query = searchQuery,
                        onHistoryClick = { item -> submitExploreSearch(item) },
                        onRemoveHistory = { item ->
                            scope.launch { searchHistoryRepository.remove(item) }
                        },
                        onClearHistory = {
                            lastRecordedSearchKeyword = null
                            pendingSearchHistoryRecord = null
                            scope.launch {
                                searchHistoryRepository.clear()
                            }
                        }
                    )
                    ExploreSearchTypeBar(
                        source = searchTypeBarSource,
                        selectedBilibiliSearchType = ui.selectedBilibiliSearchType,
                        selectedNeteaseSearchType = ui.selectedNeteaseSearchType,
                        selectedYouTubeSearchType = ui.selectedYouTubeMusicSearchType,
                        onNeteaseSearchTypeClick = vm::setNeteaseSearchType,
                        onBilibiliSearchTypeClick = vm::setBilibiliSearchType,
                        onYouTubeSearchTypeClick = vm::setYouTubeMusicSearchType,
                        selectedAlpha = tagChipSelectedAlpha,
                        unselectedAlpha = tagChipUnselectedAlpha,
                        borderAlpha = tagChipBorderAlpha
                    )
                    }
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
                                    Text(ui.searchError!!.asString(), color = MaterialTheme.colorScheme.error)
                                }
                            }
                            ui.searchItems.isEmpty() -> {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = miniPlayerHeight),
                                    Alignment.Center
                                ) {
                                    val loadError = ui.searchLoadMoreError
                                    when {
                                        ui.searchLoadingMore -> SearchLoadingMoreRow()
                                        loadError != null -> SearchLoadMoreErrorRow(loadError.asString(), vm::loadMoreSearchResults)
                                        ui.searchHasMore -> HapticTextButton(onClick = vm::loadMoreSearchResults) {
                                            Text(stringResource(CoreCommonR.string.youtube_creator_load_more))
                                        }
                                        else -> Text(stringResource(CoreCommonR.string.search_no_result))
                                    }
                                }
                            }
                            else -> {
                                val songs = ui.searchResults
                                val searchSongIndexByKey = remember(songs) {
                                    buildMap(songs.size) {
                                        songs.forEachIndexed { songIdx, s ->
                                            putIfAbsent(s.stableKey(), songIdx)
                                        }
                                    }
                                }
                                ExploreSearchResultsList(
                                    state = searchListState,
                                    isTabletLayout = isTabletLayout,
                                    miniPlayerHeight = miniPlayerHeight
                                ) {
                                itemsIndexed(
                                    items = ui.searchItems,
                                    key = { _, item -> item.stableKey },
                                    contentType = { _, item -> item::class }
                                ) { index, item ->
                                    when (item) {
                                        is ExploreSearchResult.Song -> {
                                            val song = item.song
                                            val songListIndex =
                                                searchSongIndexByKey[song.stableKey()] ?: index
                                            val isFavoriteSong = favoriteSongLookup.contains(song)
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
                                                        pendingPartsLookup?.cancel()
                                                        if (shouldShowBiliPartsPicker(song)) {
                                                            pendingPartsLookup = scope.launch {
                                                                try {
                                                                    val info =
                                                                        vm.getVideoInfoByAvid(song.id)
                                                                    if (info.pages.size <= 1) {
                                                                        onSongClick(
                                                                            songs,
                                                                            songListIndex
                                                                        )
                                                                    } else {
                                                                        partsInfo = info
                                                                        clickedSongCoverUrl =
                                                                            song.coverUrl ?: ""
                                                                        showPartsSheet = true
                                                                    }
                                                                } catch (cancelled: CancellationException) {
                                                                    throw cancelled
                                                                } catch (e: Exception) {
                                                                    NPLogger.e(
                                                                        "ExploreScreen",
                                                                        composeResources.getString(CoreCommonR.string.search_error),
                                                                        e
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            onSongClick(
                                                                songs,
                                                                songListIndex
                                                            )
                                                        }
                                                    },
                                                    onPlayNow = {
                                                        pendingPartsLookup?.cancel()
                                                        onSongPlayPreservingQueue(song)
                                                    },
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
                                                                    CoreCommonR.string.download_starting,
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
                                                                            FavoritesPlaylist.matchesSong(it, song)
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
                                            CreatorSearchRow(
                                                title = item.creator.title,
                                                subtitle = item.creator.subtitle.ifBlank {
                                                    stringResource(CoreCommonR.string.explore_search_type_creator)
                                                },
                                                coverUrl = item.creator.coverUrl,
                                                offlineMode = offlineMode,
                                                onClick = { onYouTubeCreatorClick(item.creator) }
                                            )
                                        }
                                        is ExploreSearchResult.BilibiliCreator -> {
                                            CreatorSearchRow(
                                                title = item.creator.name,
                                                subtitle = stringResource(CoreCommonR.string.bili_uploader_mid, item.creator.mid),
                                                coverUrl = item.creator.avatarUrl,
                                                offlineMode = offlineMode,
                                                onClick = { onBiliUploaderClick(item.creator) }
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
                                    item(
                                        key = "search-loading-more",
                                        contentType = "explore:search:loading-more"
                                    ) {
                                        SearchLoadingMoreRow()
                                    }
                                } else if (ui.searchLoadMoreError != null) {
                                    item(
                                        key = "search-load-more-error",
                                        contentType = "explore:search:load-more-error"
                                    ) {
                                        SearchLoadMoreErrorRow(
                                            error = ui.searchLoadMoreError!!.asString(),
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
                                    error = ui.error?.asString()
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
                                Text(stringResource(AppR.string.explore_bili_desc), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        SearchSource.YOUTUBE_MUSIC -> {
                            YouTubeMusicExploreContent(
                                browse = YouTubeBrowseState(
                                    playlists = ui.ytMusicPlaylists,
                                    loading = ui.ytMusicPlaylistsLoading,
                                    error = ui.ytMusicPlaylistsError?.asString()
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
                                    text = stringResource(CoreCommonR.string.explore_link_recognition_placeholder),
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
                                AppR.plurals.common_selected_count,
                                selectedParts.size,
                                selectedParts.size
                            )
                        )
                    },
                        navigationIcon = {
                            HapticIconButton(onClick = { exitPartsSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(AppR.string.explore_exit_selection))
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
                                    contentDescription = if (allSelected) stringResource(AppR.string.explore_deselect_all) else stringResource(AppR.string.explore_select_all)
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
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = stringResource(AppR.string.explore_export_to_playlist))
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
            title = stringResource(CoreCommonR.string.playlist_export_to_local),
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

/** 输入框有字或正在输入时才算进入搜索态 */
internal fun isExploreSearchActive(query: String, focused: Boolean): Boolean =
    query.isNotEmpty() || focused

/** Material 的来源切换: 一排可横滑的胶囊, 选中项用强调色调, 比细下划线更容易一眼看清 */
@Composable
private fun ExploreMaterialSourceSwitcher(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val container by animateColorAsState(
                if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
                label = "explore_source_container"
            )
            val content by animateColorAsState(
                if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                label = "explore_source_content"
            )
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(container)
                    .selectable(selected = selected, role = Role.Tab) { onSelected(index) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ExploreInlineNotice(text: String, warning: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (warning) colors.errorContainer.copy(alpha = 0.6f)
                else colors.surfaceContainerHigh
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (warning) colors.onErrorContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (warning) colors.onErrorContainer else colors.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreOfflineContent(topAppBarState: TopAppBarState) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        // follow the finger and stay put; a release snap reads as an unwanted bounce
        snapAnimationSpec = null,
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
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(AppR.string.nav_explore)) },
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
                    text = stringResource(CoreCommonR.string.offline_mode_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(AppR.string.explore_offline_disabled),
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
    onRemoveHistory: (String) -> Unit,
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
                        text = stringResource(CoreCommonR.string.search_history),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (query.isBlank()) {
                    HapticTextButton(onClick = onClearHistory) {
                        Text(stringResource(CoreCommonR.string.action_clear))
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
                itemsIndexed(history, key = { _, item -> item }) { _, item ->
                    ExploreSearchHistoryChip(
                        text = item,
                        onClick = { onHistoryClick(item) },
                        onRemove = { onRemoveHistory(item) },
                        modifier = Modifier.animateItem()
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
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    val removeDescription = "${stringResource(CoreCommonR.string.action_delete)} $text"
    ExploreGlassPillSurface(
        modifier = modifier,
        fallbackColor = containerColor,
        tintColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .padding(start = 2.dp, end = 4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = removeDescription },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
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
    return selectedSearchSource.takeIf { it in EXPLORE_SEARCH_TYPE_BAR_SOURCES }
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
    selectedBilibiliSearchType: BilibiliExploreSearchType,
    selectedNeteaseSearchType: NeteaseExploreSearchType,
    selectedYouTubeSearchType: YouTubeExploreSearchType,
    onNeteaseSearchTypeClick: (NeteaseExploreSearchType) -> Unit,
    onBilibiliSearchTypeClick: (BilibiliExploreSearchType) -> Unit,
    onYouTubeSearchTypeClick: (YouTubeExploreSearchType) -> Unit,
    selectedAlpha: Float,
    unselectedAlpha: Float,
    borderAlpha: Float
) {
    val preferences by YouTubeContentSettings.preferences.collectAsStateWithLifecycle()
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
        val tag = when (displayedSource) {
            SearchSource.NETEASE -> EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG
            SearchSource.BILIBILI -> EXPLORE_BILIBILI_SEARCH_TYPE_BAR_TAG
            SearchSource.YOUTUBE_MUSIC -> EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG
            else -> return@AnimatedContent
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            fun <T> typeChips(
                types: List<T>,
                selectedType: T,
                label: @Composable (T) -> String,
                icon: (T) -> ImageVector,
                onTypeClick: (T) -> Unit
            ) {
                items(types) { type ->
                    ExploreTagChip(
                        label = label(type),
                        icon = icon(type),
                        selected = selectedType == type,
                        onClick = { if (source == displayedSource) onTypeClick(type) },
                        selectedAlpha = selectedAlpha,
                        unselectedAlpha = unselectedAlpha,
                        borderAlpha = borderAlpha
                    )
                }
            }
            when (displayedSource) {
                SearchSource.NETEASE -> typeChips(
                    NeteaseExploreSearchType.entries, selectedNeteaseSearchType,
                    { neteaseSearchTypeLabel(it) }, ::neteaseSearchTypeIcon, onNeteaseSearchTypeClick
                )
                SearchSource.BILIBILI -> typeChips(
                    BilibiliExploreSearchType.entries, selectedBilibiliSearchType,
                    { bilibiliSearchTypeLabel(it) }, ::bilibiliSearchTypeIcon, onBilibiliSearchTypeClick
                )
                SearchSource.YOUTUBE_MUSIC -> typeChips(
                    YouTubeExploreSearchType.entries.filterNot { it == YouTubeExploreSearchType.VIDEO && preferences.hideMusicVideos },
                    selectedYouTubeSearchType,
                    { youtubeSearchTypeLabel(it) }, ::youtubeSearchTypeIcon, onYouTubeSearchTypeClick
                )
            }
        }
    }
}

private const val EXPLORE_HISTORY_DISPLAY_LIMIT = 15
private val EXPLORE_SEARCH_TYPE_BAR_SOURCES = setOf(
    SearchSource.NETEASE,
    SearchSource.BILIBILI,
    SearchSource.YOUTUBE_MUSIC
)
private const val EXPLORE_SEARCH_TYPE_BAR_ENTER_DURATION_MS = 180
private const val EXPLORE_SEARCH_TYPE_BAR_EXIT_DURATION_MS = 140
private const val EXPLORE_SEARCH_TYPE_BAR_SIZE_DURATION_MS = 220
private const val EXPLORE_SEARCH_TYPE_BAR_SLIDE_DIVISOR = 5
internal const val EXPLORE_SEARCH_TYPE_BAR_CONTAINER_TAG = "explore_search_type_bar"
internal const val EXPLORE_NETEASE_SEARCH_TYPE_BAR_TAG = "explore_netease_search_type_bar"
internal const val EXPLORE_BILIBILI_SEARCH_TYPE_BAR_TAG = "explore_bilibili_search_type_bar"
internal const val EXPLORE_YOUTUBE_SEARCH_TYPE_BAR_TAG = "explore_youtube_search_type_bar"
