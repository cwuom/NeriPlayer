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
 * File: moe.ouom.neriplayer.ui.screen.playlist/NeteasePlaylistDetailScreen
 * Created: 2025/8/10
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.PlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.PlaylistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.playlist.PlaylistHeader
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatPlayCount
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NeteasePlaylistDetailScreen(
    playlist: NeteasePlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: PlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                PlaylistDetailViewModel(app)
            }
        }
    )

    val ui by vm.uiState.collectAsState()
    // 使用Unit作为key，确保每次进入都重新加载最新数据
    LaunchedEffect(Unit) { vm.startPlaylist(playlist) }

    // 保存最新的header数据，用于在Screen销毁时更新使用记录
    var latestHeader by remember { mutableStateOf<PlaylistHeader?>(null) }
    LaunchedEffect(ui.header) {
        ui.header?.let { latestHeader = it }
    }

    // 在Screen销毁时更新使用记录，确保返回主页时卡片显示最新信息
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
        vm = vm,
        ui = ui,
        playlistId = playlist.id,
        playlistSource = "netease",
        onBack = onBack,
        onSongClick = onSongClick
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NeteaseAlbumDetailScreen(
    album: NeteaseAlbum,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: PlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                PlaylistDetailViewModel(app)
            }
        }
    )

    val ui by vm.uiState.collectAsState()
    // 使用Unit作为key，确保每次进入都重新加载最新数据
    LaunchedEffect(Unit) { vm.startAlbum(album) }

    // 保存最新的header数据，用于在Screen销毁时更新使用记录
    var latestHeader by remember { mutableStateOf<PlaylistHeader?>(null) }
    LaunchedEffect(ui.header) {
        ui.header?.let { latestHeader = it }
    }

    // 在Screen销毁时更新使用记录，确保返回主页时卡片显示最新信息
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
        vm = vm,
        ui = ui,
        playlistId = album.id,
        playlistSource = "neteaseAlbum",
        onBack = onBack,
        onSongClick = onSongClick
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    vm: PlaylistDetailViewModel,
    ui: PlaylistDetailUiState,
    playlistId: Long,
    playlistSource: String,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {

    val context = LocalContext.current

    // 下载进度
    var showDownloadManager by remember { mutableStateOf(false) }
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 多选 & 导出到本地歌单
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

                                if (batchDownloadProgress != null) {
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
                            title = { Text(stringResource(R.string.common_selected_count, selectedIds.size)) },
                            navigationIcon = {
                                HapticIconButton(onClick = { exitSelection() }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_exit_select))
                                }
                            },
                            actions = {
                                HapticIconButton(onClick = { if (allSelected) clearSelection() else selectAll() }) {
                                    Icon(
                                        imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                        contentDescription = if (allSelected) "取消全选" else "全选"
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
                    val miniPlayerHeight = LocalMiniPlayerHeight.current

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
                                            ?: "Playlist Shuffling",
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
                                            RetryChip { vm.retry() }
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
                                    scope.launch { listState.animateScrollToItem(currentIndex) }
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
                        sheetState = exportSheetState
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text(stringResource(R.string.playlist_export_to_local), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))

                            LazyColumn {
                                itemsIndexed(allPlaylists) { _, pl ->
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
                                            stringResource(R.string.count_songs_format, pl.songs.size),
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
        ModalBottomSheet(
            onDismissRequest = { showDownloadManager = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "下载管理器",
                        style = MaterialTheme.typography.titleLarge
                    )
                    HapticIconButton(onClick = { showDownloadManager = false }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                batchDownloadProgress?.let { progress ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.download_progress_format, progress.completedSongs, progress.totalSongs),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                HapticTextButton(onClick = { AudioDownloadManager.cancelDownload() }) {
                                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                                }
                            }

                            if (progress.currentSong.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.download_current_song, progress.currentSong),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                stringResource(R.string.download_overall_progress, progress.percentage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val animatedOverallProgress by animateFloatAsState(
                                targetValue = (progress.percentage / 100f).coerceIn(0f, 1f),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "overallProgress"
                            )
                            LinearProgressIndicator(
                                progress = { animatedOverallProgress },
                                modifier = Modifier.fillMaxWidth()
                            )

                            progress.currentProgress?.let { currentProgress ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.download_current_file_progress, currentProgress.percentage, currentProgress.speedBytesPerSec / 1024),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val animatedCurrentProgress by animateFloatAsState(
                                    targetValue = if (currentProgress.totalBytes > 0) {
                                        (currentProgress.bytesRead.toFloat() / currentProgress.totalBytes).coerceIn(0f, 1f)
                                    } else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "currentProgress"
                                )
                                LinearProgressIndicator(
                                    progress = { animatedCurrentProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                } ?: run {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "暂无下载任务",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "选择歌曲后点击下载按钮开始下载",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
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
            "点我重试",
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
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    indexWidth: Dp = 48.dp,
    snackbarHostState: SnackbarHostState
) {
    val current by PlayerManager.currentSongFlow.collectAsState()
    val isPlayingSong = current?.id == song.id
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
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

        if (!song.coverUrl.isNullOrBlank()) {
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
                    model = ImageRequest.Builder(LocalContext.current).data(song.coverUrl).build(),
                    contentDescription = song.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    (song.album.takeIf { it.isNotBlank() })?.replace("Netease", "") ?: ""
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isPlayingSong) {
            PlayingIndicator(color = MaterialTheme.colorScheme.primary)
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
                            val songInfo = "${song.name}-${song.artist}"
                            clipboardManager.setText(AnnotatedString(songInfo))
                            scope.launch {
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
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "playing")
    val animValues = listOf(
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

    val barWidth = 3.dp
    val barMaxHeight = 12.dp

    Row(
        modifier = modifier.height(barMaxHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        animValues.forEach { anim ->
            Box(
                Modifier
                    .width(barWidth)
                    .height(barMaxHeight * anim.value)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}