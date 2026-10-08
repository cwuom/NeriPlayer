package moe.ouom.neriplayer.ui.screen.download

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
 * File: moe.ouom.neriplayer.ui.screen.download/DownloadManagerScreen
 * Updated: 2026/3/23
 */


import android.app.Application
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteProgress
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteResult
import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.ui.component.download.DownloadedSongDeleteProgressCard
import moe.ouom.neriplayer.ui.component.download.isDownloadedSongDeletionRunning
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.viewmodel.DownloadManagerViewModel
import moe.ouom.neriplayer.util.format.formatDate
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import java.io.File

@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit,
    onOpenDownloadProgress: () -> Unit,
    listState: LazyListState,
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: DownloadManagerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                DownloadManagerViewModel(app)
            }
        }
    )
    val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()
    val legacyPreviewClips by viewModel.legacyPreviewClips.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val deleteProgress by viewModel.downloadedSongDeleteProgress.collectAsStateWithLifecycle()
    val deleteFailureDismissed by
        viewModel.downloadedSongDeleteFailureDismissed.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (viewModel.downloadedSongs.value.isEmpty() && !viewModel.isRefreshing.value) {
            viewModel.refreshDownloadedSongs()
        }
    }

    DownloadManagerContent(
        downloadedSongs = downloadedSongs,
        legacyPreviewClips = legacyPreviewClips,
        isRefreshing = isRefreshing,
        deleteProgress = deleteProgress,
        deleteFailureDismissed = deleteFailureDismissed,
        listState = listState,
        offlineMode = offlineMode,
        onBack = onBack,
        onOpenDownloadProgress = onOpenDownloadProgress,
        onRefresh = { viewModel.refreshDownloadedSongs(forceRefresh = true) },
        onDismissDeleteFailure = viewModel::dismissDownloadedSongDeleteFailure,
        onDeleteSongs = viewModel::deleteDownloadedSongs,
        onPlaySong = viewModel::playDownloadedSong
    )
}

/** 已下载页面的多选与删除确认状态，所有修改都发生在点击回调或删除结果回调中 */
@Stable
internal class DownloadManagerSelectionState {
    var selectionMode by mutableStateOf(false)
        private set
    var selectedSongKeys by mutableStateOf(setOf<String>())
        private set
    var songsPendingDelete by mutableStateOf<List<DownloadedSong>>(emptyList())
        private set
    var songToDelete by mutableStateOf<DownloadedSong?>(null)
        private set
    var showMultiDeleteDialog by mutableStateOf(false)
        private set
    var deletingSongCount by mutableIntStateOf(0)
        private set
    var deleteResult by mutableStateOf<DownloadedSongDeleteResult?>(null)
        private set
    private var fullLibrarySelectionRequested by mutableStateOf(false)
    private var deleteEntireLibraryPending by mutableStateOf(false)

    fun enterSelectionMode() {
        selectionMode = true
    }

    fun exitSelectionMode() {
        songsPendingDelete = emptyList()
        selectionMode = false
        selectedSongKeys = emptySet()
        fullLibrarySelectionRequested = false
    }

    fun toggleSelectAll(downloadedSongs: List<DownloadedSong>) {
        if (isAllDownloadedSongsSelected(selectedSongKeys, downloadedSongs)) {
            fullLibrarySelectionRequested = false
            selectedSongKeys = emptySet()
        } else {
            fullLibrarySelectionRequested = true
            selectedSongKeys = downloadedSongs.mapTo(linkedSetOf(), DownloadedSong::deletionIdentity)
        }
    }

    fun toggleSong(selectionKey: String, selected: Boolean) {
        fullLibrarySelectionRequested = false
        selectedSongKeys = toggleSelectedDownloadSongKeys(
            currentSelection = selectedSongKeys,
            selectionKey = selectionKey,
            selected = selected
        )
    }

    fun startSelectionFrom(song: DownloadedSong) {
        if (selectionMode) return
        selectionMode = true
        fullLibrarySelectionRequested = false
        selectedSongKeys = setOf(song.deletionIdentity())
    }

    fun requestDeleteSelected(downloadedSongs: List<DownloadedSong>) {
        if (selectedSongKeys.isEmpty()) return
        songsPendingDelete = captureSongsPendingDelete(
            downloadedSongs = downloadedSongs,
            selectedSongKeys = selectedSongKeys
        )
        if (songsPendingDelete.isNotEmpty()) {
            deleteEntireLibraryPending = fullLibrarySelectionRequested
            showMultiDeleteDialog = true
        }
    }

    fun dismissMultiDelete() {
        showMultiDeleteDialog = false
        songsPendingDelete = emptyList()
        deleteEntireLibraryPending = false
    }

    fun confirmMultiDelete(delete: (List<DownloadedSong>, Boolean) -> Unit) {
        val songsToDelete = songsPendingDelete
        deletingSongCount = songsToDelete.size
        delete(songsToDelete, deleteEntireLibraryPending)
        songsPendingDelete = emptyList()
        deleteEntireLibraryPending = false
        fullLibrarySelectionRequested = false
        selectedSongKeys = emptySet()
        selectionMode = false
        showMultiDeleteDialog = false
    }

    fun requestDelete(song: DownloadedSong) {
        songToDelete = song
    }

    fun dismissSingleDelete() {
        songToDelete = null
    }

    fun confirmSingleDelete(delete: (DownloadedSong) -> Unit) {
        songToDelete?.let { song ->
            deletingSongCount = 1
            delete(song)
        }
        songToDelete = null
    }

    fun reportDeleteResult(result: DownloadedSongDeleteResult) {
        deletingSongCount = 0
        deleteResult = result
    }

    fun consumeDeleteResult() {
        deleteResult = null
    }

    fun sanitize(downloadedSongs: List<DownloadedSong>) {
        val sanitizedState = sanitizeDownloadSelectionState(
            selectionMode = selectionMode,
            selectedSongKeys = selectedSongKeys,
            downloadedSongs = downloadedSongs
        )
        if (sanitizedState.selectionMode != selectionMode) {
            selectionMode = sanitizedState.selectionMode
        }
        if (sanitizedState.selectedSongKeys != selectedSongKeys) {
            selectedSongKeys = sanitizedState.selectedSongKeys
        }
    }
}

internal fun downloadDeleteResultMessage(
    resources: Resources,
    result: DownloadedSongDeleteResult
): String {
    val deletedCount = result.deletedSongs.size
    val failedCount = result.failedSongs.size
    return when {
        result.physicalCleanupPending -> resources.getQuantityString(
            CoreCommonR.plurals.local_files_delete_downloaded_cleanup_pending,
            deletedCount + failedCount,
            deletedCount + failedCount
        )
        deletedCount > 0 && failedCount == 0 -> resources.getQuantityString(
            CoreCommonR.plurals.local_files_delete_downloaded_success,
            deletedCount,
            deletedCount
        )
        deletedCount > 0 -> resources.getQuantityString(
            CoreCommonR.plurals.local_files_delete_downloaded_partial,
            deletedCount,
            deletedCount,
            failedCount
        )
        else -> resources.getString(CoreCommonR.string.local_files_delete_downloaded_failed)
    }
}

@Composable
internal fun DownloadManagerContent(
    downloadedSongs: List<DownloadedSong>,
    legacyPreviewClips: Map<String, Long>,
    isRefreshing: Boolean,
    deleteProgress: DownloadedSongDeleteProgress?,
    deleteFailureDismissed: Boolean,
    listState: LazyListState,
    offlineMode: Boolean,
    onBack: () -> Unit,
    onOpenDownloadProgress: () -> Unit,
    onRefresh: () -> Unit,
    onDismissDeleteFailure: (Long) -> Unit,
    onDeleteSongs: (List<DownloadedSong>, Boolean, (DownloadedSongDeleteResult) -> Unit) -> Unit,
    onPlaySong: (DownloadedSong) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val selection = remember { DownloadManagerSelectionState() }
    var searchQuery by remember { mutableStateOf("") }
    val deletionInProgress = selection.deletingSongCount > 0 ||
        isDownloadedSongDeletionRunning(deleteProgress)

    val deleteResult = selection.deleteResult
    LaunchedEffect(deleteResult) {
        val result = deleteResult ?: return@LaunchedEffect
        AppFeedback.showToast(
            context = context,
            message = downloadDeleteResultMessage(resources, result)
        )
        selection.consumeDeleteResult()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(bottom = miniPlayerHeight)
    ) {
        DownloadManagerTopBar(
            selectionMode = selection.selectionMode,
            selectedCount = selection.selectedSongKeys.size,
            allSelected = isAllDownloadedSongsSelected(
                selectedSongKeys = selection.selectedSongKeys,
                downloadedSongs = downloadedSongs
            ),
            deletionInProgress = deletionInProgress,
            onBack = onBack,
            onToggleSelectAll = { selection.toggleSelectAll(downloadedSongs) },
            onDeleteSelected = { selection.requestDeleteSelected(downloadedSongs) },
            onExitSelection = selection::exitSelectionMode,
            onOpenDownloadProgress = onOpenDownloadProgress,
            onRefresh = onRefresh,
            onEnterSelection = selection::enterSelectionMode
        )

        DownloadedSongDeleteProgressCard(
            progress = deleteProgress,
            failureDismissed = deleteFailureDismissed,
            onDismissFailure = onDismissDeleteFailure,
            requestedSongCount = selection.deletingSongCount,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 下载统计信息
        val totalSize = remember(downloadedSongs) {
            downloadedSongs.sumOf { it.fileSize }
        }
        LaunchedEffect(downloadedSongs, selection.selectionMode) {
            selection.sanitize(downloadedSongs)
        }
        DownloadManagerStatsCard(songCount = downloadedSongs.size, totalSize = totalSize)

        Spacer(modifier = Modifier.height(16.dp))

        DownloadManagerSearchField(query = searchQuery, onQueryChange = { searchQuery = it })

        Spacer(modifier = Modifier.height(16.dp))

        // 多选优先退出
        BackHandler(enabled = selection.selectionMode) { selection.exitSelectionMode() }

        DownloadedSongsList(
            downloadedSongs = downloadedSongs,
            legacyPreviewClips = legacyPreviewClips,
            isRefreshing = isRefreshing,
            searchQuery = searchQuery,
            listState = listState,
            selectionMode = selection.selectionMode,
            selectedSongKeys = selection.selectedSongKeys,
            deletionInProgress = deletionInProgress,
            offlineMode = offlineMode,
            onPlay = onPlaySong,
            onDeleteRequest = selection::requestDelete,
            onSelectionToggle = selection::toggleSong,
            onLongClick = selection::startSelectionFrom
        )
    }

    selection.songToDelete?.let { song ->
        DownloadSingleDeleteDialog(
            songName = song.name,
            deletionInProgress = deletionInProgress,
            onConfirm = {
                selection.confirmSingleDelete { target ->
                    onDeleteSongs(listOf(target), false, selection::reportDeleteResult)
                }
            },
            onDismiss = selection::dismissSingleDelete
        )
    }

    if (selection.showMultiDeleteDialog) {
        DownloadMultiDeleteDialog(
            songCount = selection.songsPendingDelete.size,
            deletionInProgress = deletionInProgress,
            onConfirm = {
                selection.confirmMultiDelete { songs, deleteEntireLibrary ->
                    onDeleteSongs(songs, deleteEntireLibrary, selection::reportDeleteResult)
                }
            },
            onDismiss = selection::dismissMultiDelete
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadManagerTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    deletionInProgress: Boolean,
    onBack: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onExitSelection: () -> Unit,
    onOpenDownloadProgress: () -> Unit,
    onRefresh: () -> Unit,
    onEnterSelection: () -> Unit
) {
    val context = LocalContext.current
    TopAppBar(
        title = {
            Column {
                Text(
                    stringResource(CoreCommonR.string.download_manager_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    stringResource(CoreCommonR.string.download_manager_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CoreCommonR.string.action_back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
        actions = {
            if (selectionMode) {
                // 多选模式下的操作按钮
                Text(
                    text = pluralStringResource(
                        CoreCommonR.plurals.download_selected_count,
                        selectedCount,
                        selectedCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                // 全选/取消全选按钮
                IconButton(
                    enabled = !deletionInProgress,
                    onClick = {
                        context.performHapticFeedback()
                        onToggleSelectAll()
                    }
                ) {
                    Icon(
                        if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = stringResource(
                            if (allSelected) CoreCommonR.string.action_deselect_all else CoreCommonR.string.action_select_all
                        )
                    )
                }
                IconButton(
                    enabled = !deletionInProgress,
                    onClick = {
                        context.performHapticFeedback()
                        onDeleteSelected()
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(CoreCommonR.string.download_delete_selected))
                }
                IconButton(
                    onClick = {
                        context.performHapticFeedback()
                        onExitSelection()
                    }
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(CoreCommonR.string.download_exit_selection))
                }
            } else {
                // 正常模式下的操作按钮
                IconButton(
                    onClick = {
                        context.performHapticFeedback()
                        onOpenDownloadProgress()
                    }
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = stringResource(CoreCommonR.string.download_progress))
                }
                IconButton(
                    onClick = {
                        context.performHapticFeedback()
                        onRefresh()
                    }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(CoreCommonR.string.action_refresh))
                }
                IconButton(
                    onClick = {
                        context.performHapticFeedback()
                        onEnterSelection()
                    }
                ) {
                    Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = stringResource(CoreCommonR.string.action_multi_select))
                }
            }
        }
    )
}

@Composable
private fun DownloadManagerStatsCard(songCount: Int, totalSize: Long) {
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        val shape = RoundedCornerShape(16.dp)
        val baseColor = MaterialTheme.colorScheme.surfaceVariant
        AdvancedGlassSurface(
            role = AdvancedGlassRole.SemanticCard,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            fallbackColor = baseColor.copy(alpha = 0.3f),
            tintColor = baseColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DownloadManagerStat(
                    value = songCount.toString(),
                    label = stringResource(CoreCommonR.string.downloaded_songs)
                )

                VerticalDivider(
                    modifier = Modifier
                        .height(32.dp)
                        .width(1.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )

                DownloadManagerStat(
                    value = formatFileSize(totalSize),
                    label = stringResource(CoreCommonR.string.download_space_used)
                )
            }
        }
    }
}

@Composable
private fun DownloadManagerStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadManagerSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        placeholder = { Text(stringResource(CoreCommonR.string.download_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(CoreCommonR.string.action_search)) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

@Composable
private fun DownloadSingleDeleteDialog(
    songName: String,
    deletionInProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.dialog_confirm_delete)) },
        text = { Text(stringResource(CoreCommonR.string.download_delete_confirm, songName)) },
        confirmButton = {
            TextButton(enabled = !deletionInProgress, onClick = onConfirm) {
                Text(stringResource(CoreCommonR.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DownloadMultiDeleteDialog(
    songCount: Int,
    deletionInProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.dialog_confirm_delete)) },
        text = {
            Text(
                pluralStringResource(
                    CoreCommonR.plurals.download_delete_selected_confirm,
                    songCount,
                    songCount
                )
            )
        },
        confirmButton = {
            TextButton(enabled = !deletionInProgress, onClick = onConfirm) {
                Text(stringResource(CoreCommonR.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

internal fun filterDownloadedSongs(
    downloadedSongs: List<DownloadedSong>,
    searchQuery: String
): List<DownloadedSong> {
    if (searchQuery.isBlank()) return downloadedSongs
    return downloadedSongs.filter { song -> song.matchesDownloadSearch(searchQuery) }
}

private fun DownloadedSong.matchesDownloadSearch(query: String): Boolean =
    displayName().contains(query, ignoreCase = true) ||
        displayArtist().contains(query, ignoreCase = true) ||
        name.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true) ||
        album.contains(query, ignoreCase = true)

@Composable
private fun DownloadedSongsList(
    downloadedSongs: List<DownloadedSong>,
    legacyPreviewClips: Map<String, Long>,
    isRefreshing: Boolean,
    searchQuery: String,
    listState: LazyListState,
    selectionMode: Boolean,
    selectedSongKeys: Set<String>,
    deletionInProgress: Boolean,
    offlineMode: Boolean,
    onPlay: (DownloadedSong) -> Unit,
    onDeleteRequest: (DownloadedSong) -> Unit,
    onSelectionToggle: (String, Boolean) -> Unit,
    onLongClick: (DownloadedSong) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    // 过滤搜索结果
    val filteredSongs = remember(downloadedSongs, searchQuery) {
        filterDownloadedSongs(downloadedSongs, searchQuery)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (filteredSongs.isEmpty()) {
            DownloadedSongsEmptyState(searchActive = searchQuery.isNotBlank())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp + miniPlayerHeight
                ),
            ) {
                items(filteredSongs, key = { it.deletionIdentity() }) { song ->
                    DownloadedSongItem(
                        song = song,
                        isLegacyPreviewClip = legacyPreviewClips[song.filePath] == song.fileSize,
                        isSelected = selectedSongKeys.contains(song.deletionIdentity()),
                        selectionMode = selectionMode,
                        onPlay = { onPlay(song) },
                        onDelete = { onDeleteRequest(song) },
                        deletionInProgress = deletionInProgress,
                        onSelectionChanged = { selected ->
                            onSelectionToggle(song.deletionIdentity(), selected)
                        },
                        onLongClick = { onLongClick(song) },
                        offlineMode = offlineMode
                    )
                }
            }
        }

        if (isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .height(3.dp),
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f)
            )
        }
    }
}

@Composable
private fun DownloadedSongsEmptyState(searchActive: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            Icons.Outlined.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(
                if (searchActive) CoreCommonR.string.download_no_match else CoreCommonR.string.download_no_songs
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(
                if (searchActive) CoreCommonR.string.download_try_other_keywords else CoreCommonR.string.download_songs_hint
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadedSongItem(
    song: DownloadedSong,
    isLegacyPreviewClip: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onSelectionChanged: (Boolean) -> Unit,
    onLongClick: () -> Unit,
    deletionInProgress: Boolean,
    offlineMode: Boolean
) {
    val resolvedCover = remember(song.coverPath, song.customCoverUrl, song.coverUrl) {
        resolveDownloadedSongCoverReference(song)
    }

    val shape = RoundedCornerShape(12.dp)
    val fallbackColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    } else {
        Color.Transparent
    }
    val tintColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = fallbackColor,
        tintColor = tintColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            onSelectionChanged(!isSelected)
                        } else {
                            onPlay()
                        }
                    },
                    onLongClick = onLongClick
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选复选框
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectionChanged(it) },
                    modifier = Modifier.padding(end = 12.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            DownloadedSongCover(resolvedCover = resolvedCover, offlineMode = offlineMode)

            Spacer(modifier = Modifier.width(16.dp))

            DownloadedSongInfo(
                song = song,
                isLegacyPreviewClip = isLegacyPreviewClip,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))


            // 操作按钮
            if (!selectionMode) {
                DownloadedSongActions(
                    deletionInProgress = deletionInProgress,
                    onPlay = onPlay,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun DownloadedSongCover(resolvedCover: String?, offlineMode: Boolean) {
    val context = LocalContext.current
    // 封面或音乐图标
    if (!resolvedCover.isNullOrBlank()) {
        AsyncImage(
            model = remember(context, resolvedCover, offlineMode) {
                offlineCachedImageRequest(
                    context = context,
                    data = resolvedCover,
                    sizePx = 128,
                    allowHardware = false,
                    offlineMode = offlineMode
                )
            },
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
            error = painterResource(id = R.drawable.ic_launcher_foreground)
        )
    } else {
        // 显示默认音乐图标
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DownloadedSongInfo(
    song: DownloadedSong,
    isLegacyPreviewClip: Boolean,
    modifier: Modifier = Modifier
) {
    // 歌曲信息
    Column(modifier = modifier) {
        Text(
            text = song.displayName(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.displayArtist(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${formatFileSize(song.fileSize)} • ${formatDate(song.downloadTime)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isLegacyPreviewClip) {
            Text(
                text = stringResource(CoreCommonR.string.download_legacy_preview_clip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun DownloadedSongActions(
    deletionInProgress: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Row {
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(CoreCommonR.string.download_play),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        IconButton(onClick = onDelete, enabled = !deletionInProgress) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(CoreCommonR.string.download_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun resolveDownloadedSongCoverReference(song: DownloadedSong): String? {
    return song.customCoverUrl
        ?.takeIf(String::isNotBlank)
        ?.takeUnless(::isMediaStoreCoverReference)
        ?: song.coverPath
            ?.takeIf(String::isNotBlank)
            ?.takeUnless(::isMediaStoreCoverReference)
            ?.let { coverPath ->
                if (!coverPath.startsWith("/")) {
                    coverPath
                } else {
                    File(coverPath).takeIf(File::exists)?.toURI()?.toString()
                }
            }
        ?: song.coverUrl?.takeIf(String::isNotBlank)?.takeUnless(::isMediaStoreCoverReference)
}

internal fun toggleSelectedDownloadSongKeys(
    currentSelection: Set<String>,
    selectionKey: String,
    selected: Boolean
): Set<String> {
    return if (selected) {
        currentSelection + selectionKey
    } else {
        currentSelection - selectionKey
    }
}

internal fun captureSongsPendingDelete(
    downloadedSongs: List<DownloadedSong>,
    selectedSongKeys: Set<String>
): List<DownloadedSong> {
    if (selectedSongKeys.isEmpty()) {
        return emptyList()
    }
    return downloadedSongs
        .filter { song -> selectedSongKeys.contains(song.deletionIdentity()) }
        .distinctBy(DownloadedSong::deletionIdentity)
}

internal fun isAllDownloadedSongsSelected(
    selectedSongKeys: Set<String>,
    downloadedSongs: List<DownloadedSong>
): Boolean {
    val availableSongKeys = downloadedSongs
        .mapTo(linkedSetOf(), DownloadedSong::deletionIdentity)
    return availableSongKeys.isNotEmpty() && selectedSongKeys == availableSongKeys
}

internal data class DownloadSelectionState(
    val selectionMode: Boolean,
    val selectedSongKeys: Set<String>
)

internal fun sanitizeDownloadSelectionState(
    selectionMode: Boolean,
    selectedSongKeys: Set<String>,
    downloadedSongs: List<DownloadedSong>
): DownloadSelectionState {
    if (!selectionMode) {
        return DownloadSelectionState(
            selectionMode = false,
            selectedSongKeys = emptySet()
        )
    }
    val validKeys = downloadedSongs.mapTo(mutableSetOf(), DownloadedSong::deletionIdentity)
    if (validKeys.isEmpty()) {
        return DownloadSelectionState(
            selectionMode = false,
            selectedSongKeys = emptySet()
        )
    }
    return DownloadSelectionState(
        selectionMode = true,
        selectedSongKeys = selectedSongKeys.intersect(validKeys)
    )
}
