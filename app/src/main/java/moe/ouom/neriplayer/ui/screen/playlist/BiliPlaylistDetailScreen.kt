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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.DownloadManagerViewModel
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDurationSec
import moe.ouom.neriplayer.util.performHapticFeedback
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BiliPlaylistDetailScreen(
    playlist: BiliPlaylist,
    onBack: () -> Unit = {},
    onPlayAudio: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val vm: BiliPlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                BiliPlaylistDetailViewModel(app)
            }
        }
    )
    val ui by vm.uiState.collectAsState()
    val downloadManager: DownloadManagerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                DownloadManagerViewModel(app)
            }
        }
    )
    LaunchedEffect(playlist.mediaId) { vm.start(playlist) }

    // 下载进度
    var showDownloadManager by remember { mutableStateOf(false) }
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
    val isCancelled by AudioDownloadManager.isCancelledFlow.collectAsState()

    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allLocalPlaylists by repo.playlists.collectAsState(initial = emptyList())
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showExportSheet by remember { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var showPartsSheet by remember { mutableStateOf(false) }
    var partsInfo by remember { mutableStateOf<BiliClient.VideoBasicInfo?>(null) }
    val partsSheetState = rememberModalBottomSheetState()

    fun toggleSelect(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }
    fun clearSelection() { selectedIds = emptySet() }
    fun selectAll() { selectedIds = ui.videos.map { it.bvid }.toSet() }
    fun exitSelection() { selectionMode = false; clearSelection() }

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var partsSelectionMode by remember { mutableStateOf(false) }
    var selectedParts by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun exitPartsSelection() {
        partsSelectionMode = false
        selectedParts = emptySet()
    }

    val displayedVideos = remember(ui.videos, searchQuery) {
        if (searchQuery.isBlank()) ui.videos
        else ui.videos.filter {
            it.title.contains(searchQuery, true) || it.uploader.contains(searchQuery, true)
        }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically { it / 6 },
        exit = fadeOut() + slideOutVertically { it / 6 }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) searchQuery = ""
                            }) { Icon(Icons.Filled.Search, contentDescription = "搜索视频") }

                            if (batchDownloadProgress != null) {
                                HapticIconButton(onClick = { showDownloadManager = true }) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = "下载管理器",
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
                    val allSelected = selectedIds.size == ui.videos.size && ui.videos.isNotEmpty()
                    TopAppBar(
                        title = { Text("已选 ${selectedIds.size} 项") },
                        navigationIcon = {
                            HapticIconButton(onClick = { exitSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = "退出多选")
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
                                onClick = { if (selectedIds.isNotEmpty()) showExportSheet = true },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = "导出到歌单")
                            }
                            HapticIconButton(
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        val selectedSongs = ui.videos
                                            .filter { it.bvid in selectedIds }
                                            .map { it.toSongItem() }

                                        scope.launch {
                                            val appCtx = context.applicationContext
                                            AudioDownloadManager.downloadPlaylist(appCtx, selectedSongs)
                                        }

                                        exitSelection()
                                    }
                                },
                                enabled = selectedIds.isNotEmpty()
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = "下载选中视频")
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
                        placeholder = { Text("搜索收藏夹内视频") },
                        singleLine = true
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val miniPlayerHeight = LocalMiniPlayerHeight.current

                    LazyColumn(
                        contentPadding = PaddingValues(
                            bottom = 24.dp + miniPlayerHeight
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Header(playlist = playlist, headerData = ui.header)
                        }

                        when {
                            ui.loading && ui.videos.isEmpty() -> {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator()
                                        Text("  正在加载收藏夹内容...")
                                    }
                                }
                            }
                            ui.error != null && ui.videos.isEmpty() -> {
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "加载失败: ${ui.error}",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Card(
                                            onClick = { vm.retry() },
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
                                }
                            }
                            else -> {
                                itemsIndexed(displayedVideos, key = { _, it -> it.id }) { index, item ->
                                    VideoRow(
                                        index = index + 1,
                                        video = item,
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
                                                            fullList.indexOfFirst { it.id == item.id }
                                                        onPlayAudio(fullList, originalIndex)
                                                    } else {
                                                        partsInfo = info
                                                        showPartsSheet = true
                                                    }
                                                } catch (e: Exception) {
                                                    NPLogger.e("BiliPlaylistDetail", "获取分 P 失败", e)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showExportSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showExportSheet = false },
                    sheetState = exportSheetState
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text("导出到本地歌单", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        LazyColumn {
                            itemsIndexed(allLocalPlaylists) { _, pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                        .clickable {
                                            val songs = if (partsSelectionMode && partsInfo != null) {
                                                val originalVideoItem = displayedVideos.find { it.bvid == partsInfo!!.bvid }
                                                partsInfo!!.pages
                                                    .filter { selectedParts.contains(it.page) }
                                                    .map { page -> vm.toSongItem(page, partsInfo!!, originalVideoItem?.coverUrl ?: "") }
                                            } else {
                                                ui.videos
                                                    .filter { selectedIds.contains(it.bvid) }
                                                    .map { it.toSongItem() }
                                            }

                                            scope.launch {
                                                repo.addSongsToPlaylist(pl.id, songs)
                                                showExportSheet = false
                                                exitSelection()
                                                exitPartsSelection()
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.weight(1f))
                                    Text("${pl.songs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(thickness = DividerDefaults.Thickness, color = DividerDefaults.color)
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
                                enabled = newName.isNotBlank() && (selectedIds.isNotEmpty() || selectedParts.isNotEmpty()),
                                onClick = {
                                    val name = newName.trim()
                                    if (name.isBlank()) return@HapticTextButton

                                    val songs = if (partsSelectionMode && partsInfo != null) {
                                        val originalVideoItem = displayedVideos.find { it.bvid == partsInfo!!.bvid }
                                        partsInfo!!.pages
                                            .filter { selectedParts.contains(it.page) }
                                            .map { page -> vm.toSongItem(page, partsInfo!!, originalVideoItem?.coverUrl ?: "") }
                                    } else {
                                        ui.videos
                                            .filter { selectedIds.contains(it.bvid) }
                                            .map { it.toSongItem() }
                                    }

                                    scope.launch {
                                        repo.createPlaylist(name)
                                        val target = repo.playlists.value.lastOrNull { it.name == name }
                                        if (target != null) {
                                            repo.addSongsToPlaylist(target.id, songs)
                                        }
                                        showExportSheet = false
                                        exitSelection()
                                        exitPartsSelection()
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
                            HapticIconButton(onClick = { showDownloadManager = false }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
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
                                            "下载进度: ${progress.completedSongs}/${progress.totalSongs}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        HapticTextButton(onClick = { AudioDownloadManager.cancelDownload() }) {
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

            if (showPartsSheet && partsInfo != null) {
                val currentPartsInfo = partsInfo!!
                BackHandler(enabled = partsSelectionMode) { exitPartsSelection() }
                ModalBottomSheet(
                    onDismissRequest = {
                        showPartsSheet = false
                        exitPartsSelection()
                    },
                    sheetState = partsSheetState
                ) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        AnimatedVisibility(visible = partsSelectionMode) {
                            val allSelected = selectedParts.size == currentPartsInfo.pages.size
                            TopAppBar(
                                title = { Text("已选 ${selectedParts.size} 项") },
                                navigationIcon = {
                                    HapticIconButton(onClick = { exitPartsSelection() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "退出多选")
                                    }
                                },
                                actions = {
                                    HapticIconButton(onClick = {
                                        if (allSelected) {
                                            selectedParts = emptySet()
                                        } else {
                                            selectedParts = currentPartsInfo.pages.map { it.page }.toSet()
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                            contentDescription = if (allSelected) "取消全选" else "全选"
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
                                        Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = "导出到歌单")
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
        durationMs = this.durationSec * 1000L,
        coverUrl = this.coverUrl
    )
}

@Composable
private fun Header(playlist: BiliPlaylist, headerData: BiliPlaylist?) {
    val displayData = headerData ?: playlist
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(displayData.coverUrl)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = displayData.title,
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
                text = displayData.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(2f, 2f), blurRadius = 4f)
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${displayData.count} 个内容",
                style = MaterialTheme.typography.bodySmall.copy(
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(2f, 2f), blurRadius = 4f)
                ),
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    index: Int,
    video: BiliVideoItem,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(video.coverUrl).build(),
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
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = video.uploader,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDurationSec(video.durationSec),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // 更多操作菜单
        if (!selectionMode) {
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
                            val songItem = video.toSongItem()
                            PlayerManager.addToQueueNext(songItem)
                            showMoreMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("添加到播放队列末尾") },
                        onClick = {
                            val songItem = video.toSongItem()
                            PlayerManager.addToQueueEnd(songItem)
                            showMoreMenu = false
                        }
                    )
                }
            }
        }
    }
}
