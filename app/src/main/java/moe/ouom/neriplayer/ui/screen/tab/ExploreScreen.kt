package moe.ouom.neriplayer.ui.screen.tab

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
 * File: moe.ouom.neriplayer.ui.screen.tab/ExploreScreen
 * Created: 2025/8/8
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.ExploreViewModel
import moe.ouom.neriplayer.ui.viewmodel.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.SearchSource
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ExploreScreen(
    gridState: LazyGridState,
    onPlay: (NeteasePlaylist) -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val vm: ExploreViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ExploreViewModel(context.applicationContext as Application) }
        }
    )
    val ui by vm.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allLocalPlaylists by repo.playlists.collectAsState(initial = emptyList())

    var showPartsSheet by remember { mutableStateOf(false) }
    var partsInfo by remember { mutableStateOf<BiliClient.VideoBasicInfo?>(null) }
    var clickedSongCoverUrl by remember { mutableStateOf("") }
    val partsSheetState = rememberModalBottomSheetState()

    var partsSelectionMode by remember { mutableStateOf(false) }
    var selectedParts by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var showExportSheet by remember { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState()

    fun exitPartsSelection() {
        partsSelectionMode = false
        selectedParts = emptySet()
    }

    LaunchedEffect(Unit) {
        if (ui.playlists.isEmpty()) vm.loadHighQuality()
    }

    val tags = listOf(
        "全部", "流行", "影视原声", "华语", "怀旧", "摇滚", "ACG", "欧美", "清新", "夜晚", "儿童", "民谣", "日语", "浪漫",
        "学习", "韩语", "工作", "电子", "粤语", "舞曲", "伤感", "游戏", "下午茶", "治愈", "说唱", "轻音乐"
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("探索") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        vm.search(searchQuery)
                    },
                    label = { Text("搜索...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            HapticIconButton(onClick = {
                                searchQuery = ""
                                vm.search("")
                            }) { Icon(Icons.Default.Clear, "Clear") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TabRow(selectedTabIndex = ui.selectedSearchSource.ordinal) {
                    SearchSource.entries.forEach { source ->
                        Tab(
                            selected = ui.selectedSearchSource == source,
                            onClick = {
                                if (ui.selectedSearchSource != source) {
                                    vm.setSearchSource(source)
                                    if (searchQuery.isNotEmpty()) vm.search(searchQuery)
                                }
                            },
                            text = { Text(source.displayName) }
                        )
                    }
                }
            }

            if (searchQuery.isNotEmpty()) {
                when {
                    ui.searching -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    }
                    ui.searchError != null -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(ui.searchError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    ui.searchResults.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("未找到结果") }
                    }
                    else -> {
                        LazyColumn(contentPadding = PaddingValues(top = 8.dp)) {
                            itemsIndexed(ui.searchResults) { index, song ->
                                SongRow(index + 1, song) {
                                    if (song.album == PlayerManager.BILI_SOURCE_TAG) {
                                        scope.launch {
                                            try {
                                                val info = vm.getVideoInfoByAvid(song.id)
                                                if (info.pages.size <= 1) {
                                                    onSongClick(ui.searchResults, index)
                                                } else {
                                                    partsInfo = info
                                                    clickedSongCoverUrl = song.coverUrl ?: ""
                                                    showPartsSheet = true
                                                }
                                            } catch (e: Exception) {
                                                NPLogger.e("ExploreScreen", "处理搜索结果时出错", e)
                                            }
                                        }
                                    } else {
                                        onSongClick(ui.searchResults, index)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                when (ui.selectedSearchSource) {
                    SearchSource.NETEASE -> {
                        NeteaseDefaultContent(gridState, ui, tags, vm, onPlay)
                    }
                    SearchSource.BILIBILI -> {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("在 Bilibili 中发现更多精彩视频", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
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
                                            onPlayParts(currentPartsInfo, index, clickedSongCoverUrl)
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
                                    val songs = partsInfo!!.pages
                                        .filter { selectedParts.contains(it.page) }
                                        .map { page -> vm.toSongItem(page, partsInfo!!, clickedSongCoverUrl) }

                                    scope.launch {
                                        repo.addSongsToPlaylist(pl.id, songs)
                                        showExportSheet = false
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
                        enabled = newName.isNotBlank() && selectedParts.isNotEmpty(),
                        onClick = {
                            val name = newName.trim()
                            if (name.isBlank()) return@HapticTextButton

                            val songs = partsInfo!!.pages
                                .filter { selectedParts.contains(it.page) }
                                .map { page -> vm.toSongItem(page, partsInfo!!, clickedSongCoverUrl) }

                            scope.launch {
                                repo.createPlaylist(name)
                                val target = repo.playlists.value.lastOrNull { it.name == name }
                                if (target != null) {
                                    repo.addSongsToPlaylist(target.id, songs)
                                }
                                showExportSheet = false
                                exitPartsSelection()
                            }
                        }
                    ) { Text("新建并导出") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NeteaseDefaultContent(
    gridState: LazyGridState,
    ui: moe.ouom.neriplayer.ui.viewmodel.ExploreUiState,
    tags: List<String>,
    vm: ExploreViewModel,
    onPlay: (NeteasePlaylist) -> Unit
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                val display = if (ui.expanded) tags else tags.take(12)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    display.forEach { tag ->
                        val selected = (ui.selectedTag == tag)
                        FilterChip(
                            selected = selected,
                            onClick = { if (!selected) vm.loadHighQuality(tag) },
                            label = { Text(tag) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    HapticTextButton(onClick = { vm.toggleExpanded() }) {
                        Text(if (ui.expanded) "收起" else "展开更多")
                    }
                }
            }
        }
        if (ui.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (ui.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(ui.error!!, color = MaterialTheme.colorScheme.error)
            }
        } else {
            items(items = ui.playlists, key = { it.id }) { playlist ->
                  PlaylistCard(playlist) { onPlay(playlist) }
            }
        }
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: SongItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.performHapticFeedback()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }

        if (!song.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(song.coverUrl).build(),
                contentDescription = song.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
        } else {
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
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}