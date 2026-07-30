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
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
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

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
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
    val viewModel: YouTubeMusicPlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                YouTubeMusicPlaylistDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by viewModel.uiState.collectAsState()
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
        if (ui.allTracksLoaded) {
            selectedKeys = ui.tracks.map { it.stableKey() }.toSet()
        }
    }
    fun exitSelection() { selectionMode = false; clearSelection() }
    fun showWaitForFullLoadMessage() {
        scope.launch {
            snackbarHostState.showSnackbar(
                context.getString(R.string.youtube_music_playlist_wait_full_load)
            )
        }
    }

    var showDownloadManager by remember { mutableStateOf(false) }
    val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val pendingTaskCount = downloadTaskSummary.pendingTaskCount
    val hasDownloadManagerEntry = downloadTaskSummary.hasPendingTasks

    var showExportSheet by remember { mutableStateOf(false) }
    var showExportAllSheet by remember { mutableStateOf(false) }
    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allPlaylists by repo.playlists.collectAsState()
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()

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

    val resolvedPlaylist = ui.playlist ?: playlist
    val playlistChromeColor = rememberPlaylistModernHeroBackgroundColor(
        coverUrl = resolvedPlaylist.coverUrl,
        offlineMode = offlineMode
    )
    val playlistChromeCollapsed = listState.firstVisibleItemIndex > 0
    val playlistTopBarColor = if (playlistChromeCollapsed) {
        playlistModernCollapsedTopBarColor()
    } else {
        playlistChromeColor
    }
    val playlistTopBarContentColor = if (playlistChromeCollapsed) {
        playlistModernCollapsedTopBarContentColor()
    } else {
        Color.White
    }
    val playlistHeroHeight by animateDpAsState(
        targetValue = if (showSearch && !selectionMode && !playlistChromeCollapsed) {
            PlaylistModernHeroSearchHeight
        } else {
            PlaylistModernHeroHeight
        },
        label = "youtube-music-playlist-hero-height"
    )
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(
        initial = false
    )
    val backgroundImageUri by AppContainer.settingsRepo.backgroundImageUriFlow.collectAsState(
        initial = null
    )
    val hasCustomBackground = backgroundImageUri != null
    LaunchedEffect(showSearch, selectionMode, playlistChromeCollapsed, autoShowKeyboard) {
        if (showSearch && !selectionMode && autoShowKeyboard) {
            delay(120)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    val playlistFavoriteId = remember(resolvedPlaylist.playlistId, resolvedPlaylist.browseId) {
        resolvedPlaylist.favoriteId()
    }
    val isFavorite = remember(favorites, playlistFavoriteId) {
        favoriteRepo.isFavorite(playlistFavoriteId, "youtubeMusic")
    }
    val resolvedTrackCount = resolvedPlaylist.trackCount.takeIf { it > 0 } ?: ui.tracks.size
    val displayedTracks = remember(ui.tracks, searchQuery) {
        if (searchQuery.isBlank()) {
            ui.tracks
        } else {
            ui.tracks.filter { song ->
                song.displayName().contains(searchQuery, ignoreCase = true) ||
                    song.displayArtist().contains(searchQuery, ignoreCase = true) ||
                    song.name.contains(searchQuery, ignoreCase = true) ||
                    song.artist.contains(searchQuery, ignoreCase = true) ||
                    song.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val currentIndex = displayedTracks.indexOfFirst { it.sameIdentityAs(currentSong) }
    val heroSubtitle = listOfNotNull(
        resolvedPlaylist.subtitle.takeIf { it.isNotBlank() },
        stringResource(
            R.string.library_favorite_source_format,
            resolvedTrackCount,
            "YouTube Music"
        )
    ).joinToString(" · ")
    fun playYouTubeMusicPlaylist(shuffle: Boolean) {
        val startIndex = resolvePlaylistPlaybackStartIndex(
            songCount = ui.tracks.size,
            shuffleEnabled = shuffle,
            randomIndex = if (ui.tracks.isEmpty()) 0 else Random.nextInt(ui.tracks.size)
        )
        if (startIndex < 0) return
        PlayerManager.setShuffle(shuffle)
        onSongClick(ui.tracks, startIndex)
    }

    LaunchedEffect(isFavorite, resolvedPlaylist, ui.tracks, ui.allTracksLoaded) {
        if (!isFavorite) return@LaunchedEffect
        if (!ui.allTracksLoaded) return@LaunchedEffect
        favoriteRepo.updateFavoriteMeta(
            id = playlistFavoriteId,
            name = resolvedPlaylist.title,
            coverUrl = resolvedPlaylist.coverUrl,
            trackCount = resolvedTrackCount,
            source = "youtubeMusic",
            browseId = resolvedPlaylist.browseId,
            playlistId = resolvedPlaylist.playlistId,
            subtitle = resolvedPlaylist.subtitle,
            songs = ui.tracks
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                        if (!ui.allTracksLoaded) {
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
                                            songs = ui.tracks
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
                val allSelected = ui.allTracksLoaded && selectedKeys.size == ui.tracks.size && ui.tracks.isNotEmpty()
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
                            } else if (ui.allTracksLoaded) {
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
                                    val selectedSongs = ui.tracks.filter { it.stableKey() in selectedKeys }
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
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
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
            if (showSearch && !selectionMode && playlistChromeCollapsed) {
                PlaylistModernVisualColorsProvider(
                    coverUrl = resolvedPlaylist.coverUrl,
                    offlineMode = offlineMode
                ) {
                    PlaylistModernDockedSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = stringResource(R.string.playlist_search_hint),
                        focusRequester = searchFocusRequester
                    )
                }
            }

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
                                actions = if (showSearch && !selectionMode && !playlistChromeCollapsed) {
                                    {
                                        PlaylistModernHeroSearchField(
                                            query = searchQuery,
                                            onQueryChange = { searchQuery = it },
                                            placeholder = stringResource(R.string.playlist_search_hint),
                                            focusRequester = searchFocusRequester
                                        )
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
                                    songCount = ui.tracks.size,
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
                                        if (ui.allTracksLoaded) {
                                            showExportAllSheet = true
                                        } else {
                                            showWaitForFullLoadMessage()
                                        }
                                    }
                                )
                        }
                    }

                    if (!ui.allTracksLoaded && ui.tracks.isNotEmpty()) {
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
                        ui.loading && ui.tracks.isEmpty() -> {
                            item {
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    LoadingBlock()
                                }
                            }
                        }

                        ui.error != null && ui.tracks.isEmpty() -> {
                            item {
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    ErrorBlock(
                                        message = ui.error.orEmpty(),
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
                                PlaylistModernListItemSurface(
                                    coverUrl = resolvedPlaylist.coverUrl,
                                    offlineMode = offlineMode
                                ) {
                                    YouTubeMusicSongRow(
                                        index = index + 1,
                                        song = song,
                                        isCurrentSong = isCurrent,
                                        animatePlayingIndicator = isCurrent && isPlaying,
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
                                                val targetIndex = ui.tracks.indexOfFirst {
                                                    it.sameIdentityAs(song)
                                                }
                                                if (targetIndex >= 0) {
                                                    onSongClick(ui.tracks, targetIndex)
                                                }
                                            }
                                        },
                                        onPlayNext = { PlayerManager.addToQueueNext(song) },
                                        onAddToQueueEnd = { PlayerManager.addToQueueEnd(song) },
                                        onDownload = {
                                            GlobalDownloadManager.startDownload(context, song)
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.download_starting, song.displayName())
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
                                        fixedItemCount = if (!ui.allTracksLoaded && ui.tracks.isNotEmpty()) {
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
                    val songs = ui.tracks
                        .filter { selectedKeys.contains(it.stableKey()) }
                    scope.launchLocalPlaylistMutation("createPlaylistFromYouTubeMusic") {
                        repo.createPlaylistWithSongs(name, songs)
                    }
                },
                onExportToPlaylist = { playlist ->
                    val songs = ui.tracks
                        .filter { selectedKeys.contains(it.stableKey()) }
                    scope.launchLocalPlaylistMutation("exportSongsFromYouTubeMusic") {
                        repo.addSongsToPlaylist(playlist.id, songs)
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
                selectedCount = ui.tracks.size,
                onDismissRequest = { showExportAllSheet = false },
                onCreateAndExport = { name ->
                    val songs = ui.tracks
                    scope.launchLocalPlaylistMutation("createPlaylistFromYouTubeMusicAll") {
                        repo.createPlaylistWithSongs(name, songs)
                    }
                    showExportAllSheet = false
                },
                onExportToPlaylist = { playlist ->
                    val songs = ui.tracks
                    scope.launchLocalPlaylistMutation("exportAllSongsFromYouTubeMusic") {
                        repo.addSongsToPlaylist(playlist.id, songs)
                    }
                    showExportAllSheet = false
                }
            )
        }
        
        BackHandler(enabled = selectionMode) { exitSelection() }
        
        if (showDownloadManager) {
            val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
            val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
            val progress = batchDownloadProgress
            BatchDownloadManagerSheet(
                batchDownloadProgress = progress,
                downloadTasks = downloadTasks,
                progressSummaryText = if (progress != null) {
                    stringResource(
                        R.string.download_progress_format,
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
                    onClick = {
                        onPlayNext()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_add_to_end)) },
                    onClick = {
                        onAddToQueueEnd()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download_to_local)) },
                    onClick = {
                        onDownload()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_song_info)) },
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
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.toast_copied)
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
