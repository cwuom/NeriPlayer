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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.  If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.home.NeteaseHomeSongListScreen
 * Created: 2026/11/20
 */

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistSelectionActions
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernListItemSurface
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernPlaybackActions
import moe.ouom.neriplayer.ui.screen.playlist.SongRow
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistPlaybackStartIndex
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import kotlin.random.Random

/**
 * 通用歌曲列表页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseHomeSongListScreen(
    source: NeteaseHomeSongSource,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val homeViewModel: HomeViewModel = viewModel()

    var songs by remember(source) { mutableStateOf<List<SongItem>>(emptyList()) }
    var loading by remember(source) { mutableStateOf(true) }
    var refreshing by remember(source) { mutableStateOf(false) }
    var errorText by remember(source) { mutableStateOf<String?>(null) }
    var refreshToken by remember(source) { mutableIntStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val loadFailedTemplate = stringResource(CoreCommonR.string.home_section_load_failed)

    LaunchedEffect(source, refreshToken, offlineMode) {
        // 离线时不得发起远程请求：歌曲页可以被进程恢复重新挂载，
        // 此时离线状态不应表现为网络错误。
        if (offlineMode) {
            loading = false
            refreshing = false
            errorText = null
            return@LaunchedEffect
        }
        val isFirstLoad = songs.isEmpty()
        if (isFirstLoad) loading = true else refreshing = true
        errorText = null
        homeViewModel.fetchFullSongSection(source)
            .onSuccess { loaded ->
                songs = loaded
                errorText = null
                // 刷新后只保留仍存在于新列表中的选中项，
                // 否则计数与下载 / 导出会按已经不存在的歌曲执行。
                selectedIds = selectedIds.intersect(loaded.map { it.id }.toSet())
            }
            .onFailure { error ->
                // 请求被下一次刷新取消时不是失败：否则第二次刷新成功后
                // 页面仍会显示上一次请求的取消错误。
                if (error is CancellationException) throw error
                val message = error.message ?: error::class.java.simpleName
                errorText = message
                // 已有旧歌曲时保留列表，只用 Snackbar 提示刷新失败，
                // 否则用户只会看到原列表，不知道刷新已经失败。
                if (songs.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(
                            loadFailedTemplate.format(message)
                        )
                    }
                }
            }
        loading = false
        refreshing = false
    }

    val title = stringResource(source.titleRes)
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allPlaylists by repo.playlists.collectAsStateWithLifecycle()
    val favoriteSongLookup = remember(allPlaylists, context) {
        FavoriteSongLookup(FavoritesPlaylist.firstOrNull(allPlaylists, context)?.songs.orEmpty())
    }
    var showExportSheet by remember { mutableStateOf(false) }
    var showExportAllSheet by remember { mutableStateOf(false) }

    fun toggleSelect(id: Long) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }
    fun clearSelection() { selectedIds = emptySet() }
    fun selectAll() { selectedIds = songs.map { it.id }.toSet() }
    fun exitSelection() { selectionMode = false; clearSelection() }

    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsStateWithLifecycle()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsStateWithLifecycle()

    fun playSection(shuffle: Boolean) {
        if (songs.isEmpty()) return
        val startIndex = resolvePlaylistPlaybackStartIndex(
            songCount = songs.size,
            shuffleEnabled = shuffle,
            randomIndex = Random.nextInt(songs.size)
        )
        if (startIndex < 0) return
        PlayerManager.setShuffle(shuffle)
        onSongClick(songs, startIndex)
    }

    val favoriteAddedText = stringResource(CoreCommonR.string.favorite_added)
    val favoriteRemovedText = stringResource(CoreCommonR.string.favorite_removed)
    fun toggleSongFavorite(song: SongItem, isFavorite: Boolean) {
        scope.launchLocalPlaylistMutation(
            operation = "toggleNeteaseHomeSongFavorite",
            onResult = { result ->
                if (result.isSuccess) {
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(
                            if (isFavorite) favoriteRemovedText else favoriteAddedText
                        )
                    }
                }
            }
        ) {
            if (isFavorite) {
                repo.removeFromFavorites(song)
            } else {
                repo.addToFavorites(song)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            TopAppBar(
                title = {
                    Text(
                        text = "${selectedIds.size}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = { exitSelection() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(CoreCommonR.string.cd_exit_select)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                val selected = songs.filter { it.id in selectedIds }
                                GlobalDownloadManager.startBatchDownload(context, selected)
                                exitSelection()
                            }
                        },
                        enabled = selectedIds.isNotEmpty()
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Outlined.Download,
                            contentDescription = stringResource(CoreCommonR.string.cd_download_selected)
                        )
                    }
                    PlaylistSelectionActions(
                        allSelected = selectedIds.isNotEmpty() && selectedIds.size == songs.size,
                        onToggleSelectAll = {
                            if (selectedIds.size == songs.size) clearSelection() else selectAll()
                        },
                        canExport = selectedIds.isNotEmpty(),
                        onExport = { showExportSheet = true }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        } else {
            TopAppBar(
                title = {
                    Text(text = title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.cd_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        enabled = !offlineMode && !loading && !refreshing,
                        onClick = { refreshToken += 1 }
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(CoreCommonR.string.recommend_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (!offlineMode) refreshToken += 1
            },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                loading && songs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                errorText != null && songs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.home_section_load_failed, errorText.orEmpty()),
                                color = MaterialTheme.colorScheme.error
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { refreshToken += 1 },
                                enabled = !offlineMode
                            ) {
                                Text(stringResource(CoreCommonR.string.home_retry_hint))
                            }
                        }
                    }
                }

                offlineMode && songs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(CoreCommonR.string.home_section_offline_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                songs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(CoreCommonR.string.home_section_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "home-song-list-actions") {
                            PlaylistModernPlaybackActions(
                                songCount = songs.size,
                                shuffleEnabled = shuffleEnabled,
                                repeatMode = repeatMode,
                                onPlayInOrder = { playSection(shuffle = false) },
                                onShufflePlay = { playSection(shuffle = true) },
                                onToggleShuffle = {
                                    PlayerManager.setShuffle(!shuffleEnabled)
                                },
                                onCycleRepeatMode = {
                                    PlayerManager.cycleRepeatMode()
                                },
                                onExportToLocalPlaylist = { showExportAllSheet = true }
                            )
                        }
                        itemsIndexed(
                            songs,
                            key = { _, song -> "home-song-list:${song.id}" }
                        ) { index, song ->
                            PlaylistModernListItemSurface(
                                coverUrl = null,
                                offlineMode = offlineMode
                            ) {
                                SongRow(
                                    index = index + 1,
                                    song = song,
                                    isCurrentSong = currentSong?.sameIdentityAs(song) == true,
                                    animatePlayingIndicator = isPlaying,
                                    isFavorite = favoriteSongLookup.contains(song),
                                    onFavoriteToggle = ::toggleSongFavorite,
                                    showCover = true,
                                    selectionMode = selectionMode,
                                    selected = song.id in selectedIds,
                                    onToggleSelect = { toggleSelect(song.id) },
                                    onLongPress = {
                                        selectionMode = true
                                        toggleSelect(song.id)
                                    },
                                    onClick = { onSongClick(songs, index) },
                                    onPlayNext = { PlayerManager.addToQueueNext(song) },
                                    onAddToQueueEnd = { PlayerManager.addToQueueEnd(song) },
                                    snackbarHostState = snackbarHostState,
                                    offlineMode = offlineMode
                                )
                            }
                        }
                    }
                }
            }
        }
      }

    if (showExportSheet) {
        PlaylistExportSheet(
            title = stringResource(CoreCommonR.string.playlist_export_to_local),
            playlists = allPlaylists.filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) },
            selectedCount = selectedIds.size,
            onDismissRequest = { showExportSheet = false },
            onCreateAndExport = { name ->
                val selected = songs.filter { it.id in selectedIds }
                scope.launchLocalPlaylistMutation(
                    operation = "createPlaylistFromNeteaseHomeSection",
                    onResult = { result ->
                        scope.showPlaylistBatchExportCreatedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = repo,
                            result = result
                        )
                    }
                ) {
                    repo.createPlaylistWithSongs(name, selected)
                }
            },
            onExportToPlaylist = { playlist ->
                val selected = songs.filter { it.id in selectedIds }
                scope.launchLocalPlaylistMutation(
                    operation = "exportSongsFromNeteaseHomeSection",
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
                    repo.addSongsToPlaylistWithResult(playlist.id, selected)
                }
            }
        )
    }
    if (showExportAllSheet) {
        PlaylistExportSheet(
            title = stringResource(CoreCommonR.string.playlist_export_to_local),
            playlists = allPlaylists.filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) },
            selectedCount = songs.size,
            onDismissRequest = { showExportAllSheet = false },
            onCreateAndExport = { name ->
                scope.launchLocalPlaylistMutation(
                    operation = "createPlaylistFromNeteaseHomeSectionAll",
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
                scope.launchLocalPlaylistMutation(
                    operation = "exportAllSongsFromNeteaseHomeSection",
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

        // 多选模式下返回键优先退出多选，而不是直接返回上一页。
        BackHandler(enabled = selectionMode) { exitSelection() }

        NeriOverlaySnackbarHost(
            hostState = snackbarHostState,
            bottomPadding = LocalMiniPlayerHeight.current
        )
    }
}