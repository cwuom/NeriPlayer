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
 * File: moe.ouom.neriplayer.ui.screens/LibraryScreen
 * Created: 2025/8/8
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository.Companion.FAVORITES_NAME
import moe.ouom.neriplayer.ui.viewmodel.LibraryViewModel
import moe.ouom.neriplayer.ui.viewmodel.NeteasePlaylist
import moe.ouom.neriplayer.util.formatPlayCount

enum class LibraryTab(val label: String) {
    LOCAL("本地"),
    NETEASE("网易云"),
    QQMUSIC("QQ音乐")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onLocalPlaylistClick: (LocalPlaylist) -> Unit = {},
    onNeteasePlaylistClick: (NeteasePlaylist) -> Unit = {}
) {
    val vm: LibraryViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Pager 用于左右滑动，与 Tab 同步
    val pagerState = rememberPagerState(
        initialPage = LibraryTab.LOCAL.ordinal,
        pageCount = { LibraryTab.entries.size }
    )
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        // 大标题 AppBar
        LargeTopAppBar(
            title = { Text("媒体库") },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        // 顶部 Tabs
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                    color = MaterialTheme.colorScheme.primary
                )
            },
            divider = {}
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(tab.label) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (LibraryTab.entries[page]) {
                LibraryTab.LOCAL -> LocalPlaylistList(
                    playlists = ui.localPlaylists,
                    onCreate = { name ->
                        val finalName = name.trim().ifBlank { "新建歌单" }
                        vm.createLocalPlaylist(finalName)
                    },
                    onClick = onLocalPlaylistClick
                )
                LibraryTab.NETEASE -> NeteasePlaylistList(
                    playlists = ui.neteasePlaylists,
                    onClick = onNeteasePlaylistClick
                )
                LibraryTab.QQMUSIC -> {
                    // TODO: QQ 音乐支持
                    LazyColumn { }
                }
            }
        }
    }
}

@Composable
private fun LocalPlaylistList(
    playlists: List<LocalPlaylist>,
    onCreate: (String) -> Unit,
    onClick: (LocalPlaylist) -> Unit
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(showDialog) {
        if (showDialog) focusRequester.requestFocus()
    }

    fun tryCreate(): Boolean {
        val trimmedInput = newName.trim()
        val finalName = trimmedInput.ifBlank { "新建歌单" }

        if (finalName.equals(FAVORITES_NAME, ignoreCase = true)) {
            nameError = "该名称已保留为“$FAVORITES_NAME”，请换一个名称哦~"
            return false
        }
        if (playlists.any { it.name.equals(finalName, ignoreCase = true) }) {
            nameError = "已存在同名歌单，请换一个名称"
            return false
        }

        onCreate(finalName)
        showDialog = false
        newName = ""
        nameError = null
        return true
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clickable { showDialog = true }
            ) {
                ListItem(headlineContent = { Text("＋ 新建歌单") })
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showDialog = false
                        newName = ""
                        nameError = null
                    },
                    title = { Text("新建歌单") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = {
                                    newName = it
                                    if (nameError != null) nameError = null
                                },
                                placeholder = { Text("输入歌单名称") },
                                singleLine = true,
                                isError = nameError != null,
                                supportingText = {
                                    val err = nameError
                                    if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = { tryCreate() }
                                )
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { tryCreate() }
                        ) { Text("创建") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showDialog = false
                                newName = ""
                                nameError = null
                            }
                        ) { Text("取消") }
                    }
                )
            }
        }
        items(
            items = playlists,
            key = { it.id }
        ) { pl ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = {
                        Text("${pl.songs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingContent = {
                        val cover = pl.songs.lastOrNull()?.coverUrl
                        if (!cover.isNullOrEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun NeteasePlaylistList(
    playlists: List<NeteasePlaylist>,
    onClick: (NeteasePlaylist) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = playlists,
            key = { it.id }
        ) { pl ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = {
                        Text(
                            "${formatPlayCount(pl.playCount)} · ${pl.trackCount}首",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(pl.picUrl).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                )
            }
        }
    }
}
