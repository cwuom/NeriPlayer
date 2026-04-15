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
 * File: moe.ouom.neriplayer.ui.screen.playlist/NeteaseCollectionDetailScreen
 * Created: 2025/8/10
 */

import android.app.Application
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.countPendingDownloadTasks
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.hasPendingDownloadTasks
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCollectionDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCollectionDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCollectionHeader
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatPlayCount
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NeteasePlaylistDetailScreen(
    playlist: PlaylistSummary,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: NeteaseCollectionDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                NeteaseCollectionDetailViewModel(app)
            }
        }
    )

    val ui by vm.uiState.collectAsState()
    // 使用 Unit 作为 key，确保每次进入都重新加载最新数据
    LaunchedEffect(Unit) { vm.startPlaylist(playlist) }

    // 保存最新的header数据，用于在Screen销毁时更新使用记录
    var latestHeader by remember { mutableStateOf<NeteaseCollectionHeader?>(null) }
    LaunchedEffect(ui.header) {
        ui.header?.let { latestHeader = it }
    }

    // 在 Screen 销毁时更新使用记录，确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
            latestHeader?.let { header ->
                AppContainer.playlistUsageRepo.updateInfo(
                    id = header.id,
                    name = header.name,
                    picUrl = header.coverUrl,
                    trackCount = header.trackCount,
                    source = "netease"
                )
            }
        }
    }

    DetailScreen(
        ui = ui,
        playlistId = playlist.id,
        playlistSource = "netease",
        onRetry = vm::retry,
        onBack = onBack,
        onSongClick = onSongClick
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NeteaseAlbumDetailScreen(
    album: AlbumSummary,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: NeteaseCollectionDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                NeteaseCollectionDetailViewModel(app)
            }
        }
    )

    val ui by vm.uiState.collectAsState()
    // 使用 Unit 作为 key，确保每次进入都重新加载最新数据
    LaunchedEffect(Unit) { vm.startAlbum(album) }

    // 保存最新的header数据，用于在Screen销毁时更新使用记录
    var latestHeader by remember { mutableStateOf<NeteaseCollectionHeader?>(null) }
    LaunchedEffect(ui.header) {
        ui.header?.let { latestHeader = it }
    }

    // 在 Screen 销毁时更新使用记录，确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
            latestHeader?.let { header ->
                AppContainer.playlistUsageRepo.updateInfo(
                    id = header.id,
                    name = header.name,
                    picUrl = header.coverUrl,
                    trackCount = header.trackCount,
                    source = "neteaseAlbum"
                )
            }
        }
    }

    DetailScreen(
        ui = ui,
        playlistId = album.id,
        playlistSource = "neteaseAlbum",
        onRetry = vm::retry,
        onBack = onBack,
        onSongClick = onSongClick
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun DetailScreen(
    ui: NeteaseCollectionDetailUiState,
    playlistId: Long,
    playlistSource: String,
    onRetry: () -> Unit,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {

    val context = LocalContext.current

    // 下载进度
    var showDownloadManager by remember { mutableStateOf(false) }
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
    val pendingTaskCount = remember(downloadTasks) { countPendingDownloadTasks(downloadTasks) }
    val hasDownloadManagerEntry = remember(downloadTasks) { hasPendingDownloadTasks(downloadTasks) }

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val listState = rememberSaveable(playlistId, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 多选与导出到本地歌单
    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allPlaylists by repo.playlists.collectAsState()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    fun toggleSelect(id: Long) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }
    fun clearSelection() { selectedIds = emptySet() }
    fun selectAll() { selectedIds = ui.tracks.map { it.id }.toSet() }
    fun exitSelection() { selectionMode = false; clearSelection();}

    // 收藏歌单
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val isFavorite = remember(favorites, playlistId) {
        favoriteRepo.isFavorite(playlistId, playlistSource)
    }

    LaunchedEffect(isFavorite, ui.header, ui.tracks) {
        if (!isFavorite) return@LaunchedEffect
        val header = ui.header ?: return@LaunchedEffect
        favoriteRepo.updateFavoriteMeta(
            id = header.id,
            name = header.name,
            coverUrl = header.coverUrl,
            trackCount = header.trackCount,
            source = playlistSource,
            songs = ui.tracks
        )
    }

    var showExportSheet by remember { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState()

    val snackbarHostState = remember { SnackbarHostState() }

    val headerHeight: Dp = 280.dp

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 6 },
        exit = fadeOut() + slideOutVertically { it / 6 }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent
            ) {
                val miniPlayerHeight = LocalMiniPlayerHeight.current
                Column {
                    // 顶部栏：普通模式 / 多选模式
                    if (!selectionMode) {
                        TopAppBar(
                            title = {
                                Text(
                                    text = ui.header?.name ?: "Playlist Shuffling",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                HapticIconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back)
                                    )
                                }
                            },
                            actions = {
                                HapticIconButton(onClick = {
                                    showSearch = !showSearch
                                    if (!showSearch) searchQuery = ""
                                }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search_songs)) }

                                // 收藏按钮
                                HapticIconButton(onClick = {
                                    scope.launch {
                                        if (isFavorite) {
                                            favoriteRepo.removeFavorite(playlistId, playlistSource)
                                        } else {
                                            ui.header?.let { header ->
                                                favoriteRepo.addFavorite(
                                                    id = playlistId,
                                                    name = header.name,
                                                    coverUrl = header.coverUrl,
                                                    trackCount = header.trackCount,
                                                    source = playlistSource,
                                                    songs = ui.tracks
                                                )
                                            }
                                        }
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = if (isFavorite) stringResource(R.string.action_unfavorite) else stringResource(R.string.action_favorite_playlist),
                                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
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
                                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    } else {
                        val allSelected =
                            selectedIds.size == ui.tracks.size && ui.tracks.isNotEmpty()
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
                                        if (selectedIds.isNotEmpty()) showExportSheet = true
                                    },
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.PlaylistAdd,
                                        contentDescription = stringResource(R.string.cd_export_playlist)
                                    )
                                }
                                HapticIconButton(
                                    onClick = {
                                        if (selectedIds.isNotEmpty()) {
                                            val selectedSongs =
                                                ui.tracks.filter { it.id in selectedIds }
                                            showDownloadManager = true
                                            GlobalDownloadManager.startBatchDownload(
                                                context,
                                                selectedSongs
                                            )
                                            exitSelection()
                                        }
                                    },
                                    enabled = selectedIds.isNotEmpty()
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

                    AnimatedVisibility(showSearch && !selectionMode) {
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

                    val displayedTracks = remember(ui.tracks, searchQuery) {
                        if (searchQuery.isBlank()) ui.tracks
                        else ui.tracks.filter {
                            it.name.contains(
                                searchQuery,
                                true
                            ) || it.artist.contains(searchQuery, true)
                        }
                    }
                    val currentIndex = displayedTracks.indexOfFirst { it.id == currentSong?.id }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                bottom = 24.dp + miniPlayerHeight
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(headerHeight)
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(ui.header?.coverUrl.takeUnless { it.isNullOrBlank() }
                                                ?: "about:blank")
                                            .crossfade(true)
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .networkCachePolicy(CachePolicy.ENABLED)
                                            .build(),
                                        contentDescription = ui.header?.name
                                            ?: stringResource(R.string.playlist_title),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .drawWithContent {
                                                drawContent()
                                                drawRect(
                                                    brush = Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.Black.copy(alpha = 0.10f),
                                                            Color.Black.copy(alpha = 0.35f),
                                                            Color.Transparent
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
                                            text = ui.header?.name ?: "Playlist Shuffling",
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
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.playlist_play_count_format,
                                                formatPlayCount(context, ui.header?.playCount ?: 0),
                                                ui.header?.trackCount ?: 0
                                            ),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                shadow = Shadow(
                                                    color = Color.Black.copy(alpha = 0.6f),
                                                    offset = Offset(2f, 2f),
                                                    blurRadius = 4f
                                                )
                                            ),
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    }
                                }
                            }

                            // 状态块
                            when {
                                ui.loading && ui.tracks.isEmpty() -> {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator()
                                            Text(stringResource(R.string.playlist_loading_content))
                                        }
                                    }
                                }

                                ui.error != null && ui.tracks.isEmpty() -> {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = stringResource(R.string.playlist_load_failed_format, ui.error ?: ""),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            RetryChip(onRetry)
                                        }
                                    }
                                }

                                else -> {
                                    itemsIndexed(
                                        displayedTracks,
                                        key = { _, it -> it.id }) { index, item ->
                                        SongRow(
                                            index = index + 1,
                                            song = item,
                                            showCover = ui.header?.isAlbum != true,
                                            selectionMode = selectionMode,
                                            selected = selectedIds.contains(item.id),
                                            onToggleSelect = { toggleSelect(item.id) },
                                            onLongPress = {
                                                if (!selectionMode) {
                                                    selectionMode = true
                                                    selectedIds = setOf(item.id)
                                                } else {
                                                    toggleSelect(item.id)
                                                }
                                            },
                                            onClick = {
                                                NPLogger.d(
                                                    "NERI-UI",
                                                    "tap song index=$index id=${item.id}"
                                                )
                                                val full = ui.tracks
                                                val pos = full.indexOfFirst { it.id == item.id }
                                                if (pos >= 0) onSongClick(full, pos)
                                            },
                                            snackbarHostState = snackbarHostState
                                        )
                                    }
                                }
                            }
                        }

                        if (currentIndex >= 0) {
                            HapticFloatingActionButton(
                                onClick = {
                                    scope.launch { listState.animateScrollToItem(currentIndex + 1) }
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

                // 导出面板 //
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
                                                // 倒序导出
                                                val songs = ui.tracks
                                                    .asReversed()
                                                    .filter { selectedIds.contains(it.id) }
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
                                    enabled = newName.isNotBlank() && selectedIds.isNotEmpty(),
                                    onClick = {
                                        val name = newName.trim()
                                        if (name.isBlank()) return@HapticTextButton
                                        // 倒序导出
                                        val songs = ui.tracks
                                            .asReversed()
                                            .filter { selectedIds.contains(it.id) }
                                        scope.launch {
                                            repo.createPlaylist(name)
                                            val target =
                                                repo.playlists.value.lastOrNull { it.name == name }
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
                // 允许返回键优先退出多选
                BackHandler(enabled = selectionMode) { exitSelection() }

                // Snackbar
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = LocalMiniPlayerHeight.current)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                )
            }
        }
    }

    // 下载管理器
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

/* 小组件 */
@Composable
private fun RetryChip(onClick: () -> Unit) {
    Card(
        onClick = onClick,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    index: Int,
    song: SongItem,
    showCover: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    indexWidth: Dp = 48.dp,
    snackbarHostState: SnackbarHostState
) {
    val current by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val isCurrentSong = current?.sameIdentityAs(song) == true
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    context.performHapticFeedback()
                    if (selectionMode) onToggleSelect() else onClick()
                },
                onLongClick = { onLongPress() }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(indexWidth),
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center
                )
            }
        }

        val itemContext = LocalContext.current
        val displayCoverUrl = song.displayCoverUrl(itemContext)
        if (showCover && !displayCoverUrl.isNullOrBlank()) {
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
                    model = offlineCachedImageRequest(itemContext, displayCoverUrl),
                    contentDescription = song.displayName(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = song.displayName(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = listOfNotNull(
                    song.displayArtist().takeIf { it.isNotBlank() },
                    (song.album.takeIf { it.isNotBlank() })?.replace("Netease", "") ?: ""
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isCurrentSong) {
            PlayingIndicator(
                color = MaterialTheme.colorScheme.primary,
                animate = isPlaying
            )
        } else {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        contentDescription = stringResource(R.string.cd_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.local_playlist_play_next)) },
                        onClick = {
                            PlayerManager.addToQueueNext(song)
                            showMoreMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_add_to_end)) },
                        onClick = {
                            PlayerManager.addToQueueEnd(song)
                            showMoreMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_song_info)) },
                        onClick = {
                            val songInfo = "${song.displayName()}-${song.displayArtist()}"
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", songInfo)))
                                snackbarHostState.showSnackbar(context.getString(R.string.toast_copied))
                            }
                            showMoreMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PlayingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    animate: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "playing")
    val flatHeight = 0.35f
    val transitionSpec: FiniteAnimationSpec<Float> =
        if (animate) snap() else tween(durationMillis = 180, easing = FastOutSlowInEasing)
    val animatedValues = listOf(
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 300),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar1"
        ),
        transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 350),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar2"
        ),
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar3"
        )
    )
    val barHeights = listOf(
        animateFloatAsState(
            targetValue = if (animate) animatedValues[0].value else flatHeight,
            animationSpec = transitionSpec,
            label = "bar1Hold"
        ).value,
        animateFloatAsState(
            targetValue = if (animate) animatedValues[1].value else flatHeight,
            animationSpec = transitionSpec,
            label = "bar2Hold"
        ).value,
        animateFloatAsState(
            targetValue = if (animate) animatedValues[2].value else flatHeight,
            animationSpec = transitionSpec,
            label = "bar3Hold"
        ).value
    )

    val barWidth = 3.dp
    val barMaxHeight = 12.dp

    Row(
        modifier = modifier.height(barMaxHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        barHeights.forEach { barHeight ->
            Box(
                Modifier
                    .width(barWidth)
                    .height(barMaxHeight * barHeight)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}
