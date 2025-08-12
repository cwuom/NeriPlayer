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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.LocalPlaylist
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
                    onCreate = { vm.createLocalPlaylist("新建歌单") }
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
    onCreate: () -> Unit
) {
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
                    .clickable { onCreate() }
            ) {
                ListItem(headlineContent = { Text("＋ 新建歌单") })
            }
        }
        items(playlists) { pl ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
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