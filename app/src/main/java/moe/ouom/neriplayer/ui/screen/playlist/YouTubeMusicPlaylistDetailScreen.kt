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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.countPendingDownloadTasks
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.hasPendingDownloadTasks
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.YouTubeMusicPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.performHapticFeedback

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun YouTubeMusicPlaylistDetailScreen(
    playlist: YouTubeMusicPlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
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
    val snackbarHostState = remember { SnackbarHostState() }
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun toggleSelect(key: String) {
        selectedKeys = if (selectedKeys.contains(key)) selectedKeys - key else selectedKeys + key
    }
    fun clearSelection() { selectedKeys = emptySet() }
    fun selectAll() { selectedKeys = ui.tracks.map { it.stableKey() }.toSet() }
    fun exitSelection() { selectionMode = false; clearSelection() }

    var showDownloadManager by remember { mutableStateOf(false) }
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
    val pendingTaskCount = remember(downloadTasks) { countPendingDownloadTasks(downloadTasks) }
    val hasDownloadManagerEntry = remember(downloadTasks) { hasPendingDownloadTasks(downloadTasks) }

    var showExportSheet by remember { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState()
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

    LaunchedEffect(isFavorite, resolvedPlaylist, ui.tracks) {
        if (!isFavorite) return@LaunchedEffect
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
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.cd_search_songs)
                            )
                        }
                        HapticIconButton(
                            onClick = {
                                scope.launch {
                                    if (isFavorite) {
                                        favoriteRepo.removeFavorite(playlistFavoriteId, "youtubeMusic")
                                    } else {
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
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        if (ui.tracks.isNotEmpty()) {
                            HapticIconButton(onClick = { onSongClick(ui.tracks, 0) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = stringResource(R.string.player_play_all)
                                )
                            }
                        }
                        if (hasDownloadManagerEntry) {
                            HapticIconButton(onClick = { showDownloadManager = true }) {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = stringResource(R.string.cd_download_manager),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                val allSelected = selectedKeys.size == ui.tracks.size && ui.tracks.isNotEmpty()
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
                        HapticIconButton(onClick = { if (allSelected) clearSelection() else selectAll() }) {
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
            AnimatedVisibility(showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.playlist_search_hint)) },
                    singleLine = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = miniPlayerHeight + 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        YouTubeMusicHeroHeader(
                            playlist = resolvedPlaylist,
                            trackCount = resolvedTrackCount
                        )
                    }

                    when {
                        ui.loading && ui.tracks.isEmpty() -> {
                            item {
                                LoadingBlock()
                            }
                        }

                        ui.error != null && ui.tracks.isEmpty() -> {
                            item {
                                ErrorBlock(
                                    message = ui.error.orEmpty(),
                                    onRetry = viewModel::retry
                                )
                            }
                        }

                        displayedTracks.isEmpty() -> {
                            item {
                                EmptyBlock(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.library_youtube_music_empty)
                                    } else {
                                        stringResource(R.string.search_no_match)
                                    }
                                )
                            }
                        }

                        else -> {
                            itemsIndexed(
                                items = displayedTracks,
                                key = { _, song -> song.stableKey() }
                            ) { index, song ->
                                val isCurrent = currentSong?.sameIdentityAs(song) == true
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
                                    }
                                )
                            }
                        }
                    }
                }

                if (currentIndex >= 0) {
                    HapticFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(currentIndex + 1)
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
            ModalBottomSheet(
                onDismissRequest = { showExportSheet = false },
                sheetState = exportSheetState,
                sheetGesturesEnabled = false
            ) {
                Column(
                    Modifier
                        .bottomSheetScrollGuard()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.playlist_export_to_local), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    LazyColumn {
                        itemsIndexed(
                            allPlaylists.filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }
                        ) { _, pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    .combinedClickable(onClick = {
                                        val songs = ui.tracks
                                            .asReversed()
                                            .filter { selectedKeys.contains(it.stableKey()) }
                                        scope.launch {
                                            repo.addSongsToPlaylist(pl.id, songs)
                                            showExportSheet = false
                                        }
                                    }),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.weight(1f))
                                Text(
                                        pluralStringResource(
                                            R.plurals.count_songs_format,
                                            pl.songs.size,
                                            pl.songs.size
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        DividerDefaults.color
                    )
                    Spacer(Modifier.height(12.dp))

                    var newName by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.playlist_create_name)) },
                            singleLine = true
                        )
                        Spacer(Modifier.width(12.dp))
                        HapticTextButton(
                            enabled = newName.isNotBlank() && selectedKeys.isNotEmpty(),
                            onClick = {
                                val name = newName.trim()
                                if (name.isBlank()) return@HapticTextButton
                                val songs = ui.tracks
                                    .asReversed()
                                    .filter { selectedKeys.contains(it.stableKey()) }
                                scope.launch {
                                    repo.createPlaylist(name)
                                    val target = repo.playlists.value.lastOrNull { it.name == name }
                                    if (target != null) {
                                        repo.addSongsToPlaylist(target.id, songs)
                                    }
                                    showExportSheet = false
                                }
                            }
                        ) { Text(stringResource(R.string.playlist_create_and_export)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        
        BackHandler(enabled = selectionMode) { exitSelection() }
        
        if (showDownloadManager) {
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
private fun YouTubeMusicHeroHeader(
    playlist: YouTubeMusicPlaylist,
    trackCount: Int
) {
    val context = LocalContext.current
    val coverModel = playlist.coverUrl.takeUnless { it.isBlank() } ?: "about:blank"
    val surfaceTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        AsyncImage(
            model = offlineCachedImageRequest(context, coverModel),
            contentDescription = playlist.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.12f),
                                Color.Black.copy(alpha = 0.38f),
                                surfaceTint
                            ),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (playlist.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = playlist.subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.55f),
                            offset = Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    ),
                    color = Color.White.copy(alpha = 0.94f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.library_favorite_source_format,
                    trackCount,
                    "YouTube Music"
                ),
                style = MaterialTheme.typography.bodySmall.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                color = Color.White.copy(alpha = 0.88f)
            )
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onDownload: () -> Unit
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        val coverModel = song.displayCoverUrl(context).takeUnless { it.isNullOrBlank() }
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
                    model = offlineCachedImageRequest(context, coverModel),
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
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    displayArtist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!selectionMode) {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.common_more_actions)
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
