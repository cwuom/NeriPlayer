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
 * File: moe.ouom.neriplayer.ui.screen.playlist/LocalPlaylistDetailScreen
 * Created: 2025/8/13
 */

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.viewmodel.DownloadManagerViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.app.Application
import kotlinx.coroutines.DelicateCoroutinesApi
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatTotalDuration
import moe.ouom.neriplayer.util.performHapticFeedback
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)
@Composable
fun LocalPlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val vm: LocalPlaylistDetailViewModel = viewModel()
    val ui = vm.uiState.collectAsState()
    LaunchedEffect(playlistId) { vm.start(playlistId) }

    val playlistOrNull = ui.value.playlist

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            tween(300, easing = FastOutSlowInEasing),
            initialOffsetY = { it }) + fadeIn(tween(150)),
        exit = slideOutVertically(
            tween(250, easing = FastOutSlowInEasing),
            targetOffsetY = { it }) + fadeOut(tween(150))
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            if (playlistOrNull == null) {
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        TopAppBar(
                            title = { Text("歌单") },
                            navigationIcon = {
                                HapticIconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回"
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
                ) { padding ->
                    Box(
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                return@Surface
            }

            val playlist = playlistOrNull
            val isFavorites = playlist.name == LocalPlaylistRepository.FAVORITES_NAME

            val context = LocalContext.current
            val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
            val allPlaylists by repo.playlists.collectAsState()

            var showDeletePlaylistConfirm by remember { mutableStateOf(false) }
            var showDeleteMultiConfirm by remember { mutableStateOf(false) }
            var showExportSheet by remember { mutableStateOf(false) }
            val exportSheetState = rememberModalBottomSheetState()

            var showSearch by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            var showDownloadManager by remember { mutableStateOf(false) }
            
            // 下载进度
            val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()

            // Snackbar状态
            val snackbarHostState = remember { SnackbarHostState() }

            // 可变列表：保持存储层顺序（正序），UI 用 asReversed() 倒序展示
            val localSongs = remember(playlist.id) {
                mutableStateListOf<SongItem>().also { it.addAll(playlist.songs) }
            }

            // 阻断 VM->UI 同步；同时用 pendingOrder 兼容 重排/批删 两类操作
            var blockSync by remember { mutableStateOf(false) }
            var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }
            LaunchedEffect(playlist.songs, blockSync, pendingOrder) {
                val repoIds = playlist.songs.map { it.id }
                val wanted = pendingOrder
                if (!blockSync) {
                    localSongs.clear()
                    localSongs.addAll(playlist.songs)
                } else if (wanted != null && wanted == repoIds) {
                    localSongs.clear()
                    localSongs.addAll(playlist.songs)
                    pendingOrder = null
                    blockSync = false
                }
            }

            // 多选
            var selectionMode by remember { mutableStateOf(false) }
            val selectedIdsState = remember { mutableStateOf<Set<Long>>(emptySet()) }
            fun toggleSelect(id: Long) {
                selectedIdsState.value =
                    if (selectedIdsState.value.contains(id)) selectedIdsState.value - id
                    else selectedIdsState.value + id
            }

            fun selectAll() {
                selectedIdsState.value = localSongs.map { it.id }.toSet()
            }

            fun clearSelection() {
                selectedIdsState.value = emptySet()
            }

            fun exitSelectionMode() {
                selectionMode = false; clearSelection()
            }

            // 重命名
            var showRename by remember { mutableStateOf(false) }
            var renameText by remember { mutableStateOf(TextFieldValue(playlist.name)) }
            var renameError by remember { mutableStateOf<String?>(null) }
            fun validateRename(input: String): String? {
                val name = input.trim()
                if (name.isEmpty()) return "名称不能为空哦"
                if (name.equals(LocalPlaylistRepository.FAVORITES_NAME, ignoreCase = true)) {
                    return "该名称已保留为“${LocalPlaylistRepository.FAVORITES_NAME}”"
                }
                if (allPlaylists.any {
                        it.id != playlist.id && it.name.equals(
                            name,
                            ignoreCase = true
                        )
                    }) {
                    return "已存在同名歌单，请换一个名称"
                }
                return null
            }

            if (showRename) {
                renameError = validateRename(renameText.text)
                AlertDialog(
                    onDismissRequest = { showRename = false },
                    confirmButton = {
                        val trimmed = renameText.text.trim()
                        val disabled =
                            renameError != null || trimmed.equals(playlist.name, ignoreCase = true)
                        HapticTextButton(
                            onClick = {
                                if (!disabled) {
                                    vm.rename(trimmed)
                                    showRename = false
                                }
                            },
                            enabled = !disabled
                        ) { Text("确定") }
                    },
                    dismissButton = {
                        HapticTextButton(onClick = {
                            showRename = false
                        }) { Text("取消") }
                    },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = {
                                renameText = it
                                renameError = validateRename(it.text)
                            },
                            singleLine = true,
                            isError = renameError != null,
                            supportingText = {
                                val err = renameError
                                if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
                            }
                        )
                    },
                    title = { Text("重命名歌单") }
                )
            }

            // 拖拽
            val headerKey = "header"
            val scope = rememberCoroutineScope()

            val reorderState = rememberReorderableLazyListState(
                onMove = { from: ItemPosition, to: ItemPosition ->
                    if (!blockSync) blockSync = true
                    val fromKey = from.key as? Pair<*, *> ?: return@rememberReorderableLazyListState
                    val toKey = to.key as? Pair<*, *> ?: return@rememberReorderableLazyListState
                    val fromId = fromKey.first as? Long ?: return@rememberReorderableLazyListState
                    val toId = toKey.first as? Long ?: return@rememberReorderableLazyListState
                    val fromIdx = localSongs.indexOfFirst { it.id == fromId && it.album == fromKey.second }
                    val toIdx = localSongs.indexOfFirst { it.id == toId && it.album == toKey.second }
                    if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                        localSongs.add(toIdx, localSongs.removeAt(fromIdx))
                    }
                },
                canDragOver = { _, over -> (over.key as? String) != headerKey },
                onDragEnd = { _, _ ->
                    val newOrder = localSongs.map { it.id }
                    pendingOrder = newOrder
                    blockSync = true
                    scope.launch {
                        repo.reorderSongs(playlist.id, newOrder)
                    }
                }
            )

            // 统计
            val totalDurationMs by remember(playlist.id) {
                derivedStateOf { localSongs.sumOf { it.durationMs } }
            }

            // 当前播放 & FAB
            val currentSong by PlayerManager.currentSongFlow.collectAsState()
            val currentIndexInSource = localSongs.indexOfFirst { it.id == currentSong?.id }

            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { 
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(bottom = LocalMiniPlayerHeight.current)
                    ) 
                },
                topBar = {
                    if (!selectionMode) {
                        TopAppBar(
                            title = {
                                Text(
                                    playlist.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                HapticIconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回"
                                    )
                                }
                            },
                            actions = {
                                HapticIconButton(onClick = {
                                    showSearch = !showSearch
                                    if (!showSearch) searchQuery = ""
                                }) { Icon(Icons.Filled.Search, contentDescription = "搜索歌曲") }
                                
                                if (batchDownloadProgress != null) {
                                    HapticIconButton(
                                        onClick = { showDownloadManager = true }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Download,
                                            contentDescription = "下载管理器",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                if (!isFavorites) {
                                    HapticIconButton(onClick = {
                                        renameText = TextFieldValue(playlist.name)
                                        renameError = null
                                        showRename = true
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "重命名")
                                    }
                                    HapticIconButton(onClick = {
                                        showDeletePlaylistConfirm = true
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除歌单"
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
                        val allSelected =
                            selectedIdsState.value.size == localSongs.size && localSongs.isNotEmpty()
                        TopAppBar(
                            title = { Text("已选 ${selectedIdsState.value.size} 项") },
                            navigationIcon = {
                                HapticIconButton(onClick = { exitSelectionMode() }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "退出多选"
                                    )
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
                                        if (selectedIdsState.value.isNotEmpty()) showExportSheet =
                                            true
                                    },
                                    enabled = selectedIdsState.value.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.PlaylistAdd,
                                        contentDescription = "导出到歌单"
                                    )
                                }
                                HapticIconButton(
                                    onClick = {
                                        if (selectedIdsState.value.isNotEmpty()) {
                                            val selectedSongs = localSongs.filter { it.id in selectedIdsState.value }
                                            exitSelectionMode()
                                            GlobalDownloadManager.startBatchDownload(context, selectedSongs)
                                        }
                                    },
                                    enabled = selectedIdsState.value.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = "下载所选歌曲"
                                    )
                                }
                                HapticIconButton(
                                    onClick = {
                                        if (selectedIdsState.value.isNotEmpty()) showDeleteMultiConfirm =
                                            true
                                    },
                                    enabled = selectedIdsState.value.isNotEmpty()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除所选")
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
            ) { padding ->
                val displayedSongs: List<SongItem> = remember(localSongs, searchQuery) {
                    val base = localSongs.asReversed()
                    if (searchQuery.isBlank()) base
                    else base.filter {
                        it.name.contains(searchQuery, true) || it.artist.contains(
                            searchQuery,
                            true
                        )
                    }
                }

                Column(Modifier.padding(padding).fillMaxSize()) {
                    val miniPlayerHeight = LocalMiniPlayerHeight.current
                    AnimatedVisibility(showSearch && !selectionMode) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("搜索歌单内歌曲") },
                            singleLine = true
                        )
                    }

                    Box(Modifier.fillMaxSize()) {
                        val headerHeight: Dp = 240.dp

                        LazyColumn(
                            state = reorderState.listState,
                            contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
                            modifier = Modifier
                                .fillMaxSize()
                                .reorderable(reorderState)
                        ) {
                            // 头图
                            item(key = headerKey) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(headerHeight)
                                ) {
                                    // 头图取“展示顺序”的第一张有封面的
                                    val baseQueue = localSongs.asReversed()
                                    val headerCover = baseQueue.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(headerCover).build(),
                                        contentDescription = playlist.name,
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
                                                        startY = 0f, endY = size.height
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
                                            text = playlist.name,
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
                                            text = "${formatTotalDuration(totalDurationMs)} · ${localSongs.size} 首",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                shadow = Shadow(
                                                    color = Color.Black.copy(alpha = 0.6f),
                                                    offset = Offset(2f, 2f),
                                                    blurRadius = 4f
                                                )
                                            ),
                                            color = Color.White.copy(alpha = 0.92f)
                                        )
                                    }
                                }
                            }

                            // 列表（倒序）
                            itemsIndexed(
                                items = displayedSongs,
                                key = { _, song -> song.id to song.album }
                            ) { revIndex, song ->
                                ReorderableItem(state = reorderState, key = song.id to song.album) { isDragging ->
                                    val rowScale by animateFloatAsState(
                                        targetValue = if (isDragging) 1.02f else 1f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "row-scale"
                                    )

                                    Row(
                                        modifier = Modifier
                                            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .combinedClickable(
                                                    onClick = {
                                                        context.performHapticFeedback()
                                                        if (selectionMode) {
                                                            toggleSelect(song.id)
                                                        } else {
                                                            val baseQueue = localSongs.asReversed() // 原始展示队列
                                                            val pos = baseQueue.indexOfFirst { it.id == song.id && it.album == song.album }
                                                            if (pos >= 0) onSongClick(baseQueue, pos)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!selectionMode) {
                                                            selectionMode = true
                                                            selectedIdsState.value = setOf(song.id)
                                                        } else {
                                                            toggleSelect(song.id)
                                                        }
                                                    }
                                                ),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 序号/复选框
                                            Box(
                                                Modifier.width(48.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (selectionMode) {
                                                    Checkbox(
                                                        checked = selectedIdsState.value.contains(
                                                            song.id
                                                        ),
                                                        onCheckedChange = { toggleSelect(song.id) }
                                                    )
                                                } else {
                                                    Text(
                                                        text = (revIndex + 1).toString(), // 展示顺序编号（倒序）
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Clip
                                                    )
                                                }
                                            }

                                            // 封面
                                            if (!song.coverUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(song.coverUrl).build(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                )
                                            } else {
                                                Spacer(Modifier.size(48.dp))
                                            }
                                            Spacer(Modifier.width(12.dp))

                                            // 标题/歌手
                                            Column(Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = song.name,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    // 下载完成标志
                                                    if (AudioDownloadManager.getLocalFilePath(LocalContext.current, song) != null) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.DownloadDone,
                                                            contentDescription = "已下载",
                                                            modifier = Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = song.artist,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        // 右侧：非多选为时间/播放态；多选为手柄
                                        val isPlayingSong = currentSong?.id == song.id && currentSong?.album == song.album
                                        val trailingVisible = !isDragging && !selectionMode

                                        if (!selectionMode) {
                                            AnimatedVisibility(
                                                visible = trailingVisible,
                                                enter = fadeIn(tween(120)),
                                                exit = fadeOut(tween(100))
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    if (isPlayingSong) {
                                                        PlayingIndicator(color = MaterialTheme.colorScheme.primary)
                                                    } else {
                                                        Text(
                                                            text = formatDuration(song.durationMs),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }

                                                    // 更多操作菜单
                                                    var showMoreMenu by remember { mutableStateOf(false) }
                                                    Box {
                                                        IconButton(
                                                            onClick = { showMoreMenu = true }
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.MoreVert,
                                                                contentDescription = "更多操作",
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }

                                                        DropdownMenu(
                                                            expanded = showMoreMenu,
                                                            onDismissRequest = { showMoreMenu = false }
                                                        ) {
                                                            DropdownMenuItem(
                                                                text = { Text("接下来播放...") },
                                                                onClick = {
                                                                    PlayerManager.addToQueueNext(song)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text("添加到播放队列末尾") },
                                                                onClick = {
                                                                    PlayerManager.addToQueueEnd(song)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .detectReorder(reorderState)
                                                    .padding(8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.DragHandle,
                                                    contentDescription = "拖拽手柄",
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 定位到正在播放
                        val currentIndexInDisplay = if (currentIndexInSource >= 0) {
                            displayedSongs.indexOfFirst { it.id == currentSong?.id }
                        } else -1

                        if (currentIndexInDisplay >= 0) {
                            HapticFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        reorderState.listState.animateScrollToItem(
                                            currentIndexInDisplay + 1
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
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = "定位到正在播放"
                                )
                            }
                        }
                        

                    }
                }

                // 删除歌单二次确认
                if (showDeletePlaylistConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeletePlaylistConfirm = false },
                        title = { Text("删除歌单") },
                        text = { Text("确定要删除此歌单吗？此操作不可恢复！") },
                        confirmButton = {
                            HapticTextButton(onClick = {
                                vm.delete { ok -> if (ok) onDeleted() }
                                showDeletePlaylistConfirm = false
                            }) { Text("删除") }
                        },
                        dismissButton = {
                            HapticTextButton(onClick = {
                                showDeletePlaylistConfirm = false
                            }) { Text("取消") }
                        }
                    )
                }

                // 多选删除确认
                if (showDeleteMultiConfirm) {
                    val count = selectedIdsState.value.size
                    AlertDialog(
                        onDismissRequest = { showDeleteMultiConfirm = false },
                        title = { Text("删除所选歌曲") },
                        text = { Text("确定要从歌单移除所选的 $count 首歌曲吗？") },
                        confirmButton = {
                            HapticTextButton(onClick = {
                                val ids: List<Long> = selectedIdsState.value.toList()
                                val expected = localSongs.filterNot { it.id in ids }.map { it.id }
                                pendingOrder = expected
                                blockSync = true

                                // 立即更新本地 UI，原子删除
                                localSongs.removeAll { it.id in ids }
                                showDeleteMultiConfirm = false
                                exitSelectionMode()

                                vm.removeSongs(ids)
                            }) { Text("删除（$count）") }
                        },
                        dismissButton = {
                            HapticTextButton(onClick = {
                                showDeleteMultiConfirm = false
                            }) { Text("取消") }
                        }
                    )
                }

                // 多选导出
                if (showExportSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showExportSheet = false },
                        sheetState = exportSheetState
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text("导出到歌单", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))

                            LazyColumn {
                                itemsIndexed(allPlaylists.filter { it.id != playlist.id }) { _, pl ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp)
                                            .combinedClickable(onClick = {
                                                context.performHapticFeedback()
                                                val ids = selectedIdsState.value
                                                val displayedSongs = localSongs
                                                val songs =
                                                    displayedSongs.filter { ids.contains(it.id) }
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
                                            "${pl.songs.size} 首",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            var newName by remember { mutableStateOf("") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("新建歌单名称") },
                                    singleLine = true
                                )
                                Spacer(Modifier.width(12.dp))
                                HapticTextButton(
                                    enabled = newName.isNotBlank() && selectedIdsState.value.isNotEmpty(),
                                    onClick = {
                                        val name = newName.trim()
                                        if (name.isBlank()) return@HapticTextButton
                                        val ids = selectedIdsState.value
                                        // 以展示顺序（倒序）筛选导出
                                        val displayedSongs = localSongs.asReversed()
                                        val songs = displayedSongs.filter { ids.contains(it.id) }
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
                                ) { Text("新建并导出") }
                            }
                            Spacer(Modifier.height(12.dp))
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
                                HapticIconButton(
                                    onClick = { showDownloadManager = false }
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "关闭"
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            batchDownloadProgress?.let { progress ->
                                // 下载进度显示
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "下载进度: ${progress.completedSongs}/${progress.totalSongs}",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            HapticTextButton(
                                                onClick = {
                                                    AudioDownloadManager.cancelDownload()
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("下载已取消")
                                                    }
                                                }
                                            ) {
                                                Text("取消", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        
                                        if (progress.currentSong.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                "正在下载: ${progress.currentSong}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        // 总体进度条
                                        Text(
                                            "总体进度: ${progress.percentage}%",
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
                                        
                                        // 单首歌曲进度条
                                        progress.currentProgress?.let { currentProgress ->
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                "当前文件: ${currentProgress.percentage}% (${currentProgress.speedBytesPerSec / 1024} KB/s)",
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
                                // 没有下载任务时的显示
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

                // 多选优先退出
                BackHandler(enabled = selectionMode) { exitSelectionMode() }
            }
        }
    }
}
