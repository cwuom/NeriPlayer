package moe.ouom.neriplayer.ui.screen

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
 * File: moe.ouom.neriplayer.ui.screens/LocalPlaylistDetailScreen
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatTotalDuration
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
        enter = slideInVertically(tween(300, easing = FastOutSlowInEasing), initialOffsetY = { it }) + fadeIn(tween(150)),
        exit = slideOutVertically(tween(250, easing = FastOutSlowInEasing), targetOffsetY = { it }) + fadeOut(tween(150))
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (playlistOrNull == null) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("歌单") },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
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
            fun selectAll() { selectedIdsState.value = localSongs.map { it.id }.toSet() }
            fun clearSelection() { selectedIdsState.value = emptySet() }
            fun exitSelectionMode() { selectionMode = false; clearSelection() }

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
                if (allPlaylists.any { it.id != playlist.id && it.name.equals(name, ignoreCase = true) }) {
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
                        val disabled = renameError != null || trimmed.equals(playlist.name, ignoreCase = true)
                        TextButton(
                            onClick = {
                                if (!disabled) {
                                    vm.rename(trimmed)
                                    showRename = false
                                }
                            },
                            enabled = !disabled
                        ) { Text("确定") }
                    },
                    dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
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
                    val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
                    val toId   = to.key as? Long   ?: return@rememberReorderableLazyListState
                    val fromIdx = localSongs.indexOfFirst { it.id == fromId }
                    val toIdx   = localSongs.indexOfFirst { it.id == toId }
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
            val totalDurationMs by remember {
                derivedStateOf { localSongs.sumOf { it.durationMs } }
            }

            // 当前播放 & FAB
            val currentSong by PlayerManager.currentSongFlow.collectAsState()
            val currentIndexInSource = localSongs.indexOfFirst { it.id == currentSong?.id }

            Scaffold(
                topBar = {
                    if (!selectionMode) {
                        TopAppBar(
                            title = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                            actions = {
                                if (!isFavorites) {
                                    IconButton(onClick = { showRename = true }) { Icon(Icons.Filled.Edit, contentDescription = "重命名") }
                                    IconButton(onClick = { showDeletePlaylistConfirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "删除歌单") }
                                }
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    } else {
                        val allSelected = selectedIdsState.value.size == localSongs.size && localSongs.isNotEmpty()
                        TopAppBar(
                            title = { Text("已选 ${selectedIdsState.value.size} 项") },
                            navigationIcon = { IconButton(onClick = { exitSelectionMode() }) { Icon(Icons.Filled.Close, contentDescription = "退出多选") } },
                            actions = {
                                IconButton(onClick = { if (allSelected) clearSelection() else selectAll() }) {
                                    Icon(
                                        imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                        contentDescription = if (allSelected) "取消全选" else "全选"
                                    )
                                }
                                IconButton(
                                    onClick = { if (selectedIdsState.value.isNotEmpty()) showExportSheet = true },
                                    enabled = selectedIdsState.value.isNotEmpty()
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = "导出到歌单")
                                }
                                IconButton(
                                    onClick = { if (selectedIdsState.value.isNotEmpty()) showDeleteMultiConfirm = true },
                                    enabled = selectedIdsState.value.isNotEmpty()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除所选")
                                }
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    val headerHeight: Dp = 240.dp

                    // UI 层用倒序列表展示
                    val displayedSongs: List<SongItem> = localSongs.asReversed()

                    LazyColumn(
                        state = reorderState.listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
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
                                val headerCover = displayedSongs.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(headerCover).build(),
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
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "${formatTotalDuration(totalDurationMs)} · ${localSongs.size} 首",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.92f)
                                    )
                                }
                            }
                        }

                        // 列表（倒序）
                        itemsIndexed(
                            items = displayedSongs,
                            key = { _, song -> song.id }
                        ) { revIndex, song ->
                            // revIndex 是展示顺序的索引；需要映射回源列表索引
                            val sourceIndex = localSongs.indexOfFirst { it.id == song.id }

                            ReorderableItem(state = reorderState, key = song.id) { isDragging ->
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
                                                    if (selectionMode) toggleSelect(song.id)
                                                    else {
                                                        // 播放按“倒序展示列表”作为播放列表
                                                        onSongClick(displayedSongs, revIndex)
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
                                        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                                            if (selectionMode) {
                                                Checkbox(
                                                    checked = selectedIdsState.value.contains(song.id),
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
                                                model = ImageRequest.Builder(LocalContext.current).data(song.coverUrl).build(),
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
                                            Text(
                                                text = song.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.titleMedium
                                            )
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
                                    val isPlayingSong = currentSong?.id == song.id
                                    val trailingVisible = !isDragging && !selectionMode
                                    val trailingScale by animateFloatAsState(
                                        targetValue = if (trailingVisible) 1f else 0.85f,
                                        animationSpec = tween(120, easing = FastOutSlowInEasing),
                                        label = "trailing-scale"
                                    )

                                    if (!selectionMode) {
                                        AnimatedVisibility(
                                            visible = trailingVisible,
                                            enter = fadeIn(tween(120)),
                                            exit = fadeOut(tween(100))
                                        ) {
                                            Box(
                                                modifier = Modifier.graphicsLayer {
                                                    scaleX = trailingScale
                                                    scaleY = trailingScale
                                                }
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
                                            }
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.DragHandle,
                                            contentDescription = "拖拽手柄",
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .detectReorder(reorderState)
                                        )
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
                        FloatingActionButton(
                            onClick = { scope.launch { reorderState.listState.animateScrollToItem(currentIndexInDisplay + 1) } },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = "定位到正在播放")
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
                        TextButton(onClick = {
                            vm.delete { ok -> if (ok) onDeleted() }
                            showDeletePlaylistConfirm = false
                        }) { Text("删除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeletePlaylistConfirm = false }) { Text("取消") }
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
                        TextButton(onClick = {
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
                        TextButton(onClick = { showDeleteMultiConfirm = false }) { Text("取消") }
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
                                            val ids = selectedIdsState.value
                                            val displayedSongs = localSongs
                                            val songs = displayedSongs.filter { ids.contains(it.id) }
                                            scope.launch {
                                                repo.addSongsToPlaylist(pl.id, songs)
                                                showExportSheet = false
                                            }
                                        }),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.weight(1f))
                                    Text("${pl.songs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.HorizontalDivider()
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
                            TextButton(
                                enabled = newName.isNotBlank() && selectedIdsState.value.isNotEmpty(),
                                onClick = {
                                    val name = newName.trim()
                                    if (name.isBlank()) return@TextButton
                                    val ids = selectedIdsState.value
                                    // 以展示顺序（倒序）筛选导出
                                    val displayedSongs = localSongs.asReversed()
                                    val songs = displayedSongs.filter { ids.contains(it.id) }
                                    scope.launch {
                                        repo.createPlaylist(name)
                                        val target = repo.playlists.value.lastOrNull { it.name == name }
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

            // 多选优先退出
            BackHandler(enabled = selectionMode) { exitSelectionMode() }
        }
    }
}
