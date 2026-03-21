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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.FavoritesPlaylist
import moe.ouom.neriplayer.data.LocalFilesPlaylist
import moe.ouom.neriplayer.data.LocalPlaylist
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.SystemLocalPlaylists
import moe.ouom.neriplayer.data.displayCoverUrl
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.LibraryViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatPlayCount
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

enum class LibraryTab(val labelResId: Int) {
    LOCAL(R.string.library_tab_local),
    FAVORITE(R.string.library_tab_favorite),
    YTMUSIC(R.string.library_tab_youtube_music),
    NETEASE(R.string.library_tab_netease_playlist),
    NETEASEALBUM(R.string.library_tab_netease_album),
    BILI(R.string.library_tab_bilibili),
    QQMUSIC(R.string.library_tab_qqmusic)
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
    youtubeMusicListState: LazyListState,
    biliListState: LazyListState,
    qqMusicListState: LazyListState,
    onLocalPlaylistClick: (LocalPlaylist) -> Unit = {},
    onNeteasePlaylistClick: (NeteasePlaylist) -> Unit = {},
    onNeteaseAlbumClick: (NeteaseAlbum) -> Unit = {},
    onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    onBiliPlaylistClick: (BiliPlaylist) -> Unit = {},
    onOpenRecent: () -> Unit = {}
) {
    val vm: LibraryViewModel = viewModel()
    val ui by vm.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val defaultPlaylistName = stringResource(R.string.library_create_playlist_default)

    val pagerState = rememberPagerState(
        initialPage = initialTabIndex.coerceIn(0, LibraryTab.entries.lastIndex),
        pageCount = { LibraryTab.entries.size }
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialTabIndex) {
        val targetPage = initialTabIndex.coerceIn(0, LibraryTab.entries.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != initialTabIndex) {
            onTabIndexChange(pagerState.currentPage)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshYouTubeMusicPlaylists()
    }

    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        LargeTopAppBar(
            title = { Text(stringResource(R.string.library_title)) },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            ),
            actions = {
                HapticIconButton(onClick = onOpenRecent) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = stringResource(R.string.library_recent_played)
                    )
                }
            }
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .padding(horizontal = 0.dp, vertical = 12.dp)
                .fillMaxSize()
        ) {
            Column(Modifier.fillMaxSize()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 8.dp,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    LibraryTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(stringResource(tab.labelResId)) }
                        )
                    }
                }

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
                                val finalName = name.trim().ifBlank { defaultPlaylistName }
                                vm.createLocalPlaylist(finalName)
                            },
                            onClick = onLocalPlaylistClick,
                            onRename = { playlistId, newName ->
                                vm.renameLocalPlaylist(playlistId, newName)
                            },
                            onDelete = { playlistId ->
                                vm.deleteLocalPlaylist(playlistId)
                            },
                            onReorder = { order ->
                                vm.reorderLocalPlaylists(order)
                            }
                        )

                        LibraryTab.FAVORITE -> FavoritePlaylistList(
                            listState = favoriteListState,
                            onNeteasePlaylistClick = onNeteasePlaylistClick,
                            onBiliPlaylistClick = onBiliPlaylistClick
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

                        LibraryTab.YTMUSIC -> YouTubeMusicPlaylistList(
                            playlists = ui.youtubeMusicPlaylists,
                            error = ui.youtubeMusicError,
                            listState = youtubeMusicListState,
                            onClick = onYouTubeMusicPlaylistClick,
                            onRetry = { vm.refreshYouTubeMusicPlaylists() }
                        )

                        LibraryTab.BILI -> BiliPlaylistList(
                            playlists = ui.biliPlaylists,
                            listState = biliListState,
                            onClick = onBiliPlaylistClick
                        )

                        LibraryTab.QQMUSIC -> QqMusicPlaylistList(
                            _playlists = emptyList(), // TODO: Add qqMusicPlaylists to LibraryUiState when QQ Music is implemented
                            listState = qqMusicListState,
                            _onClick = { /* TODO: Implement QQ Music playlist click */ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeMusicPlaylistList(
    playlists: List<YouTubeMusicPlaylist>,
    error: String?,
    listState: LazyListState,
    onClick: (YouTubeMusicPlaylist) -> Unit,
    onRetry: () -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var menuPlaylist by remember { mutableStateOf<YouTubeMusicPlaylist?>(null) }

    fun copyToClipboard(label: String, text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 8.dp + miniPlayerHeight
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        if (playlists.isEmpty()) {
            item {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = error ?: stringResource(R.string.library_youtube_music_empty),
                                color = if (error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.Unspecified
                                }
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.library_youtube_music_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (error != null) {
                                    HapticTextButton(onClick = onRetry) {
                                        Text(text = stringResource(R.string.action_retry))
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_youtube),
                                contentDescription = stringResource(R.string.common_youtube),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    )
                }
            }
        }
        items(
            items = playlists,
            key = { it.browseId }
        ) { playlist ->
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .combinedClickable(
                        onClick = { onClick(playlist) },
                        onLongClick = { menuPlaylist = playlist }
                    )
            ) {
                ListItem(
                    headlineContent = { Text(playlist.title) },
                    supportingContent = {
                        val trackCountText = playlist.trackCount
                            .takeIf { it > 0 }
                            ?.let { count ->
                                pluralStringResource(
                                    R.plurals.library_song_count,
                                    count,
                                    count
                                )
                            }
                        val subtitleText = playlist.subtitle.ifBlank {
                            stringResource(R.string.library_youtube_music_hint)
                        }
                        Text(
                            text = listOfNotNull(subtitleText, trackCountText)
                                .distinct()
                                .joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    leadingContent = {
                        if (playlist.coverUrl.isNotEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(playlist.coverUrl)
                                    .build(),
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

                DropdownMenu(
                    expanded = menuPlaylist?.browseId == playlist.browseId,
                    onDismissRequest = { menuPlaylist = null }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_youtube_music_open_playlist)) },
                        onClick = {
                            menuPlaylist = null
                            onClick(playlist)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_youtube_music_copy_browse_id)) },
                        onClick = {
                            copyToClipboard("ytmusic_browse_id", playlist.browseId)
                            menuPlaylist = null
                        }
                    )
                    if (playlist.playlistId.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_youtube_music_copy_playlist_id)) },
                            onClick = {
                                copyToClipboard("ytmusic_playlist_id", playlist.playlistId)
                                menuPlaylist = null
                            }
                        )
                    }
                }
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
        val cardShape = RoundedCornerShape(12.dp)
        items(
            items = playlists,
            key = { it.mediaId }
        ) { pl ->
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.title) },
                    supportingContent = {
                        Text(
                            pluralStringResource(R.plurals.library_video_count_plural, pl.count, pl.count),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    onClick: (LocalPlaylist) -> Unit,
    onRename: (Long, String) -> Unit = { _, _ -> },
    onDelete: (Long) -> Unit = {},
    onReorder: (List<Long>) -> Unit = {}
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteSelectedConfirm by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val defaultPlaylistName = context.getString(R.string.library_create_playlist_default)
    val maxNameLength = LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(initial = false)
    val reorderablePlaylists = remember { mutableStateListOf<LocalPlaylist>() }

    LaunchedEffect(showDialog) {
        if (showDialog && autoShowKeyboard) focusRequester.requestFocus()
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
        showDeleteSelectedConfirm = false
    }

    fun toggleSelection(playlistId: Long) {
        selectedIds =
            if (selectedIds.contains(playlistId)) selectedIds - playlistId else selectedIds + playlistId
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        showDeleteSelectedConfirm = true
    }

    BackHandler(enabled = selectionMode) { exitSelection() }

    LaunchedEffect(playlists) {
        val filtered = playlists.filterNot { SystemLocalPlaylists.isSystemPlaylist(it, context) }
        reorderablePlaylists.clear()
        reorderablePlaylists.addAll(filtered)
        val validIds = filtered.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
        if (selectionMode && reorderablePlaylists.isEmpty()) {
            exitSelection()
        }
    }

    fun tryCreate(): Boolean {
        val trimmedInput = newName.trim().take(maxNameLength)
        val finalName = trimmedInput.ifBlank { defaultPlaylistName }.take(maxNameLength)

        val favoritesName = context.getString(R.string.favorite_my_music)
        val localFilesName = context.getString(R.string.local_files)
        if (FavoritesPlaylist.matches(finalName, context)) {
            nameError = context.getString(R.string.library_name_reserved, favoritesName)
            return false
        }
        if (LocalFilesPlaylist.matches(finalName, context)) {
            nameError = context.getString(R.string.library_name_reserved, localFilesName)
            return false
        }
        if (playlists.any { it.name.equals(finalName, ignoreCase = true) }) {
            nameError = context.getString(R.string.library_name_exists)
            return false
        }

        onCreate(finalName)
        showDialog = false
        newName = ""
        nameError = null
        return true
    }

    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val favoritesPlaylist = playlists.firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, context) }
    val localFilesPlaylist = playlists.firstOrNull { LocalFilesPlaylist.isSystemPlaylist(it, context) }
    val reorderState = rememberReorderableLazyListState(
        listState = listState,
        onMove = { from: ItemPosition, to: ItemPosition ->
            if (!selectionMode) return@rememberReorderableLazyListState
            val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
            val toId = to.key as? Long ?: return@rememberReorderableLazyListState
            val fromIdx = reorderablePlaylists.indexOfFirst { it.id == fromId }
            val toIdx = reorderablePlaylists.indexOfFirst { it.id == toId }
            if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                reorderablePlaylists.add(toIdx, reorderablePlaylists.removeAt(fromIdx))
            }
        },
        canDragOver = { _, over ->
            selectionMode && over.key is Long
        },
        onDragEnd = { _, _ ->
            if (selectionMode) {
                onReorder(reorderablePlaylists.map { it.id })
            }
        }
    )

    LazyColumn(
        state = reorderState.listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .reorderable(reorderState)
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        if (selectionMode) {
            item(key = "local_playlist_selection_header") {
                val allSelected = selectedIds.size == reorderablePlaylists.size && reorderablePlaylists.isNotEmpty()
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.common_selected_count, selectedIds.size)) },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            HapticIconButton(onClick = { exitSelection() }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_exit_multi_select)
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HapticTextButton(
                                    onClick = {
                                        selectedIds = if (allSelected) {
                                            emptySet()
                                        } else {
                                            reorderablePlaylists.map { it.id }.toSet()
                                        }
                                    }
                                ) {
                                    Text(
                                        if (allSelected) {
                                            stringResource(R.string.action_deselect_all)
                                        } else {
                                            stringResource(R.string.action_select_all)
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                HapticTextButton(
                                    enabled = selectedIds.isNotEmpty(),
                                    onClick = { deleteSelected() }
                                ) {
                                    Text(stringResource(R.string.common_delete_selected))
                                }
                            }
                        }
                    )
                }
            }
        }
        item {
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .clickable(enabled = !selectionMode) { showDialog = true }
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_create_new)) },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showDialog = false
                        newName = ""
                        nameError = null
                    },
                    title = { Text(stringResource(R.string.playlist_create)) },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = {
                                    newName = it.take(maxNameLength)
                                    if (nameError != null) nameError = null
                                },
                                placeholder = { Text(stringResource(R.string.playlist_enter_name)) },
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
                        ) { Text(stringResource(R.string.action_create)) }
                    },
                    dismissButton = {
                        HapticTextButton(
                            onClick = {
                                showDialog = false
                                newName = ""
                                nameError = null
                            }
                        ) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }

            if (showDeleteSelectedConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteSelectedConfirm = false },
                    title = { Text(stringResource(R.string.dialog_confirm_delete)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.library_delete_selected_confirm,
                                selectedIds.size
                            )
                        )
                    },
                    confirmButton = {
                        HapticTextButton(
                            onClick = {
                                selectedIds.forEach { onDelete(it) }
                                exitSelection()
                            }
                        ) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = {
                        HapticTextButton(
                            onClick = { showDeleteSelectedConfirm = false }
                        ) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }
        }

        favoritesPlaylist?.let { system ->
            item(key = "local_playlist_favorites") {
                val displayName = SystemLocalPlaylists.resolve(system.id, system.name, context)?.currentName ?: system.name
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                        .combinedClickable(
                            onClick = {
                                if (!selectionMode) onClick(system)
                            }
                        )
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.library_song_count, system.songs.size, system.songs.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) {
                                    Spacer(modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                val cover = system.displayCoverUrl(context)
                                if (!cover.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = offlineCachedImageRequest(context, cover),
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
                        }
                    )
                }
            }
        }

        items(
            items = reorderablePlaylists,
            key = { it.id }
        ) { pl ->
            ReorderableItem(state = reorderState, key = pl.id) { isDragging ->
                val systemPlaylist = SystemLocalPlaylists.resolve(pl.id, pl.name, context)
                val displayName = systemPlaylist?.currentName ?: pl.name
                val isSystemPlaylist = systemPlaylist != null
                val isSelected = selectionMode && selectedIds.contains(pl.id)
                val rowContainerColor = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                }

                var showMenu by remember { mutableStateOf(false) }
                var showRenameDialog by remember { mutableStateOf(false) }
                var showDeleteDialog by remember { mutableStateOf(false) }
                var renameText by remember { mutableStateOf(pl.name.take(maxNameLength)) }

                if (selectionMode && showMenu) showMenu = false

                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = rowContainerColor
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .animateItem()
                        .clip(cardShape)
                        .combinedClickable(
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(pl.id)
                                } else {
                                    onClick(pl)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode && !isSystemPlaylist) {
                                    selectionMode = true
                                    selectedIds = setOf(pl.id)
                                }
                            }
                        )
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.library_song_count, pl.songs.size, pl.songs.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            if (!isSystemPlaylist) toggleSelection(pl.id)
                                        },
                                        enabled = !isSystemPlaylist
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                val cover = pl.displayCoverUrl(context)
                                if (!cover.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = offlineCachedImageRequest(context, cover),
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
                        },
                        trailingContent = {
                            if (selectionMode && !isSystemPlaylist) {
                                Box(
                                    modifier = Modifier
                                        .detectReorder(reorderState)
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = stringResource(R.string.common_drag_handle),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else if (!selectionMode && !isSystemPlaylist) {
                                Box {
                                    HapticIconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.MoreVert,
                                            contentDescription = stringResource(R.string.common_more_options)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_rename)) },
                                            onClick = {
                                                showMenu = false
                                                renameText = pl.name.take(maxNameLength)
                                                showRenameDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_delete)) },
                                            onClick = {
                                                showMenu = false
                                                showDeleteDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                if (showRenameDialog) {
                    AlertDialog(
                        onDismissRequest = { showRenameDialog = false },
                        title = { Text(stringResource(R.string.action_rename)) },
                        text = {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it.take(maxNameLength) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            HapticTextButton(
                                onClick = {
                                    val trimmed = renameText.trim().take(maxNameLength)
                                    if (trimmed.isNotBlank()) {
                                        onRename(pl.id, trimmed)
                                        showRenameDialog = false
                                    }
                                }
                            ) { Text(stringResource(R.string.action_confirm)) }
                        },
                        dismissButton = {
                            HapticTextButton(
                                onClick = { showRenameDialog = false }
                            ) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text(stringResource(R.string.action_delete)) },
                        text = {
                            Text(stringResource(R.string.library_delete_playlist_confirm, displayName))
                        },
                        confirmButton = {
                            HapticTextButton(
                                onClick = {
                                    onDelete(pl.id)
                                    showDeleteDialog = false
                                }
                            ) { Text(stringResource(R.string.action_delete)) }
                        },
                        dismissButton = {
                            HapticTextButton(
                                onClick = { showDeleteDialog = false }
                            ) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }
            }
        }

        localFilesPlaylist?.let { system ->
            item(key = "local_playlist_local_files") {
                val displayName = SystemLocalPlaylists.resolve(system.id, system.name, context)?.currentName ?: system.name
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                        .combinedClickable(
                            onClick = {
                                if (!selectionMode) onClick(system)
                            }
                        )
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.library_song_count, system.songs.size, system.songs.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectionMode) {
                                    Spacer(modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                val cover = system.displayCoverUrl(context)
                                if (!cover.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = offlineCachedImageRequest(context, cover),
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
                        }
                    )
                }
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
    val context = LocalContext.current
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        items(
            items = playlists,
            key = { it.id }
        ) { pl ->
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.home_play_count_format,
                                formatPlayCount(context, pl.playCount),
                                pl.trackCount
                            ),
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
        val cardShape = RoundedCornerShape(12.dp)
        items(
            items = playlists,
            key = { it.id }
        ) { pl ->
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .clickable { onClick(pl) }
            ) {
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = {
                        Text(
                            pluralStringResource(R.plurals.library_song_count, pl.size, pl.size),
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
    onNeteasePlaylistClick: (NeteasePlaylist) -> Unit,
    onBiliPlaylistClick: (BiliPlaylist) -> Unit
) {
    val context = LocalContext.current
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val scope = rememberCoroutineScope()
    var sortMode by rememberSaveable { mutableStateOf(false) }
    val reorderableFavorites = remember { mutableStateListOf<moe.ouom.neriplayer.data.FavoritePlaylist>() }

    BackHandler(enabled = sortMode) { sortMode = false }

    LaunchedEffect(favorites) {
        reorderableFavorites.clear()
        reorderableFavorites.addAll(favorites)
        if (sortMode && favorites.isEmpty()) {
            sortMode = false
        }
    }

    val reorderState = rememberReorderableLazyListState(
        listState = listState,
        onMove = { from: ItemPosition, to: ItemPosition ->
            if (!sortMode) return@rememberReorderableLazyListState
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState
            val fromIndex = reorderableFavorites.indexOfFirst { "${it.source}:${it.id}" == fromKey }
            val toIndex = reorderableFavorites.indexOfFirst { "${it.source}:${it.id}" == toKey }
            if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                reorderableFavorites.add(toIndex, reorderableFavorites.removeAt(fromIndex))
            }
        },
        canDragOver = { _, over -> sortMode && over.key is String },
        onDragEnd = { _, _ ->
            if (sortMode) {
                scope.launch {
                    favoriteRepo.reorderFavorites(
                        reorderableFavorites.map { "${it.source}:${it.id}" }
                    )
                }
            }
        }
    )

    LazyColumn(
        state = reorderState.listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .reorderable(reorderState)
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        if (sortMode) {
            item(key = "favorite_sort_mode_header") {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.library_favorite_sort_mode_title)) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.library_favorite_sort_mode_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            HapticIconButton(onClick = { sortMode = false }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_cancel)
                                )
                            }
                        }
                    )
                }
            }
        }
        if (favorites.isEmpty()) {
            item {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.playlist_no_favorite)) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.playlist_favorite_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                items = reorderableFavorites,
                key = { "${it.source}:${it.id}" }
            ) { favorite ->
                ReorderableItem(state = reorderState, key = "${favorite.source}:${favorite.id}") {
                    Card(
                        shape = cardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = if (sortMode) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            }
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .animateItem()
                            .clip(cardShape)
                            .combinedClickable(
                                onClick = {
                                    if (sortMode) return@combinedClickable
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
                                        "bili" -> {
                                            onBiliPlaylistClick(
                                                BiliPlaylist(
                                                    mediaId = favorite.id,
                                                    fid = 0L,
                                                    mid = 0L,
                                                    title = favorite.name,
                                                    count = favorite.trackCount,
                                                    coverUrl = favorite.coverUrl.orEmpty()
                                                )
                                            )
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!sortMode) {
                                        sortMode = true
                                    }
                                }
                            )
                    ) {
                        ListItem(
                            headlineContent = { Text(favorite.name) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.library_favorite_source_format,
                                        favorite.trackCount,
                                        favorite.source
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            leadingContent = {
                                if (!favorite.coverUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = offlineCachedImageRequest(context, favorite.coverUrl),
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
                            },
                            trailingContent = {
                                if (sortMode) {
                                    Box(
                                        modifier = Modifier
                                            .detectReorder(reorderState)
                                            .padding(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DragHandle,
                                            contentDescription = stringResource(R.string.common_drag_handle),
                                            modifier = Modifier.size(24.dp)
                                        )
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

@Composable
private fun QqMusicPlaylistList(
    _playlists: List<Any>, // TODO: Replace with proper QQ Music playlist type
    listState: LazyListState,
    _onClick: (Any) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        // TODO: Implement QQ Music playlist list when type is available
        item {
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(cardShape)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_qqmusic_coming)) },
                    supportingContent = {
                        Text(stringResource(R.string.library_coming_soon), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
