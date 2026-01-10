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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.di.AppContainer
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
import moe.ouom.neriplayer.core.download.GlobalDownloadManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BiliPlaylistDetailScreen(
    playlist: BiliPlaylist,
    onBack: () -> Unit = {},
    onPlayAudio: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
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
    // 使用Unit作为key，确保每次进入都重新加载最新数据
    LaunchedEffect(Unit) { vm.start(playlist) }

    // 保存最新的header和videos数据，用于在Screen销毁时更新使用记录
    var latestHeader by remember { mutableStateOf<BiliPlaylist?>(null) }
    var latestVideosSize by remember { mutableStateOf(0) }
    LaunchedEffect(ui.header, ui.videos.size) {
        ui.header?.let { latestHeader = it }
        latestVideosSize = ui.videos.size
    }

    // 在Screen销毁时更新使用记录，确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
            latestHeader?.let { header ->
                AppContainer.playlistUsageRepo.updateInfo(
                    id = header.mediaId,
                    name = header.title,
                    picUrl = header.coverUrl,
                    trackCount = latestVideosSize,
                    fid = header.fid,
                    mid = header.mid,
                    source = "bili"
                )
            }
        }
    }

    // 下载进度
    var showDownloadManager by remember { mutableStateOf(false) }
    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()

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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) searchQuery = ""
                            }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_video)) }

                            if (batchDownloadProgress != null) {
                                HapticIconButton(onClick = { showDownloadManager = true }) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.download_manager),
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
                        title = { Text(stringResource(R.string.common_selected_count, selectedIds.size)) },
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
                        placeholder = { Text(stringResource(R.string.search_playlist)) },
                        singleLine = true
                    )
                }

                Box(modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
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
                                        Text(stringResource(R.string.bili_loading_favorites))
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
                                                    NPLogger.e("BiliPlaylistDetail", context.getString(R.string.bili_get_parts_failed), e)
                                                }
                                            }
                                        },
                                        snackbarHostState = snackbarHostState
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
                        Text(stringResource(R.string.playlist_export_to_local), style = MaterialTheme.typography.titleMedium)
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
                                    Text(stringResource(R.string.explore_song_count, pl.songs.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                placeholder = { Text(stringResource(R.string.playlist_create_name)) },
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
                            ) { Text(stringResource(R.string.playlist_create_and_export)) }
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
                                stringResource(R.string.download_manager),
                                style = MaterialTheme.typography.titleLarge
                            )
                            HapticIconButton(onClick = { showDownloadManager = false }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
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
                                            stringResource(R.string.bili_download_progress_format, progress.completedSongs, progress.totalSongs),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        HapticTextButton(onClick = { AudioDownloadManager.cancelDownload() }) {
                                            Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    if (progress.currentSong.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            stringResource(R.string.bili_downloading_current, progress.currentSong),
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
                                    stringResource(R.string.download_no_tasks),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.download_select_hint),
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
                                title = { Text(stringResource(R.string.common_selected_count, selectedParts.size)) },
                                navigationIcon = {
                                    HapticIconButton(onClick = { exitPartsSelection() }) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_exit_multi_select))
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
                text = stringResource(R.string.bili_content_count, displayData.count),
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
    onClick: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
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
                        contentDescription = stringResource(R.string.common_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_add_to_queue)) },
                        onClick = {
                            val songItem = video.toSongItem()
                            PlayerManager.addToQueueNext(songItem)
                            showMoreMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.playlist_add_to_end)) },
                        onClick = {
                            val songItem = video.toSongItem()
                            PlayerManager.addToQueueEnd(songItem)
                            showMoreMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_song_info)) },
                        onClick = {
                            val songInfo = "${video.title}-${video.uploader}"
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
