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
 * File: moe.ouom.neriplayer.ui.screen.playlist/YouTubeMusicPlaylistDetailScreen
 * Updated: 2026/3/23
 */

import android.app.Application
import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.YouTubeMusicPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.util.search.playlistSearchValues

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun YouTubeMusicPlaylistDetailScreen(
    playlist: YouTubeMusicPlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val viewModel: YouTubeMusicPlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                YouTubeMusicPlaylistDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by viewModel.uiState.collectAsState()
    val hasRequestedPlaylistState = ui.playlist?.browseId == playlist.browseId
    val visibleTracks = if (hasRequestedPlaylistState) ui.tracks else emptyList()
    val requestedAllTracksLoaded = hasRequestedPlaylistState && ui.allTracksLoaded
    val requestedError = ui.error.takeIf { hasRequestedPlaylistState }
    val requestedTracksUnavailable = hasRequestedPlaylistState && ui.tracksUnavailable
    val isRequestedPlaylistLoading = shouldShowYouTubeMusicPlaylistDetailLoading(
        requestedBrowseId = playlist.browseId,
        loadedBrowseId = ui.playlist?.browseId,
        loading = ui.loading
    )
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun toggleSelect(key: String) {
        selectedKeys = if (selectedKeys.contains(key)) selectedKeys - key else selectedKeys + key
    }
    fun clearSelection() { selectedKeys = emptySet() }
    fun selectAll() {
        if (requestedAllTracksLoaded) {
            selectedKeys = visibleTracks.map { it.stableKey() }.toSet()
        }
    }
    fun exitSelection() { selectionMode = false; clearSelection() }
    fun showWaitForFullLoadMessage() {
        scope.launch {
            snackbarHostState.showNeriSnackbar(
                composeResources.getString(R.string.youtube_music_playlist_wait_full_load)
            )
        }
    }

    var showDownloadManager by remember { mutableStateOf(false) }
    val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val hasDownloadManagerEntry = downloadTaskSummary.hasDownloadManagerEntry

    var showExportSheet by remember { mutableStateOf(false) }
    var showExportAllSheet by remember { mutableStateOf(false) }
    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allPlaylists by repo.playlists.collectAsState()
    val favoriteSongs = remember(allPlaylists, context) {
        FavoritesPlaylist.firstOrNull(allPlaylists, context)?.songs.orEmpty()
    }
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val favoriteAddedText = stringResource(R.string.favorite_added)
    val favoriteRemovedText = stringResource(R.string.favorite_removed)
    fun toggleSongFavorite(song: SongItem, isFavoriteSong: Boolean) {
        val message = if (isFavoriteSong) favoriteRemovedText else favoriteAddedText
        scope.launchLocalPlaylistMutation(
            operation = "toggleYouTubeMusicDetailSongFavorite",
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

    LaunchedEffect(playlist.browseId) {
        viewModel.start(playlist)
    }

    var latestPlaylist by remember { mutableStateOf<YouTubeMusicPlaylist?>(playlist) }
    LaunchedEffect(ui.playlist) {
        ui.playlist?.let { latestPlaylist = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestPlaylist?.let { updated ->
                AppContainer.launchBackgroundIo {
                    AppContainer.playlistUsageRepo.updateInfo(
                        id = stableYouTubeMusicId(updated.playlistId.ifBlank { updated.browseId }),
                        name = updated.title,
                        picUrl = updated.coverUrl,
                        trackCount = updated.trackCount,
                        source = "youtubeMusic",
                        browseId = updated.browseId,
                        playlistId = updated.playlistId
                    )
                }
            }
        }
    }

    val resolvedPlaylist = ui.playlist?.takeIf { hasRequestedPlaylistState } ?: playlist
    val playlistChromeColor = rememberPlaylistModernHeroBackgroundColor(
        coverUrl = resolvedPlaylist.coverUrl,
        offlineMode = offlineMode
    )
    val density = LocalDensity.current
    val searchVisible = shouldShowPlaylistSearch(
        showSearch = showSearch,
        selectionMode = selectionMode
    )
    val searchVisibilityProgress = playlistModernSearchVisibilityProgress(
        searchVisible = searchVisible,
        label = "youtube-music-playlist-search-visibility"
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
    val playlistFavoriteId = remember(resolvedPlaylist.playlistId, resolvedPlaylist.browseId) {
        resolvedPlaylist.favoriteId()
    }
    val isFavorite = remember(favorites, playlistFavoriteId) {
        favoriteRepo.isFavorite(playlistFavoriteId, "youtubeMusic")
    }
    val resolvedTrackCount = resolvedPlaylist.trackCount.takeIf { it > 0 } ?: visibleTracks.size
    val displayedTracks = rememberPlaylistSearchResults(
        query = searchQuery,
        items = visibleTracks,
        tokens = { song -> song.playlistSearchValues(context) }
    )
    val currentIndex = displayedTracks.indexOfFirst { it.sameIdentityAs(currentSong) }
    val heroSubtitle = listOfNotNull(
        resolvedPlaylist.creatorName.takeIf { it.isNotBlank() },
        resolvedPlaylist.subtitle.takeIf { it.isNotBlank() },
        stringResource(
            R.string.library_favorite_source_format,
            resolvedTrackCount,
            "YouTube Music"
        )
    ).distinct().joinToString(" · ")
    fun playYouTubeMusicPlaylist(shuffle: Boolean) {
        val startIndex = resolvePlaylistPlaybackStartIndex(
            songCount = visibleTracks.size,
            shuffleEnabled = shuffle,
            randomIndex = if (visibleTracks.isEmpty()) 0 else Random.nextInt(visibleTracks.size)
        )
        if (startIndex < 0) return
        PlayerManager.setShuffle(shuffle)
        onSongClick(visibleTracks, startIndex)
    }

    LaunchedEffect(isFavorite, resolvedPlaylist, visibleTracks, requestedAllTracksLoaded) {
        if (!isFavorite) return@LaunchedEffect
        if (!requestedAllTracksLoaded) return@LaunchedEffect
        favoriteRepo.updateFavoriteMeta(
            id = playlistFavoriteId,
            name = resolvedPlaylist.title,
            coverUrl = resolvedPlaylist.coverUrl,
            trackCount = resolvedTrackCount,
            source = "youtubeMusic",
            browseId = resolvedPlaylist.browseId,
            playlistId = resolvedPlaylist.playlistId,
            subtitle = resolvedPlaylist.subtitle,
            songs = visibleTracks
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            NeriSnackbarHost(
                hostState = snackbarHostState,
                bottomPadding = miniPlayerHeight
            )
        },
        topBar = {
            if (!selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = resolvedPlaylist.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        HapticIconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        HapticIconButton(
                            onClick = {
                                showSearch = !showSearch
                                if (!showSearch) {
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.cd_search_songs)
                            )
                        }
                        HapticIconButton(onClick = viewModel::retry) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh)
                            )
                        }
                        HapticIconButton(
                            onClick = {
                                scope.launch {
                                    if (isFavorite) {
                                        favoriteRepo.removeFavorite(playlistFavoriteId, "youtubeMusic")
                                    } else {
                                        if (!requestedAllTracksLoaded) {
                                            showWaitForFullLoadMessage()
                                            return@launch
                                        }
                                        favoriteRepo.addFavorite(
                                            id = playlistFavoriteId,
                                            name = resolvedPlaylist.title,
                                            coverUrl = resolvedPlaylist.coverUrl,
                                            trackCount = resolvedTrackCount,
                                            source = "youtubeMusic",
                                            browseId = resolvedPlaylist.browseId,
                                            playlistId = resolvedPlaylist.playlistId,
                                            subtitle = resolvedPlaylist.subtitle,
                                            songs = visibleTracks
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isFavorite) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
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
                                    contentDescription = stringResource(R.string.cd_download_manager),
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
                val allSelected = requestedAllTracksLoaded &&
                    selectedKeys.size == visibleTracks.size && visibleTracks.isNotEmpty()
                TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(
                                R.plurals.common_selected_count,
                                selectedKeys.size,
                                selectedKeys.size
                            )
                        )
                    },
                    navigationIcon = {
                        HapticIconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_exit_select))
                        }
                    },
                    actions = {
                        HapticIconButton(onClick = {
                            if (allSelected) {
                                clearSelection()
                            } else if (requestedAllTracksLoaded) {
                                selectAll()
                            } else {
                                showWaitForFullLoadMessage()
                            }
                        }) {
                            Icon(
                                imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                contentDescription = if (allSelected) {
                                    stringResource(R.string.action_deselect_all)
                                } else {
                                    stringResource(R.string.action_select_all)
                                }
                            )
                        }
                        HapticIconButton(
                            onClick = {
                                if (selectedKeys.isNotEmpty()) showExportSheet = true
                            },
                            enabled = selectedKeys.isNotEmpty()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.PlaylistAdd,
                                contentDescription = stringResource(R.string.cd_export_playlist)
                            )
                        }
                        HapticIconButton(
                            onClick = {
                                if (selectedKeys.isNotEmpty()) {
                                    val selectedSongs = visibleTracks.filter { it.stableKey() in selectedKeys }
                                    showDownloadManager = true
                                    GlobalDownloadManager.startBatchDownload(context, selectedSongs)
                                    exitSelection()
                                }
                            },
                            enabled = selectedKeys.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = stringResource(R.string.cd_download_selected)
                            )
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PlaylistModernDockedSearchSlot(
                revealProgress = dockedSearchProgress,
                coverUrl = resolvedPlaylist.coverUrl,
                offlineMode = offlineMode,
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.playlist_search_hint),
                inputState = searchInputState,
                onFocusChanged = { dockedSearchFocused = it },
                focusRequester = if (searchFieldFocusInHeader) {
                    null
                } else {
                    searchFocusRequester
                },
                dockedProgress = searchDockedVisualProgress
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                PlaylistModernVisualColorsProvider(
                    coverUrl = resolvedPlaylist.coverUrl,
                    offlineMode = offlineMode
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = miniPlayerHeight + 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = PLAYLIST_HEADER_KEY) {
                            PlaylistModernHeroHeader(
                                displayName = resolvedPlaylist.title,
                                coverUrl = resolvedPlaylist.coverUrl,
                                subtitle = heroSubtitle,
                                offlineMode = offlineMode,
                                height = playlistHeroHeight,
                                coverContentDescription = resolvedPlaylist.title,
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
                                                placeholder = stringResource(R.string.playlist_search_hint),
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
                            coverUrl = resolvedPlaylist.coverUrl,
                            offlineMode = offlineMode,
                            hasCustomBackground = hasCustomBackground
                        ) {
                                PlaylistModernPlaybackActions(
                                    songCount = visibleTracks.size,
                                    shuffleEnabled = shuffleEnabled,
                                    repeatMode = repeatMode,
                                    onPlayInOrder = { playYouTubeMusicPlaylist(shuffle = false) },
                                    onShufflePlay = { playYouTubeMusicPlaylist(shuffle = true) },
                                    onToggleShuffle = {
                                        PlayerManager.setShuffle(!shuffleEnabled)
                                    },
                                    onCycleRepeatMode = {
                                        PlayerManager.cycleRepeatMode()
                                    },
                                    onExportToLocalPlaylist = {
                                        if (requestedAllTracksLoaded) {
                                            showExportAllSheet = true
                                        } else {
                                            showWaitForFullLoadMessage()
                                        }
                                    }
                                )
                        }
                    }

                    if (!requestedAllTracksLoaded && visibleTracks.isNotEmpty()) {
                        item(key = "youtube_music_partial_loaded") {
                            PlaylistModernListItemSurface(
                                coverUrl = resolvedPlaylist.coverUrl,
                                offlineMode = offlineMode
                            ) {
                                PartialPlaylistBlock(onRetry = viewModel::retry)
                            }
                        }
                    }

                    when {
                        isRequestedPlaylistLoading && visibleTracks.isEmpty() -> {
                            item {
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    LoadingBlock()
                                }
                            }
                        }

                        (requestedError != null || requestedTracksUnavailable) &&
                            visibleTracks.isEmpty() -> {
                            item {
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    ErrorBlock(
                                        message = if (requestedTracksUnavailable) {
                                            stringResource(
                                                R.string.youtube_music_playlist_tracks_unavailable
                                            )
                                        } else {
                                            requestedError.orEmpty()
                                        },
                                        onRetry = viewModel::retry
                                    )
                                }
                            }
                        }

                        displayedTracks.isEmpty() -> {
                            item {
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    EmptyBlock(
                                        text = if (searchQuery.isBlank()) {
                                            stringResource(R.string.library_youtube_music_empty)
                                        } else {
                                            stringResource(R.string.search_no_match)
                                        }
                                    )
                                }
                            }
                        }

                        else -> {
                            itemsIndexed(
                                items = displayedTracks,
                                key = { _, song -> song.stableKey() }
                            ) { index, song ->
                                val isCurrent = currentSong?.sameIdentityAs(song) == true
                                val isFavoriteSong = favoriteSongs.any { it.sameIdentityAs(song) }
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    YouTubeMusicSongRow(
                                        index = index + 1,
                                        song = song,
                                        isCurrentSong = isCurrent,
                                        animatePlayingIndicator = isCurrent && isPlaying,
                                        isFavorite = isFavoriteSong,
                                        onFavoriteToggle = ::toggleSongFavorite,
                                        snackbarHostState = snackbarHostState,
                                        selectionMode = selectionMode,
                                        selected = song.stableKey() in selectedKeys,
                                        onToggleSelect = { toggleSelect(song.stableKey()) },
                                        onLongPress = {
                                            if (!selectionMode) {
                                                selectionMode = true
                                                toggleSelect(song.stableKey())
                                            }
                                        },
                                        onClick = {
                                            if (selectionMode) {
                                                toggleSelect(song.stableKey())
                                            } else {
                                                val targetIndex = visibleTracks.indexOfFirst {
                                                    it.sameIdentityAs(song)
                                                }
                                                if (targetIndex >= 0) {
                                                    onSongClick(visibleTracks, targetIndex)
                                                }
                                            }
                                        },
                                        onPlayNext = { PlayerManager.addToQueueNext(song) },
                                        onAddToQueueEnd = { PlayerManager.addToQueueEnd(song) },
                                        onDownload = {
                                            GlobalDownloadManager.startDownload(context, song)
                                            scope.launch {
                                                snackbarHostState.showNeriSnackbar(
                                                    composeResources.getString(R.string.download_starting, song.displayName())
                                                )
                                            }
                                        },
                                        offlineMode = offlineMode
                                    )
                                }
                            }
                        }
                    }
                }
                }

                if (currentIndex >= 0) {
                    HapticFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(
                                    resolvePlaylistSongItemIndex(
                                        songIndex = currentIndex,
                                        fixedItemCount = if (!requestedAllTracksLoaded && visibleTracks.isNotEmpty()) {
                                            3
                                        } else {
                                            2
                                        }
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                bottom = 16.dp + miniPlayerHeight,
                                end = 16.dp
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = stringResource(R.string.cd_locate_playing)
                        )
                    }
                }
            }
        }
        
        if (showExportSheet) {
            PlaylistExportSheet(
                title = stringResource(R.string.playlist_export_to_local),
                playlists = allPlaylists.filterNot {
                    LocalFilesPlaylist.isSystemPlaylist(it, context)
                },
                selectedCount = selectedKeys.size,
                onDismissRequest = { showExportSheet = false },
                onCreateAndExport = { name ->
                    val songs = visibleTracks
                        .filter { selectedKeys.contains(it.stableKey()) }
                    scope.launchLocalPlaylistMutation(
                        operation = "createPlaylistFromYouTubeMusic",
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
                },
                onExportToPlaylist = { playlist ->
                    val songs = visibleTracks
                        .filter { selectedKeys.contains(it.stableKey()) }
                    scope.launchLocalPlaylistMutation(
                        operation = "exportSongsFromYouTubeMusic",
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
                }
            )
        }

        if (showExportAllSheet) {
            PlaylistExportSheet(
                title = stringResource(R.string.playlist_export_to_local),
                playlists = allPlaylists.filterNot {
                    LocalFilesPlaylist.isSystemPlaylist(it, context)
                },
                selectedCount = visibleTracks.size,
                onDismissRequest = { showExportAllSheet = false },
                onCreateAndExport = { name ->
                    val songs = visibleTracks
                    scope.launchLocalPlaylistMutation(
                        operation = "createPlaylistFromYouTubeMusicAll",
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
                    val songs = visibleTracks
                    scope.launchLocalPlaylistMutation(
                        operation = "exportAllSongsFromYouTubeMusic",
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
        
        BackHandler(enabled = selectionMode) { exitSelection() }
        
        if (showDownloadManager) {
            val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
            BatchDownloadManagerSheet(
                downloadTasks = downloadTasks,
                onDismiss = { showDownloadManager = false }
            )
        }
    }
}

@Composable
private fun PartialPlaylistBlock(onRetry: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.youtube_music_playlist_partial_loaded),
                color = playlistModernListSecondaryContentColor()
            )
            HapticTextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = stringResource(R.string.playlist_loading_content))
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.playlist_load_failed_format, message),
            color = MaterialTheme.colorScheme.error
        )
        HapticTextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

internal fun shouldShowYouTubeMusicPlaylistDetailLoading(
    requestedBrowseId: String,
    loadedBrowseId: String?,
    loading: Boolean
): Boolean = loading || loadedBrowseId != requestedBrowseId

@Composable
private fun EmptyBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = playlistModernListSecondaryContentColor()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeMusicSongRow(
    index: Int,
    song: SongItem,
    isCurrentSong: Boolean,
    animatePlayingIndicator: Boolean,
    isFavorite: Boolean,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    snackbarHostState: SnackbarHostState,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueueEnd: () -> Unit,
    onDownload: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    context.performHapticFeedback()
                    onClick()
                },
                onLongClick = {
                    context.performHapticFeedback()
                    onLongPress()
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectionMode) {
                androidx.compose.material3.Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = playlistModernListTertiaryContentColor(),
                    maxLines = 1
                )
            }
        }

        val coverModel = rememberSongDisplayCoverUrl(song).takeUnless { it.isNullOrBlank() }
        val displayName = song.displayName()
        val displayArtist = song.displayArtist()
        if (!coverModel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = coverModel,
                        offlineMode = offlineMode
                    ),
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = playlistModernListPrimaryContentColor()
            )
            Text(
                text = listOfNotNull(
                    displayArtist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = playlistModernListSecondaryContentColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isCurrentSong) {
            PlayingIndicator(
                color = MaterialTheme.colorScheme.primary,
                animate = animatePlayingIndicator
            )
        } else {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = playlistModernListSecondaryContentColor()
            )
        }

        if (!selectionMode) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.common_more_actions),
                        tint = playlistModernListSecondaryContentColor()
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
                        onPlayNext()
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
                        onAddToQueueEnd()
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
                    text = { Text(stringResource(R.string.download_to_local)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        onDownload()
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
                                    ClipData.newPlainText(
                                        "text",
                                        "${displayName}-${displayArtist}"
                                    )
                                )
                            )
                            snackbarHostState.showNeriSnackbar(
                                composeResources.getString(R.string.toast_copied)
                            )
                        }
                        showMenu = false
                    }
                )
            }
        }
        }
    }
}
