package moe.ouom.neriplayer.ui.screen.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.ui.screen.playlist/NeteaseCloudScreen
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistLoadErrorDialog
import moe.ouom.neriplayer.ui.component.playlist.PlaylistSelectionActions
import moe.ouom.neriplayer.ui.component.playlist.playlistLoadErrorText
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.navigation.rememberMainTabDetailVisibilityState
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailFrame
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailHeader
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailSearch
import moe.ouom.neriplayer.ui.viewmodel.playlist.NeteaseCloudViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.canQueueNeteaseCloudTracks
import moe.ouom.neriplayer.util.search.playlistSearchValues
import kotlin.random.Random

/**
 * 云盘封面取最新上传歌曲的封面。
 * 云盘接口按上传时间倒序返回，因此取第一首带封面的歌；全部没有封面时回退到默认视觉。
 */
internal fun resolveNeteaseCloudCoverUrl(tracks: List<SongItem>): String? =
    tracks.firstNotNullOfOrNull { song ->
        song.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
    }

/** 云盘全选使用的歌曲 ID 集合。 */
internal fun neteaseCloudSongIds(tracks: List<SongItem>): Set<Long> =
    tracks.mapTo(mutableSetOf()) { it.id }

/** 按云盘列表顺序取出被选中的歌曲，保证导出到本地歌单后的顺序与页面一致。 */
internal fun selectNeteaseCloudSongs(
    tracks: List<SongItem>,
    selectedIds: Set<Long>
): List<SongItem> = tracks.filter { it.id in selectedIds }

/**
 * 云盘播放/导出只接受完整加载的列表：前缀列表入队或导出都会得到残缺歌单。
 */
internal fun canUseNeteaseCloudTracks(hasTracks: Boolean, incomplete: Boolean): Boolean =
    canQueueNeteaseCloudTracks(hasTracks = hasTracks, incomplete = incomplete)

/**
 * 网易云云盘歌曲页。
 *
 * 云盘不是普通歌单，但在展示和操作上对齐网易云歌单详情：
 * 头部封面取最新上传歌曲的封面，提供顺序播放、随机播放、循环模式、搜索、
 * 多选下载与导出到本地歌单。
 *
 * @param onBack 返回回调
 * @param onSongClick 播放回调，参数为已加载完成的云盘列表与起始下标
 * @param offlineMode 是否处于脱机模式
 */
@Composable
fun NeteaseCloudScreen(
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val vm: NeteaseCloudViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                NeteaseCloudViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsState()
    val pageOwner = remember { Any() }
    DisposableEffect(vm, pageOwner) {
        vm.start(pageOwner, offlineMode)
        onDispose { vm.stop(pageOwner) }
    }
    LaunchedEffect(vm, pageOwner, offlineMode) {
        vm.updateOfflineMode(pageOwner, offlineMode)
    }

    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
    val repeatMode by PlayerManager.repeatModeFlow.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val playlists by repo.playlists.collectAsState()
    val favoriteSongs = remember(playlists, context) {
        FavoritesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty()
    }
    val favoriteLookup = remember(favoriteSongs) { FavoriteSongLookup(favoriteSongs) }
    val favoriteAddedText = stringResource(CoreCommonR.string.favorite_added)
    val favoriteRemovedText = stringResource(CoreCommonR.string.favorite_removed)
    fun toggleSongFavorite(song: SongItem, isFavorite: Boolean) {
        val message = if (isFavorite) favoriteRemovedText else favoriteAddedText
        scope.launchLocalPlaylistMutation(
            operation = "toggleNeteaseCloudSongFavorite",
            onResult = { result ->
                if (result.isSuccess) {
                    scope.launch { snackbarHostState.showNeriSnackbar(message) }
                }
            }
        ) {
            if (isFavorite) {
                repo.removeFromFavorites(song)
            } else {
                repo.addToFavorites(song)
            }
        }
    }

    // 搜索：与普通歌单详情共用头部/吸顶搜索框，按歌曲元数据本地过滤
    val searchUiState = rememberPlaylistSearchUiState()
    val searchQuery = searchUiState.query.value
    val searchVisible = searchUiState.visible.value
    val searchInputState = rememberPlaylistSearchInputState(
        query = searchQuery,
        onQueryChange = { searchUiState.query.value = it }
    )
    val searchContext = rememberPlaylistSearchContext()
    val searchFocusRequester = remember { FocusRequester() }
    var headerSearchFocused by remember { mutableStateOf(false) }
    var dockedSearchFocused by remember { mutableStateOf(false) }

    // 多选与导出到本地歌单
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showExportAllSheet by remember { mutableStateOf(false) }
    var showDownloadManager by remember { mutableStateOf(false) }
    val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val hasDownloadManagerEntry = downloadTaskSummary.hasDownloadManagerEntry
    fun toggleSelect(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }
    // 翻页/刷新/清空后同步选中集合：清掉已不在列表里的 id，列表为空时退出多选
    // （放在分支外，刷新返回空列表时也能生效）
    LaunchedEffect(ui.tracks) {
        val availableIds = ui.tracks.mapTo(mutableSetOf()) { it.id }
        selectedIds = selectedIds.intersect(availableIds)
        if (availableIds.isEmpty()) selectionMode = false
    }

    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(
        initial = false
    )
    val backgroundImageUri by AppContainer.settingsRepo.backgroundImageUriFlow.collectAsState(
        initial = null
    )

    val error = ui.error
    val errorText = if (error != null) playlistLoadErrorText(error).asString() else null
    LaunchedEffect(errorText, ui.tracks.isNotEmpty()) {
        if (errorText != null && ui.tracks.isNotEmpty()) {
            snackbarHostState.showNeriSnackbar(errorText)
        }
    }
    if (error != null && ui.tracks.isEmpty() && !ui.loginRequired) {
        PlaylistLoadErrorDialog(
            message = error,
            onRetry = { vm.retry(pageOwner) },
            onBack = onBack
        )
        return
    }

    val title = stringResource(CoreCommonR.string.netease_cloud_title)
    val summary = if (ui.maxBytes > 0L) {
        stringResource(
            CoreCommonR.string.netease_cloud_summary,
            ui.totalCount,
            formatFileSize(ui.usedBytes),
            formatFileSize(ui.maxBytes)
        )
    } else {
        pluralStringResource(
            AppR.plurals.library_song_count,
            ui.totalCount,
            ui.totalCount
        )
    }

    val detailVisibilityState = rememberMainTabDetailVisibilityState("netease_cloud")

    AnimatedVisibility(
        visibleState = detailVisibilityState,
        enter = fadeIn() + slideInVertically { it / 6 },
        exit = fadeOut() + slideOutVertically { it / 6 }
    ) {
        when {
            ui.loginRequired -> NeteaseCloudStatusShell(
                title = title,
                onBack = onBack,
                onRefresh = { vm.refresh(pageOwner) },
                refreshEnabled = false
            ) {
                NeteaseCloudMessage(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(CoreCommonR.string.netease_cloud_login_required),
                    hint = stringResource(CoreCommonR.string.netease_cloud_login_hint),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            ui.loading && ui.tracks.isEmpty() -> NeteaseCloudStatusShell(
                title = title,
                onBack = onBack,
                onRefresh = { vm.refresh(pageOwner) },
                refreshEnabled = false
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            ui.tracks.isEmpty() -> NeteaseCloudStatusShell(
                title = title,
                onBack = onBack,
                onRefresh = { vm.refresh(pageOwner) },
                refreshEnabled = !ui.loading && !ui.refreshing && !ui.loadingMore
            ) {
                NeteaseCloudMessage(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(CoreCommonR.string.netease_cloud_empty),
                    hint = stringResource(CoreCommonR.string.netease_cloud_empty_hint),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            else -> {
                val tracks = ui.tracks
                val canUseTracks = canUseNeteaseCloudTracks(
                    hasTracks = tracks.isNotEmpty(),
                    incomplete = ui.incomplete
                )
                val displayedTracks = rememberPlaylistSearchResults(
                    query = searchQuery,
                    items = tracks,
                    tokens = { song -> song.playlistSearchValues(searchContext) },
                    buildIndex = shouldBuildPlaylistSearchIndex(searchVisible, searchQuery)
                )
                val listState = rememberSaveable(saver = LazyListState.Saver) {
                    LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
                }
                val coverUrl = resolveNeteaseCloudCoverUrl(tracks)
                val trackIndices = remember(tracks) {
                    tracks.mapIndexed { index, song -> song.stableKey() to index }.toMap()
                }
                val playingIndex = displayedTracks.indexOfFirst { song ->
                    currentSong?.sameIdentityAs(song) == true
                }
                val selectedSongs = remember(tracks, selectedIds) {
                    selectNeteaseCloudSongs(tracks, selectedIds)
                }
                val allSelected = tracks.isNotEmpty() && selectedIds.size == tracks.size

                fun play(shuffle: Boolean) {
                    if (!canUseTracks) return
                    val startIndex = resolvePlaylistPlaybackStartIndex(
                        songCount = tracks.size,
                        shuffleEnabled = shuffle,
                        randomIndex = if (tracks.isEmpty()) 0 else Random.nextInt(tracks.size)
                    )
                    if (startIndex < 0) return
                    PlayerManager.setShuffle(shuffle)
                    onSongClick(tracks, startIndex)
                }

                PlaylistModernDetailFrame(
                    header = PlaylistModernDetailHeader(
                        key = "netease_cloud",
                        headerKey = "netease_cloud_header",
                        title = title,
                        coverUrl = coverUrl,
                        subtitle = summary,
                        offlineMode = offlineMode,
                        hasCustomBackground = backgroundImageUri != null
                    ),
                    search = PlaylistModernDetailSearch(
                        visible = searchVisible,
                        onVisibleChange = { searchUiState.visible.value = it },
                        query = searchQuery,
                        onQueryChange = { searchUiState.query.value = it },
                        inputState = searchInputState,
                        focusRequester = searchFocusRequester,
                        autoShowKeyboard = autoShowKeyboard,
                        headerFocused = headerSearchFocused,
                        onHeaderFocusChanged = { headerSearchFocused = it },
                        dockedFocused = dockedSearchFocused,
                        onDockedFocusChanged = { dockedSearchFocused = it }
                    ),
                    listState = listState,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    selectionCount = if (selectionMode) selectedIds.size else null,
                    onExitSelection = { exitSelection() },
                    onLocatePlaying = if (playingIndex >= 0) {
                        {
                            scope.launch {
                                listState.animateScrollToItem(
                                    resolvePlaylistSongItemIndex(playingIndex)
                                )
                            }
                        }
                    } else {
                        null
                    },
                    searchPlaceholder = stringResource(
                        CoreCommonR.string.netease_cloud_search_hint
                    ),
                    normalActions = { contentColor ->
                        if (hasDownloadManagerEntry) {
                            HapticIconButton(onClick = { showDownloadManager = true }) {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = stringResource(
                                        CoreCommonR.string.cd_download_manager
                                    ),
                                    tint = contentColor
                                )
                            }
                        }
                        HapticIconButton(
                            onClick = { vm.refresh(pageOwner) },
                            enabled = !ui.loading && !ui.refreshing && !ui.loadingMore
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(
                                    CoreCommonR.string.action_refresh
                                ),
                                tint = contentColor
                            )
                        }
                    },
                    selectionActions = {
                        HapticIconButton(
                            onClick = {
                                if (selectedSongs.isNotEmpty()) {
                                    showDownloadManager = true
                                    GlobalDownloadManager.startBatchDownload(
                                        context,
                                        selectedSongs
                                    )
                                    exitSelection()
                                }
                            },
                            enabled = selectedSongs.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = stringResource(
                                    CoreCommonR.string.cd_download_selected
                                )
                            )
                        }
                        PlaylistSelectionActions(
                            allSelected = allSelected,
                            onToggleSelectAll = {
                                selectedIds = if (allSelected) {
                                    emptySet()
                                } else {
                                    neteaseCloudSongIds(tracks)
                                }
                            },
                            canExport = selectedSongs.isNotEmpty(),
                            onExport = { showExportSheet = true }
                        )
                    },
                    beforeList = { _ ->
                        if (ui.refreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    },
                    playbackActions = {
                        PlaylistModernPlaybackActions(
                            songCount = if (canUseTracks) tracks.size else 0,
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            onPlayInOrder = { play(shuffle = false) },
                            onShufflePlay = { play(shuffle = true) },
                            onToggleShuffle = {
                                PlayerManager.setShuffle(!shuffleEnabled)
                            },
                            onCycleRepeatMode = {
                                PlayerManager.cycleRepeatMode()
                            },
                            onExportToLocalPlaylist = { showExportAllSheet = true }
                        )
                    },
                    content = {
                        itemsIndexed(
                            items = displayedTracks,
                            key = { _, song -> song.stableKey() },
                            contentType = { _, _ -> "netease_cloud_song" }
                        ) { index, song ->
                            SongRow(
                                index = index + 1,
                                song = song,
                                isCurrentSong = currentSong?.sameIdentityAs(song) == true,
                                animatePlayingIndicator = isPlaying,
                                isFavorite = favoriteLookup.contains(song),
                                onFavoriteToggle = ::toggleSongFavorite,
                                showCover = true,
                                selectionMode = selectionMode,
                                selected = selectedIds.contains(song.id),
                                onToggleSelect = { toggleSelect(song.id) },
                                onLongPress = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedIds = setOf(song.id)
                                    } else {
                                        toggleSelect(song.id)
                                    }
                                },
                                onClick = {
                                    // 列表未完整加载时 tracks 只是前缀，先不入队避免队列被截断
                                    if (canUseTracks) {
                                        trackIndices[song.stableKey()]?.let { position ->
                                            onSongClick(tracks, position)
                                        }
                                    }
                                },
                                onPlayNext = { PlayerManager.addToQueueNext(song) },
                                onAddToQueueEnd = {
                                    PlayerManager.addToQueueEnd(song)
                                },
                                snackbarHostState = snackbarHostState,
                                offlineMode = offlineMode
                            )
                        }
                        if (displayedTracks.isEmpty() && searchQuery.isNotBlank()) {
                            item(key = "netease_cloud_search_empty") {
                                Text(
                                    text = stringResource(
                                        CoreCommonR.string.netease_cloud_search_empty
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        horizontal = 20.dp,
                                        vertical = 24.dp
                                    )
                                )
                            }
                        }
                        if (ui.loadingMore) {
                            item(key = "netease_cloud_loading_more") {
                                NeteaseCloudLoadingMoreRow()
                            }
                        } else if (!canUseTracks && !ui.loading && !ui.refreshing) {
                            item(key = "netease_cloud_incomplete_hint") {
                                Text(
                                    text = stringResource(
                                        CoreCommonR.string.netease_cloud_incomplete_hint
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(
                                        horizontal = 20.dp,
                                        vertical = 12.dp
                                    )
                                )
                            }
                        }
                    },
                    overlays = {
                        BackHandler(enabled = selectionMode) { exitSelection() }
                        if (showExportSheet) {
                            NeteaseCloudExportSheet(
                                songs = selectedSongs,
                                playlists = playlists,
                                repository = repo,
                                snackbarHostState = snackbarHostState,
                                scope = scope,
                                canWrite = { true },
                                onDismiss = { showExportSheet = false }
                            )
                        }
                        if (showExportAllSheet) {
                            NeteaseCloudExportSheet(
                                songs = tracks,
                                playlists = playlists,
                                repository = repo,
                                snackbarHostState = snackbarHostState,
                                scope = scope,
                                // 面板打开后刷新可能把列表退回到前缀状态，确认时必须再校验一次
                                canWrite = { canUseTracks },
                                onDismiss = { showExportAllSheet = false }
                            )
                        }
                        if (showDownloadManager) {
                            val downloadTasks by GlobalDownloadManager
                                .downloadTasks
                                .collectAsState()
                            BatchDownloadManagerSheet(
                                downloadTasks = downloadTasks,
                                onDismiss = { showDownloadManager = false }
                            )
                        }
                    }
                )
            }
        }
    }
}

/** 云盘在登录、加载、空列表时使用的普通页面外壳，与普通歌单详情共用同一套顶栏样式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeteaseCloudStatusShell(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        HapticIconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreCommonR.string.cd_back)
                            )
                        }
                    },
                    actions = {
                        HapticIconButton(
                            onClick = onRefresh,
                            enabled = refreshEnabled
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(
                                    CoreCommonR.string.action_refresh
                                )
                            )
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
                content()
            }
        }
    }
}

/** 登录、空列表等居中提示。 */
@Composable
private fun NeteaseCloudMessage(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 翻页加载中的底部提示行。 */
@Composable
private fun NeteaseCloudLoadingMoreRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(CoreCommonR.string.netease_cloud_loading_more),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 云盘歌曲导出面板：全量导出与多选导出复用同一套本地歌单选择与反馈。 */
@Composable
private fun NeteaseCloudExportSheet(
    songs: List<SongItem>,
    playlists: List<LocalPlaylist>,
    repository: LocalPlaylistRepository,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    canWrite: () -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 导出必须跑在页面作用域上：面板隐藏动画结束会销毁面板自身的 composition/scope
    fun rejectIncomplete() {
        onDismiss()
        scope.launch {
            snackbarHostState.showNeriSnackbar(
                context.getString(CoreCommonR.string.netease_cloud_incomplete_hint)
            )
        }
    }
    PlaylistExportSheet(
        title = stringResource(CoreCommonR.string.playlist_export_to_local),
        playlists = playlists.filterNot { playlist ->
            LocalFilesPlaylist.isSystemPlaylist(playlist, context)
        },
        selectedCount = songs.size,
        onDismissRequest = onDismiss,
        onCreateAndExport = { name ->
            if (!canWrite()) {
                rejectIncomplete()
            } else {
                scope.launchLocalPlaylistMutation(
                    operation = "createPlaylistFromNeteaseCloud",
                    onResult = { result ->
                        scope.showPlaylistBatchExportCreatedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = repository,
                            result = result
                        )
                    }
                ) {
                    repository.createPlaylistWithSongs(name, songs)
                }
            }
        },
        onExportToPlaylist = { playlist ->
            if (!canWrite()) {
                rejectIncomplete()
            } else {
                scope.launchLocalPlaylistMutation(
                    operation = "exportSongsFromNeteaseCloud",
                    onResult = { result ->
                        scope.showPlaylistBatchExportAddedResult(
                            context = context,
                            snackbarHostState = snackbarHostState,
                            repository = repository,
                            targetPlaylistId = playlist.id,
                            targetPlaylistName = playlist.name,
                            result = result
                        )
                    }
                ) {
                    repository.addSongsToPlaylistWithResult(playlist.id, songs)
                }
            }
        }
    )
}