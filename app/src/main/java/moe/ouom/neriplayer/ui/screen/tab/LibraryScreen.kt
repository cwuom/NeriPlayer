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
 * File: moe.ouom.neriplayer.ui.screen.tab/LibraryScreen
 * Created: 2025/8/8
 */

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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
import moe.ouom.neriplayer.data.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.LocalPlaylistRepository.Companion.FAVORITES_NAME
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.LibraryViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatPlayCount
import androidx.compose.material.icons.outlined.History
import moe.ouom.neriplayer.util.HapticIconButton

enum class LibraryTab(val label: String) {
    LOCAL("本地"),
    FAVORITE("收藏"),
    NETEASE("网易云 - 歌单"),
    NETEASEALBUM("网易云 - 专辑"),
    BILI("哔哩哔哩"),
    QQMUSIC("QQ音乐")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    initialTabIndex: Int = 0,
    onTabIndexChange: (Int) -> Unit = {},
    localListState: LazyListState,
    favoriteListState: LazyListState,
    neteaseAlbumState: LazyListState,
    neteaseListState: LazyListState,
    biliListState: LazyListState,
    qqMusicListState: LazyListState,
    onLocalPlaylistClick: (LocalPlaylist) -> Unit = {},
    onNeteasePlaylistClick: (NeteasePlaylist) -> Unit = {},
    onNeteaseAlbumClick: (NeteaseAlbum) -> Unit = {},
    onBiliPlaylistClick: (BiliPlaylist) -> Unit = {},
    onOpenRecent: () -> Unit = {}
) {
    val vm: LibraryViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 使用rememberSaveable来保存当前选中的标签页索引
    var currentTabIndex by rememberSaveable { mutableStateOf(initialTabIndex) }
    
    // 使用rememberPagerState来管理页面状态，支持左右滑动
    val pagerState = rememberPagerState(
        initialPage = currentTabIndex,
        pageCount = { LibraryTab.entries.size }
    )
    val scope = rememberCoroutineScope()

    // 当pagerState改变时，同步更新currentTabIndex并通知父组件
    LaunchedEffect(pagerState.currentPage) {
        currentTabIndex = pagerState.currentPage
        onTabIndexChange(pagerState.currentPage)
    }

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
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            ),
            actions = {
                HapticIconButton(onClick = onOpenRecent) {
                    Icon(Icons.Outlined.History, contentDescription = "最近播放")
                }
            }
        )

        // 顶部 Tabs
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        // 点击Tab时，使用协程来平滑滚动到对应页面
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(tab.label) }
                )
            }
        }

        // 使用HorizontalPager来支持左右滑动切换标签页
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp
        ) { page ->
            when (LibraryTab.entries[page]) {
                LibraryTab.LOCAL -> LocalPlaylistList(
                    playlists = ui.localPlaylists,
                    listState = localListState,
                    onCreate = { name ->
                        val finalName = name.trim().ifBlank { "新建歌单" }
                        vm.createLocalPlaylist(finalName)
                    },
                    onClick = onLocalPlaylistClick
                )
                LibraryTab.FAVORITE -> FavoritePlaylistList(
                    listState = favoriteListState,
                    onNeteasePlaylistClick = onNeteasePlaylistClick
                )
                LibraryTab.NETEASE -> NeteasePlaylistList(
                    playlists = ui.neteasePlaylists,
                    listState = neteaseListState,
                    onClick = onNeteasePlaylistClick
                )
                LibraryTab.NETEASEALBUM -> NeteaseAlbumList(
                    playlists = ui.neteaseAlbums,
                    listState = neteaseAlbumState,
                    onClick = onNeteaseAlbumClick
                )
                LibraryTab.BILI -> BiliPlaylistList(
                    playlists = ui.biliPlaylists,
                    listState = biliListState,
                    onClick = onBiliPlaylistClick
                )
                LibraryTab.QQMUSIC -> QqMusicPlaylistList(
                    playlists = emptyList(), // TODO: Add qqMusicPlaylists to LibraryUiState when QQ Music is implemented
                    listState = qqMusicListState,
                    onClick = { /* TODO: Implement QQ Music playlist click */ }
                )
            }
        }
    }
}

@Composable
private fun BiliPlaylistList(
    playlists: List<BiliPlaylist>,
    listState: LazyListState,
    onClick: (BiliPlaylist) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = playlists,
            key = { it.mediaId }
        ) { pl ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.title) },
                    supportingContent = {
                        Text("${pl.count} 个视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    leadingContent = {
                        if (pl.coverUrl.isNotEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(pl.coverUrl).build(),
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
private fun LocalPlaylistList(
    playlists: List<LocalPlaylist>,
    listState: LazyListState,
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
            nameError = "该名称已保留为\"$FAVORITES_NAME\"，请换一个名称哦~"
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
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clickable { showDialog = true }
            ) {
                ListItem(headlineContent = { Text("＋ 新建歌单") },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                ),)
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
                        HapticTextButton(
                            onClick = { tryCreate() }
                        ) { Text("创建") }
                    },
                    dismissButton = {
                        HapticTextButton(
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
            val isListEmpty = pl.songs.isEmpty()
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clickable(enabled = !isListEmpty) { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = {
                        Text("${pl.songs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
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
    listState: LazyListState,
    onClick: (NeteasePlaylist) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
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
                    containerColor = Color.Transparent
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
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
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

@Composable
private fun NeteaseAlbumList(
    playlists: List<NeteaseAlbum>,
    listState: LazyListState,
    onClick: (NeteaseAlbum) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
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
                    containerColor = Color.Transparent
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
                            "${pl.size}首",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
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

@Composable
private fun FavoritePlaylistList(
    listState: LazyListState,
    onNeteasePlaylistClick: (NeteasePlaylist) -> Unit
) {
    val context = LocalContext.current
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (favorites.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    ListItem(
                        headlineContent = { Text("暂无收藏的歌单") },
                        supportingContent = {
                            Text("在歌单详情页点击收藏按钮，或长按首页推荐歌单收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    )
                }
            }
        } else {
            items(
                items = favorites,
                key = { "${it.source}:${it.id}" }
            ) { favorite ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .animateItem()
                        .clickable {
                            when (favorite.source) {
                                "netease" -> {
                                    onNeteasePlaylistClick(
                                        NeteasePlaylist(
                                            id = favorite.id,
                                            name = favorite.name,
                                            picUrl = favorite.coverUrl ?: "",
                                            playCount = 0,
                                            trackCount = favorite.trackCount
                                        )
                                    )
                                }
                            }
                        }
                ) {
                    ListItem(
                        headlineContent = { Text(favorite.name) },
                        supportingContent = {
                            Text("${favorite.trackCount}首 · ${favorite.source}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            if (!favorite.coverUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(favorite.coverUrl).build(),
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
}

@Composable
private fun QqMusicPlaylistList(
    playlists: List<Any>, // TODO: Replace with proper QQ Music playlist type
    listState: LazyListState,
    onClick: (Any) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // TODO: Implement QQ Music playlist list when type is available
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ListItem(
                    headlineContent = { Text("QQ音乐功能开发中...") },
                    supportingContent = {
                        Text("敬请期待", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                )
            }
        }
    }
}