package moe.ouom.neriplayer.ui.screen.playlist

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
 * File: moe.ouom.neriplayer.ui.screen.playlist/BiliPlaylistDetailScreen
 * Created: 2025/8/15
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliThumbnailUrl
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.rememberMainTabDetailVisibilityState
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.toFavoriteBrowseId
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.format.formatDurationSec
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.util.ClipboardCopyResult
import moe.ouom.neriplayer.ui.util.copyPlainTextSafely
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import androidx.compose.runtime.saveable.rememberSaveable
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BiliPlaylistDetailScreen(
    playlist: BiliPlaylist,
    onBack: () -> Unit = {},
    onPlayAudio: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    suppressVisibilityTransition: Boolean = false,
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val vm: BiliPlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                BiliPlaylistDetailViewModel(app)
            }
        }
    )
    val ui by vm.uiState.collectAsState()
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()
    // 使用Unit作为key，确保每次进入都重新加载最新数据
    LaunchedEffect(playlist.mediaId, playlist.kind) { vm.start(playlist) }

    // 保存最新的header和videos数据，用于在Screen销毁时更新使用记录
    var latestHeader by remember { mutableStateOf<BiliPlaylist?>(null) }
    var latestTrackCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.header, ui.videos.size) {
        ui.header?.let { latestHeader = it }
        latestTrackCount = ui.header?.count?.coerceAtLeast(ui.videos.size) ?: ui.videos.size
    }

    // 在Screen销毁时更新使用记录，确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
            latestHeader?.let { header ->
                AppContainer.playlistUsageRepo.updateInfo(
                    id = header.mediaId,
                    name = header.title,
                    picUrl = header.coverUrl,
                    trackCount = latestTrackCount,
                    fid = header.fid,
                    mid = header.mid,
                    source = "bili",
                    subtype = header.kind.name,
                    subtitle = header.subtitle
                )
            }
        }
    }

    // 下载进度
    var showDownloadManager by remember { mutableStateOf(false) }
    val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val pendingTaskCount = downloadTaskSummary.pendingTaskCount
    val hasDownloadManagerEntry = downloadTaskSummary.hasPendingTasks

    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allLocalPlaylists by repo.playlists.collectAsState(initial = emptyList())
    val favoriteSongs = remember(allLocalPlaylists, context) {
        FavoritesPlaylist.firstOrNull(allLocalPlaylists, context)?.songs.orEmpty()
    }
    val playlistSource = "bili"
    val playlistId = ui.header?.mediaId ?: playlist.mediaId
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val isFavorite = remember(favorites, playlistId) {
        favoriteRepo.isFavorite(playlistId, playlistSource)
    }
    LaunchedEffect(isFavorite, ui.header, ui.videos) {
        if (!isFavorite) return@LaunchedEffect
        val header = ui.header ?: return@LaunchedEffect
        favoriteRepo.updateFavoriteMeta(
            id = header.mediaId,
            name = header.title,
            coverUrl = header.coverUrl,
            trackCount = header.count,
            source = playlistSource,
            browseId = header.toFavoriteBrowseId(),
            subtitle = header.subtitle,
            songs = ui.videos.map { it.toSongItem() }
        )
    }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showExportAllSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val favoriteAddedText = stringResource(R.string.favorite_added)
    val favoriteRemovedText = stringResource(R.string.favorite_removed)
    fun toggleSongFavorite(song: SongItem, isFavoriteSong: Boolean) {
        val message = if (isFavoriteSong) favoriteRemovedText else favoriteAddedText
        scope.launchLocalPlaylistMutation(
            operation = "toggleBiliDetailSongFavorite",
            onResult = { result ->
                if (result.isSuccess) {
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(message)
                    }
                }
            }
        ) {
            if (isFavoriteSong) {
                repo.removeFromFavorites(song)
            } else {
                repo.addToFavorites(song)
            }
        }
    }
    val listState = rememberSaveable(
        playlist.kind.name,
        playlist.mediaId,
        saver = LazyListState.Saver
    ) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    var showPartsSheet by remember { mutableStateOf(false) }
    var partsInfo by remember { mutableStateOf<BiliClient.VideoBasicInfo?>(null) }
    val partsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun toggleSelect(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }
    fun clearSelection() { selectedIds = emptySet() }
    fun selectAll() { selectedIds = ui.videos.map { it.bvid }.toSet() }
    fun exitSelection() { selectionMode = false; clearSelection() }

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var headerSearchFocused by remember { mutableStateOf(false) }
    var dockedSearchFocused by remember { mutableStateOf(false) }
    val searchInputState = rememberPlaylistSearchInputState(
        query = searchQuery,
        onQueryChange = { searchQuery = it }
    )
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var partsSelectionMode by remember { mutableStateOf(false) }
    var selectedParts by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun exitPartsSelection() {
        partsSelectionMode = false
        selectedParts = emptySet()
    }

    val displayedVideos = rememberPlaylistSearchResults(
        query = searchQuery,
        items = ui.videos,
        tokens = { video ->
            listOf(video.title, video.uploader, video.bvid, video.id.toString())
        }
    )
    val displayHeader = ui.header ?: playlist
    val displayHeaderCoverUrl = remember(displayHeader.coverUrl) {
        buildBiliPlaylistHeroCoverUrl(displayHeader.coverUrl)
    }
    val playlistChromeColor = rememberPlaylistModernHeroBackgroundColor(
        coverUrl = displayHeaderCoverUrl,
        offlineMode = offlineMode
    )
    val density = LocalDensity.current
    val searchVisible = shouldShowPlaylistSearch(
        showSearch = showSearch,
        selectionMode = selectionMode
    )
    val searchVisibilityProgress = playlistModernSearchVisibilityProgress(
        searchVisible = searchVisible,
        label = "bili-playlist-search-visibility"
    )
    val searchVisibilityEased = resolvePlaylistEasedProgress(searchVisibilityProgress)
    val playlistHeroHeight = interpolatePlaylistDp(
        start = PlaylistModernHeroHeight,
        end = PlaylistModernHeroSearchHeight,
        fraction = searchVisibilityEased
    )
    val playlistChromeCollapseProgress by remember(
        listState,
        density,
        playlistHeroHeight
    ) {
        derivedStateOf {
            resolvePlaylistChromeCollapseProgress(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                expandedHeroHeightPx = with(density) {
                    playlistHeroHeight.roundToPx()
                }
            )
        }
    }
    val playlistChromeVisualProgress = resolvePlaylistEasedProgress(
        playlistChromeCollapseProgress
    )
    val dockedSearchRevealProgress by remember(listState, density) {
        derivedStateOf {
            resolvePlaylistDockedSearchRevealProgress(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset,
                revealDistancePx = with(density) {
                    PlaylistModernDockedSearchSlotHeight.roundToPx()
                }
            )
        }
    }
    val searchDockedVisualProgress = resolvePlaylistEasedProgress(
        dockedSearchRevealProgress
    )
    val dockedSearchProgress = resolvePlaylistDockedSearchSlotProgress(
        searchVisibilityProgress = searchVisibilityProgress,
        dockedRevealProgress = dockedSearchRevealProgress
    )
    val searchSlotVisible = shouldComposePlaylistSearchSlot(
        searchVisible = searchVisible,
        visibilityProgress = dockedSearchProgress
    )
    val headerSearchAlpha = resolvePlaylistHeaderSearchAlpha(
        searchVisibilityProgress = searchVisibilityProgress,
        chromeCollapseProgress = playlistChromeCollapseProgress
    )
    val headerSearchVisible = shouldComposePlaylistSearchSlot(
        searchVisible = searchVisible,
        visibilityProgress = headerSearchAlpha
    )
    val searchFieldFocusInHeader =
        headerSearchVisible && dockedSearchRevealProgress < 0.5f
    val searchFieldComposed = headerSearchVisible || searchSlotVisible
    val playlistTopBarColor = resolvePlaylistTranslucentTopBarColor(
        playlistColor = playlistChromeColor,
        collapseProgress = playlistChromeVisualProgress
    )
    val playlistTopBarContentColor = interpolatePlaylistColor(
        start = resolvePlaylistSolidTopBarContentColor(playlistChromeColor),
        end = playlistModernCollapsedTopBarContentColor(),
        fraction = playlistChromeVisualProgress
    )
    val playlistSelectionTopBarColor = resolvePlaylistSelectionTopBarColor(
        playlistColor = playlistChromeColor,
        collapseProgress = playlistChromeCollapseProgress
    )
    val playlistSelectionTopBarContentColor = resolvePlaylistSelectionTopBarContentColor(
        playlistColor = playlistChromeColor,
        collapsedContentColor = playlistModernCollapsedTopBarContentColor(),
        collapseProgress = playlistChromeCollapseProgress
    )
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(
        initial = false
    )
    val backgroundImageUri by AppContainer.settingsRepo.backgroundImageUriFlow.collectAsState(
        initial = null
    )
    val hasCustomBackground = backgroundImageUri != null
    LaunchedEffect(
        showSearch,
        selectionMode,
        searchFieldComposed,
        autoShowKeyboard,
        searchFieldFocusInHeader
    ) {
        if (!searchFieldComposed) return@LaunchedEffect
        val shouldAutoFocus = shouldRequestPlaylistSearchFocus(
            showSearch,
            selectionMode,
            autoShowKeyboard
        )
        val shouldTransferFocus = shouldTransferPlaylistSearchFocus(
            showSearch = showSearch,
            selectionMode = selectionMode,
            searchFieldComposed = searchFieldComposed,
            searchInputFocused = headerSearchFocused || dockedSearchFocused,
            searchQuery = searchQuery
        )
        if (!shouldAutoFocus && !shouldTransferFocus) return@LaunchedEffect
        if (shouldAutoFocus) delay(120)
        searchFocusRequester.requestFocus()
        keyboardController?.show()
    }
    fun playBiliPlaylist(shuffle: Boolean) {
        val startIndex = resolvePlaylistPlaybackStartIndex(
            songCount = ui.videos.size,
            shuffleEnabled = shuffle,
            randomIndex = if (ui.videos.isEmpty()) 0 else Random.nextInt(ui.videos.size)
        )
        if (startIndex < 0) return
        PlayerManager.setShuffle(shuffle)
        onPlayAudio(ui.videos, startIndex)
    }

    val detailVisibilityState = rememberMainTabDetailVisibilityState(
        detailKey = playlist.mediaId,
        initiallyVisible = suppressVisibilityTransition
    )
    AnimatedVisibility(
        visibleState = detailVisibilityState,
        enter = if (suppressVisibilityTransition) {
            EnterTransition.None
        } else {
            fadeIn() + slideInVertically { it / 6 }
        },
        exit = if (suppressVisibilityTransition) {
            ExitTransition.None
        } else {
            fadeOut() + slideOutVertically { it / 6 }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            val miniPlayerHeight = LocalMiniPlayerHeight.current
            Column {
                if (!selectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                text = ui.header?.title ?: playlist.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            HapticIconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) {
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_video)) }

                            HapticIconButton(onClick = { vm.refresh() }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.action_refresh)
                                )
                            }

                            // 收藏按钮
                            HapticIconButton(onClick = {
                                scope.launch {
                                    val header = ui.header ?: playlist
                                    if (isFavorite) {
                                        favoriteRepo.removeFavorite(playlistId, playlistSource)
                                    } else {
                                        favoriteRepo.addFavorite(
                                            id = playlistId,
                                            name = header.title,
                                            coverUrl = header.coverUrl,
                                            trackCount = header.count,
                                            source = playlistSource,
                                            browseId = header.toFavoriteBrowseId(),
                                            subtitle = header.subtitle,
                                            songs = ui.videos.map { it.toSongItem() }
                                        )
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) {
                                        stringResource(R.string.action_unfavorite)
                                    } else {
                                        stringResource(R.string.action_favorite_playlist)
                                    },
                                    tint = playlistTopBarContentColor
                                )
                            }

                            if (hasDownloadManagerEntry) {
                                HapticIconButton(onClick = { showDownloadManager = true }) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.download_manager),
                                        tint = playlistTopBarContentColor
                                    )
                                }
                            }
                        },
                        windowInsets = WindowInsets.statusBars,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = playlistTopBarColor,
                            scrolledContainerColor = playlistTopBarColor,
                            titleContentColor = playlistTopBarContentColor,
                            navigationIconContentColor = playlistTopBarContentColor,
                            actionIconContentColor = playlistTopBarContentColor
                        )
                    )
                } else {
                    val allSelected = selectedIds.size == ui.videos.size && ui.videos.isNotEmpty()
                    TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(
                                R.plurals.common_selected_count,
                                selectedIds.size,
                                selectedIds.size
                            )
                        )
                    },
                        navigationIcon = {
                            HapticIconButton(onClick = { exitSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_exit_multi_select))
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = { if (allSelected) clearSelection() else selectAll() }) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = if (allSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)
                                )
                            }
                            HapticIconButton(
                                onClick = { if (selectedIds.isNotEmpty()) showExportSheet = true },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.explore_export_to_playlist))
                            }
                            HapticIconButton(
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        val selectedSongs = ui.videos
                                            .filter { it.bvid in selectedIds }
                                            .map { it.toSongItem() }

                                        showDownloadManager = true
                                        GlobalDownloadManager.startBatchDownload(context, selectedSongs)
                                        exitSelection()
                                    }
                                },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download_selected_videos))
                            }
                        },
                        windowInsets = WindowInsets.statusBars,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = playlistSelectionTopBarColor,
                            scrolledContainerColor = playlistSelectionTopBarColor,
                            titleContentColor = playlistSelectionTopBarContentColor,
                            navigationIconContentColor = playlistSelectionTopBarContentColor,
                            actionIconContentColor = playlistSelectionTopBarContentColor
                        )
                    )
                }

                PlaylistModernDockedSearchSlot(
                    revealProgress = dockedSearchProgress,
                    coverUrl = displayHeaderCoverUrl,
                    offlineMode = offlineMode,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.search_playlist),
                    inputState = searchInputState,
                    onFocusChanged = { dockedSearchFocused = it },
                    focusRequester = if (searchFieldFocusInHeader) {
                        null
                    } else {
                        searchFocusRequester
                    },
                    dockedProgress = searchDockedVisualProgress
                )

                Box(modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    val activeSong = currentSong
                    val currentVideoIndex = displayedVideos.indexOfFirst { video ->
                        activeSong?.album?.startsWith(PlayerManager.BILI_SOURCE_TAG) == true &&
                            activeSong.id == video.id
                    }

                    PlaylistModernVisualColorsProvider(
                        coverUrl = displayHeaderCoverUrl,
                        offlineMode = offlineMode
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                PlaylistModernHeroHeader(
                                    displayName = displayHeader.title,
                                    coverUrl = displayHeaderCoverUrl,
                                    subtitle = pluralStringResource(
                                        R.plurals.bili_content_count,
                                        displayHeader.count,
                                        displayHeader.count
                                    ),
                                    offlineMode = offlineMode,
                                    height = playlistHeroHeight,
                                    coverContentDescription = displayHeader.title,
                                    actions = if (headerSearchVisible) {
                                        {
                                            Box(
                                                modifier = Modifier.graphicsLayer {
                                                    alpha = headerSearchAlpha
                                                }
                                            ) {
                                                PlaylistModernHeroSearchField(
                                                    query = searchQuery,
                                                    onQueryChange = { searchQuery = it },
                                                    placeholder = stringResource(R.string.search_playlist),
                                                    inputState = searchInputState,
                                                    onFocusChanged = { headerSearchFocused = it },
                                                    focusRequester = if (searchFieldFocusInHeader) {
                                                        searchFocusRequester
                                                    } else {
                                                        null
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }

                        item(
                            key = PLAYLIST_ACTIONS_KEY,
                            contentType = "playlist_actions"
                        ) {
                            PlaylistModernActionSheet(
                                coverUrl = displayHeaderCoverUrl,
                                offlineMode = offlineMode,
                                hasCustomBackground = hasCustomBackground
                            ) {
                                    PlaylistModernPlaybackActions(
                                        songCount = ui.videos.size,
                                        shuffleEnabled = shuffleEnabled,
                                        repeatMode = repeatMode,
                                        onPlayInOrder = { playBiliPlaylist(shuffle = false) },
                                        onShufflePlay = { playBiliPlaylist(shuffle = true) },
                                        onToggleShuffle = {
                                            PlayerManager.setShuffle(!shuffleEnabled)
                                        },
                                        onCycleRepeatMode = {
                                            PlayerManager.cycleRepeatMode()
                                        },
                                        onExportToLocalPlaylist = {
                                            showExportAllSheet = true
                                        }
                                    )
                            }
                        }

                        when {
                            ui.loading && ui.videos.isEmpty() -> {
                                item {
                                    PlaylistModernListItemSurface(
                                        coverUrl = displayHeaderCoverUrl,
                                        offlineMode = offlineMode
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator()
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(stringResource(R.string.bili_loading_favorites))
                                        }
                                    }
                                }
                            }
                            ui.error != null && ui.videos.isEmpty() -> {
                                item {
                                    PlaylistModernListItemSurface(
                                        coverUrl = displayHeaderCoverUrl,
                                        offlineMode = offlineMode
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = stringResource(R.string.bili_load_failed, ui.error ?: ""),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Card(
                                                onClick = { vm.retry() },
                                                shape = RoundedCornerShape(50),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                            ) {
                                                Text(
                                                    stringResource(R.string.action_retry),
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                itemsIndexed(
                                    displayedVideos,
                                    key = { _, it -> it.bvid.ifBlank { it.id.toString() } }
                                ) { index, item ->
                                    val songItem = remember(item) { item.toSongItem() }
                                    val isFavoriteSong = favoriteSongs.any {
                                        it.sameIdentityAs(songItem)
                                    }
                                    PlaylistModernListItemSurface(
                                        coverUrl = displayHeaderCoverUrl,
                                        offlineMode = offlineMode
                                    ) {
                                        VideoRow(
                                            index = index + 1,
                                            video = item,
                                            songItem = songItem,
                                            isFavorite = isFavoriteSong,
                                            onFavoriteToggle = ::toggleSongFavorite,
                                            isCurrentSong = activeSong?.album?.startsWith(PlayerManager.BILI_SOURCE_TAG) == true &&
                                                activeSong.id == item.id,
                                            animatePlayingIndicator = activeSong?.album?.startsWith(PlayerManager.BILI_SOURCE_TAG) == true &&
                                                activeSong.id == item.id &&
                                                isPlaying,
                                            selectionMode = selectionMode,
                                            selected = selectedIds.contains(item.bvid),
                                            onToggleSelect = { toggleSelect(item.bvid) },
                                            onLongPress = {
                                                if (!selectionMode) {
                                                    selectionMode = true
                                                    selectedIds = setOf(item.bvid)
                                                }
                                            },
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        val info = vm.getVideoInfo(item.bvid)
                                                        if (info.pages.size <= 1) {
                                                            val fullList = ui.videos
                                                            val originalIndex =
                                                                fullList.indexOfFirst { it.bvid == item.bvid }
                                                            onPlayAudio(fullList, originalIndex)
                                                        } else {
                                                            partsInfo = info
                                                            showPartsSheet = true
                                                        }
                                                    } catch (e: Exception) {
                                                        NPLogger.e("BiliPlaylistDetail", composeResources.getString(R.string.bili_get_parts_failed), e)
                                                    }
                                                }
                                            },
                                            snackbarHostState = snackbarHostState,
                                            offlineMode = offlineMode
                                        )
                                    }
                                }
                                if (
                                    (playlist.kind == BiliPlaylistKind.COLLECTION ||
                                        playlist.kind == BiliPlaylistKind.SERIES) &&
                                    ui.hasMore
                                ) {
                                    item(
                                        key = "bili_archive_load_more",
                                        contentType = "bili_archive_load_more"
                                    ) {
                                        PlaylistModernListItemSurface(
                                            coverUrl = displayHeaderCoverUrl,
                                            offlineMode = offlineMode
                                        ) {
                                            TextButton(
                                                onClick = vm::loadMoreVideos,
                                                enabled = !ui.loadingMore,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                if (ui.loadingMore) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(stringResource(R.string.bili_uploader_load_more))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }

                    if (currentVideoIndex >= 0) {
                        HapticFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(
                                        resolvePlaylistSongItemIndex(currentVideoIndex)
                                    )
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 16.dp + miniPlayerHeight, end = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                contentDescription = stringResource(R.string.cd_locate_playing)
                            )
                        }
                    }

                    NeriOverlaySnackbarHost(
                        hostState = snackbarHostState,
                        bottomPadding = miniPlayerHeight,
                        applyNavigationBarsPadding = false
                    )
                }
            }

            if (showExportSheet) {
                PlaylistExportSheet(
                    title = stringResource(R.string.playlist_export_to_local),
                    playlists = allLocalPlaylists.filterNot {
                        LocalFilesPlaylist.isSystemPlaylist(it, context)
                    },
                    selectedCount = if (partsSelectionMode) selectedParts.size else selectedIds.size,
                    onDismissRequest = { showExportSheet = false },
                    onCreateAndExport = { name ->
                        val songs = if (partsSelectionMode && partsInfo != null) {
                            val originalVideoItem = displayedVideos.find { it.bvid == partsInfo!!.bvid }
                            partsInfo!!.pages
                                .filter { selectedParts.contains(it.page) }
                                .map { page ->
                                    vm.toSongItem(page, partsInfo!!, originalVideoItem?.coverUrl ?: "")
                                }
                        } else {
                            ui.videos
                                .filter { selectedIds.contains(it.bvid) }
                                .map { it.toSongItem() }
                        }
                        scope.launchLocalPlaylistMutation(
                            operation = "createPlaylistFromBili",
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
                        exitSelection()
                        exitPartsSelection()
                    },
                    onExportToPlaylist = { playlist ->
                        val songs = if (partsSelectionMode && partsInfo != null) {
                            val originalVideoItem = displayedVideos.find { it.bvid == partsInfo!!.bvid }
                            partsInfo!!.pages
                                .filter { selectedParts.contains(it.page) }
                                .map { page ->
                                    vm.toSongItem(page, partsInfo!!, originalVideoItem?.coverUrl ?: "")
                                }
                        } else {
                            ui.videos
                                .filter { selectedIds.contains(it.bvid) }
                                .map { it.toSongItem() }
                        }
                        scope.launchLocalPlaylistMutation(
                            operation = "exportSongsFromBili",
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
                        exitSelection()
                        exitPartsSelection()
                    }
                )
            }

            if (showExportAllSheet) {
                PlaylistExportSheet(
                    title = stringResource(R.string.playlist_export_to_local),
                    playlists = allLocalPlaylists.filterNot {
                        LocalFilesPlaylist.isSystemPlaylist(it, context)
                    },
                    selectedCount = ui.videos.size,
                        onDismissRequest = { showExportAllSheet = false },
                        onCreateAndExport = { name ->
                            val songs = ui.videos.map { it.toSongItem() }
                            scope.launchLocalPlaylistMutation(
                                operation = "createPlaylistFromBiliAll",
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
                            showExportAllSheet = false
                        },
                        onExportToPlaylist = { playlist ->
                            val songs = ui.videos.map { it.toSongItem() }
                            scope.launchLocalPlaylistMutation(
                                operation = "exportAllSongsFromBili",
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
                            showExportAllSheet = false
                        }
                )
            }

            // 下载管理器
            if (showDownloadManager) {
                val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
                val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
                val progress = batchDownloadProgress
                BatchDownloadManagerSheet(
                    batchDownloadProgress = progress,
                    downloadTasks = downloadTasks,
                    progressSummaryText = if (progress != null) {
                        stringResource(
                            R.string.bili_download_progress_format,
                            progress.completedSongs,
                            progress.totalSongs
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.download_tasks_count,
                            pendingTaskCount,
                            pendingTaskCount
                        )
                    },
                    onDismiss = { showDownloadManager = false }
                )
            }

            if (showPartsSheet && partsInfo != null) {
                val currentPartsInfo = partsInfo!!
                BackHandler(enabled = partsSelectionMode) { exitPartsSelection() }
                DensityScaledModalBottomSheet(
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
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_exit_multi_select))
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
                                            contentDescription = if (allSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)
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
                            val originalVideoItem = displayedVideos.find { it.bvid == currentPartsInfo.bvid }

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
                                                    onPlayParts(currentPartsInfo, index, originalVideoItem?.coverUrl ?: "")
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
            BackHandler(enabled = selectionMode) { exitSelection() }
        }
    }
}

private fun BiliVideoItem.toSongItem(): SongItem {
    return SongItem(
        id = this.id,
        name = this.title,
        artist = this.uploader,
        album = PlayerManager.BILI_SOURCE_TAG,
        albumId = 0L,
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    index: Int,
    video: BiliVideoItem,
    songItem: SongItem,
    isFavorite: Boolean,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    isCurrentSong: Boolean,
    animatePlayingIndicator: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val thumbnailUrl = remember(video.coverUrl) {
        buildBiliThumbnailUrl(
            imageUrl = video.coverUrl,
            width = 192,
            height = 108
        )
    }
    val coverRequest = remember(context, thumbnailUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = thumbnailUrl,
            sizePx = 192,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    context.performHapticFeedback()
                    if (selectionMode) onToggleSelect() else onClick()
                },
                onLongClick = onLongPress
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = playlistModernListTertiaryContentColor(),
                    textAlign = TextAlign.Center
                )
            }
        }

        AsyncImage(
            model = coverRequest,
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 100.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = video.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = playlistModernListPrimaryContentColor()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = video.uploader,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = playlistModernListSecondaryContentColor()
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isCurrentSong) {
            PlayingIndicator(
                color = MaterialTheme.colorScheme.primary,
                animate = animatePlayingIndicator
            )
        } else {
            Text(
                text = formatDurationSec(video.durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = playlistModernListSecondaryContentColor()
            )
        }
        
        // 更多操作菜单
        if (!selectionMode) {
            var showMoreMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showMoreMenu = true }
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.common_more_actions),
                        tint = playlistModernListSecondaryContentColor()
                    )
                }
                
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
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
                            PlayerManager.addToQueueNext(songItem)
                            showMoreMenu = false
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
                            PlayerManager.addToQueueEnd(songItem)
                            showMoreMenu = false
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
                            onFavoriteToggle(songItem, isFavorite)
                            showMoreMenu = false
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
                            val songInfo = "${video.title}-${video.uploader}"
                            scope.launch {
                                val messageRes = when (
                                    val result = clipboard.copyPlainTextSafely("text", songInfo)
                                ) {
                                    is ClipboardCopyResult.Copied -> if (result.wasTruncated) {
                                        R.string.toast_copy_truncated
                                    } else {
                                        R.string.toast_copied
                                    }
                                    ClipboardCopyResult.TransactionTooLarge -> R.string.toast_copy_failed
                                }
                                snackbarHostState.showNeriSnackbar(composeResources.getString(messageRes))
                            }
                            showMoreMenu = false
                        }
                    )
                }
            }
        }
    }
}
