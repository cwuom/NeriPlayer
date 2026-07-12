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
 * File: moe.ouom.neriplayer.ui.screen.playlist/LocalPlaylistDetailScreen
 * Updated: 2026/3/23
 */


import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.countPendingDownloadTasks
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportResult
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.SongDownloadSubtitle
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalMetadataProcessingState
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.fastScrollableImageRequest
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticOutlinedButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.formatTotalDuration
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.performHapticFeedback
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.io.File

private fun hasCachedLocalDownload(song: SongItem): Boolean {
    return GlobalDownloadManager.hasDownloadedSongCached(song) ||
        ManagedDownloadStorage.peekDownloadedAudio(song) != null
}

private const val BLANK_COVER_MODEL = "about:blank"

private fun playlistNameFieldValue(text: String, maxLength: Int): TextFieldValue {
    val limited = text.take(maxLength)
    return TextFieldValue(
        text = limited,
        selection = TextRange(limited.length)
    )
}

private fun limitedPlaylistNameFieldValue(value: TextFieldValue, maxLength: Int): TextFieldValue {
    val limited = value.text.take(maxLength)
    if (limited == value.text) return value

    return value.copy(
        text = limited,
        selection = TextRange(
            start = value.selection.start.coerceIn(0, limited.length),
            end = value.selection.end.coerceIn(0, limited.length)
        )
    )
}

internal fun areDisplayedSongKeysSelected(
    selectedKeys: Set<String>,
    displayedKeys: Set<String>
): Boolean {
    return displayedKeys.isNotEmpty() && displayedKeys.all(selectedKeys::contains)
}

internal fun toggleDisplayedSongSelection(
    selectedKeys: Set<String>,
    displayedKeys: Set<String>
): Set<String> {
    if (displayedKeys.isEmpty()) return selectedKeys
    return if (areDisplayedSongKeysSelected(selectedKeys, displayedKeys)) {
        selectedKeys - displayedKeys
    } else {
        selectedKeys + displayedKeys
    }
}

internal fun <T> snapshotReversedList(items: List<T>): List<T> {
    return items.toList().asReversed()
}

internal fun selectedStoredLocalSongsForExport(
    storedSongs: List<SongItem>,
    selectedKeys: Set<String>
): List<SongItem> {
    // 目标歌单也会倒序展示，所以这里保留存储顺序，避免全选新建后看起来反过来
    return storedSongs.filter { it.stableKey() in selectedKeys }
}

private fun SongItem.optimisticPlaylistInsertKeys(): Set<String> {
    return buildSet {
        add("identity:${stableKey()}")
        LocalSongSupport.localDuplicateKeys(
            song = this@optimisticPlaylistInsertKeys,
            includeMetadataFallback = true
        ).forEach { key -> add("local:$key") }
    }
}

internal fun normalizeLocalPlaylistHeaderCoverModel(headerCover: String?): String {
    return headerCover?.takeIf { it.isNotBlank() } ?: BLANK_COVER_MODEL
}

internal fun resolveDisplayedLocalPlaylistDetailState(
    uiState: LocalPlaylistDetailUiState,
    requestedPlaylistId: Long
): LocalPlaylistDetailUiState {
    if (uiState.requestedPlaylistId != null && uiState.requestedPlaylistId != requestedPlaylistId) {
        return LocalPlaylistDetailUiState(requestedPlaylistId = requestedPlaylistId)
    }
    val playlist = uiState.playlist ?: return uiState
    return if (playlist.id == requestedPlaylistId) {
        uiState
    } else {
        LocalPlaylistDetailUiState(requestedPlaylistId = requestedPlaylistId)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)
@Composable
@SuppressLint("LocalContextResourcesRead")
fun LocalPlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val vm: LocalPlaylistDetailViewModel = viewModel()
    val rawUiState by vm.uiState.collectAsState()
    val uiState = remember(rawUiState, playlistId) {
        resolveDisplayedLocalPlaylistDetailState(rawUiState, playlistId)
    }
    val scanPreviewState by vm.scanPreviewState.collectAsState()
    val metadataProcessingState by vm.metadataProcessingState.collectAsState()
    val visibleMetadataProcessingState = metadataProcessingState
        .takeIf { it.playlistId == playlistId }
        ?: LocalMetadataProcessingState()
    LaunchedEffect(playlistId) { vm.start(playlistId) }

    // 保存最新的歌单数据，用于在Screen销毁时更新使用记录
    var latestPlaylist by remember { mutableStateOf<moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist?>(null) }
    var playlistDeleted by remember(playlistId) { mutableStateOf(false) }
    LaunchedEffect(uiState.playlist) {
        uiState.playlist?.let { latestPlaylist = it }
    }

    // 在Screen销毁时更新使用记录，确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
            if (playlistDeleted) return@onDispose
            latestPlaylist?.let { playlist ->
                AppContainer.playlistUsageRepo.updateInfo(
                    id = playlist.id,
                    name = playlist.name,
                    picUrl = playlist.displayCoverUrl(context),
                    trackCount = playlist.songs.size,
                    source = "local"
                )
            }
        }
    }

    val playlist = uiState.playlist
    val isResolved = uiState.isResolved
    var deleteNavigationHandled by remember(playlistId) { mutableStateOf(false) }

    fun navigateAfterPlaylistDeleted() {
        if (deleteNavigationHandled) return
        deleteNavigationHandled = true
        playlistDeleted = true
        onDeleted()
    }

    LaunchedEffect(isResolved, playlist, playlistId) {
        if (isResolved && playlist == null) {
            playlistDeleted = true
            AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
            navigateAfterPlaylistDeleted()
        }
    }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(
            tween(300, easing = FastOutSlowInEasing),
            initialOffsetY = { it }) + fadeIn(tween(150)),
        exit = slideOutVertically(
            tween(250, easing = FastOutSlowInEasing),
            targetOffsetY = { it }) + fadeOut(tween(150))
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            if (playlist == null) {
                if (isResolved) {
                    return@Surface
                }
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.playlist_title)) },
                            navigationIcon = {
                                HapticIconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back)
                                    )
                                }
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
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

            val context = LocalContext.current
            val clipboard = LocalClipboard.current
            val isFavorites = FavoritesPlaylist.isSystemPlaylist(playlist, context)
            val isLocalFilesPlaylist = LocalFilesPlaylist.isSystemPlaylist(playlist, context)
            val isSystemPlaylist = isFavorites || isLocalFilesPlaylist
            val isPlaying by PlayerManager.isPlayingFlow.collectAsState()

            val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
            val allPlaylists by repo.playlists.collectAsState()
            val scope = rememberCoroutineScope()
            var syncInProgress by remember { mutableStateOf(false) }
            var showNeteaseSyncConfirm by remember { mutableStateOf(false) }
            var showNeteaseSyncPreview by remember { mutableStateOf(false) }
            var neteaseSyncPreviewSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
            var neteaseSyncPreviewQuery by rememberSaveable { mutableStateOf("") }
            var neteaseSyncSelectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }



            var showDeletePlaylistConfirm by remember { mutableStateOf(false) }
            var showDeleteMultiConfirm by remember { mutableStateOf(false) }
            var showExportSheet by remember { mutableStateOf(false) }
            var detailSong by remember { mutableStateOf<SongItem?>(null) }
            var pendingSyncConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var pendingSyncConfirmLabel by remember { mutableStateOf("") }

            var showSearch by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            var showDownloadManager by remember { mutableStateOf(false) }
            var showLocalScanModeDialog by remember { mutableStateOf(false) }
            var showScanPlaylistExportSheet by remember { mutableStateOf(false) }
            val searchFocusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            
            // 下载进度
            val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
            val hasDownloadManagerEntry = downloadTaskSummary.hasPendingTasks
            val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()

            // Snackbar状态
            val snackbarHostState = remember { SnackbarHostState() }
            val requiredAudioPermission = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            }

            fun showAudioImportResult(result: moe.ouom.neriplayer.ui.viewmodel.playlist.LocalAudioImportUiResult) {
                scope.launch {
                    val resources = context.resources
                    val message = when {
                        result.importedCount > 0 && result.failedCount > 0 -> {
                            val failedSummary = resources.getQuantityString(
                                R.plurals.local_playlist_import_audio_failed_summary,
                                result.failedCount,
                                result.failedCount
                            )
                            resources.getQuantityString(
                                R.plurals.local_playlist_import_audio_partial,
                                result.importedCount,
                                result.importedCount,
                                failedSummary
                            )
                        }
                        result.importedCount > 0 -> {
                            resources.getQuantityString(
                                R.plurals.local_playlist_import_audio_success,
                                result.importedCount,
                                result.importedCount
                            )
                        }
                        result.failedCount == 0 -> {
                            context.getString(R.string.local_playlist_import_audio_no_new)
                        }
                        else -> {
                            resources.getQuantityString(
                                R.plurals.local_playlist_import_audio_failed,
                                result.failedCount,
                                result.failedCount
                            )
                        }
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }

            fun showScannedPlaylistAddResult(result: moe.ouom.neriplayer.ui.viewmodel.playlist.LocalAudioImportUiResult) {
                scope.launch {
                    val message = if (result.importedCount > 0) {
                        context.resources.getQuantityString(
                            R.plurals.local_playlist_add_scanned_success,
                            result.importedCount,
                            result.importedCount
                        )
                    } else {
                        context.getString(R.string.local_playlist_add_scanned_no_new)
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }

            fun handleLocalAudioScanResult(result: LocalAudioImportResult) {
                scope.launch {
                    if (!result.completed) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.local_playlist_scan_preserve_existing)
                        )
                        return@launch
                    }

                    if (result.failedCount > 0) {
                        snackbarHostState.showSnackbar(
                            context.resources.getQuantityString(
                                R.plurals.download_scan_failed,
                                result.failedCount,
                                result.failedCount
                            )
                        )
                    }
                }
            }

            fun startDeviceAudioScan() {
                detailSong = null
                vm.scanDeviceSongs(::handleLocalAudioScanResult)
            }

            fun startFolderAudioScan(folderUri: Uri) {
                detailSong = null
                vm.scanFolderSongs(folderUri, ::handleLocalAudioScanResult)
            }

            fun dismissScanPreviewPage(cancelScan: Boolean = true) {
                showScanPlaylistExportSheet = false
                vm.clearScanPreview(cancelScan = cancelScan)
            }

            val folderScanLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                val persistGranted = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.isSuccess
                if (!persistGranted) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "目录持久授权失败，导入的歌曲在应用重启后可能无法访问"
                        )
                    }
                }
                startFolderAudioScan(uri)
            }

            val audioPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    startDeviceAudioScan()
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.download_scan_permission_required)
                        )
                    }
                }
            }

            if (showLocalScanModeDialog) {
                AlertDialog(
                    onDismissRequest = { showLocalScanModeDialog = false },
                    confirmButton = {
                        HapticTextButton(
                            onClick = {
                                showLocalScanModeDialog = false
                                folderScanLauncher.launch(null)
                            }
                        ) { Text(stringResource(R.string.local_playlist_scan_folder)) }
                    },
                    dismissButton = {
                        HapticTextButton(
                            onClick = {
                                showLocalScanModeDialog = false
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    requiredAudioPermission
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    startDeviceAudioScan()
                                } else {
                                    audioPermissionLauncher.launch(requiredAudioPermission)
                                }
                            }
                        ) { Text(stringResource(R.string.local_playlist_scan_global)) }
                    },
                    title = { Text(stringResource(R.string.local_playlist_scan_mode_title)) },
                    text = { Text(stringResource(R.string.local_playlist_scan_mode_message)) }
                )
            }

            // 可变列表保持存储层顺序，UI 读取时用快照倒序展示
            val localSongs = remember(playlistId) {
                mutableStateListOf<SongItem>().also { it.addAll(playlist.songs) }
            }

            // 阻断 VM->UI 同步；同时用 pendingOrderIdentities 兼容重排和批删
            var blockSync by remember(playlistId) { mutableStateOf(false) }
            var pendingOrderIdentities by remember(playlistId) { mutableStateOf<List<SongIdentity>?>(null) }
            LaunchedEffect(playlist.songs, blockSync, pendingOrderIdentities) {
                val repoIdentities = playlist.songs.map { it.identity() }
                val wanted = pendingOrderIdentities
                if (!blockSync) {
                    localSongs.clear()
                    localSongs.addAll(playlist.songs)
                } else if (wanted != null && wanted == repoIdentities) {
                    localSongs.clear()
                    localSongs.addAll(playlist.songs)
                    pendingOrderIdentities = null
                    blockSync = false
                }
            }

            // 多选
            var selectionMode by remember(playlistId) { mutableStateOf(false) }
            val selectedKeysState = remember(playlistId) { mutableStateOf<Set<String>>(emptySet()) }

            fun toggleSelect(song: SongItem) {
                val songKey = song.stableKey()
                selectedKeysState.value =
                    if (selectedKeysState.value.contains(songKey)) selectedKeysState.value - songKey
                    else selectedKeysState.value + songKey
            }

            fun clearSelection() {
                selectedKeysState.value = emptySet()
            }

            fun exitSelectionMode() {
                selectionMode = false; clearSelection()
            }

            fun launchWithLocalSyncWarning(songs: List<SongItem>, actionLabel: String, action: () -> Unit) {
                if (songs.any { it.isLocalSong() }) {
                    pendingSyncConfirmLabel = actionLabel
                    pendingSyncConfirmAction = action
                } else {
                    action()
                }
            }

            fun appendSongsOptimistically(targetPlaylistId: Long, songs: List<SongItem>) {
                val isCurrentTarget = targetPlaylistId == playlistId ||
                    (targetPlaylistId == LocalFilesPlaylist.SYSTEM_ID && isLocalFilesPlaylist)
                if (!isCurrentTarget || songs.isEmpty()) return
                val existingKeys = HashSet<String>(localSongs.size * 2)
                localSongs.forEach { song ->
                    existingKeys += song.optimisticPlaylistInsertKeys()
                }
                val now = System.currentTimeMillis()
                val additions = songs.mapNotNull { song ->
                    val candidateKeys = song.optimisticPlaylistInsertKeys()
                    if (candidateKeys.any(existingKeys::contains)) {
                        return@mapNotNull null
                    }
                    existingKeys += candidateKeys
                    song.copy(addedAt = now)
                }
                if (additions.isNotEmpty()) {
                    localSongs.addAll(additions)
                }
            }

            fun handleNeteaseSyncResult(result: moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncResult) {
                syncInProgress = false
                val message = result.message ?: if (result.totalSongs == 0) {
                    context.getString(R.string.local_playlist_sync_netease_empty)
                } else {
                    context.getString(
                        R.string.local_playlist_sync_netease_result,
                        result.totalSongs,
                        result.added,
                        result.skippedExisting,
                        result.skippedUnsupported,
                        result.failed
                    )
                }
                scope.launch {
                    snackbarHostState.showSnackbar(message)
                }
            }

            fun syncSelectedNeteaseSongs() {
                if (syncInProgress) return
                val selectedSongs = neteaseSyncPreviewSongs.filter {
                    it.stableKey() in neteaseSyncSelectedKeys
                }
                if (selectedSongs.isEmpty()) return
                syncInProgress = true
                vm.syncSongsToNeteaseLiked(selectedSongs) { result ->
                    showNeteaseSyncPreview = false
                    handleNeteaseSyncResult(result)
                }
            }

            fun openNeteaseSyncPreview() {
                val allSongs = playlist.songs
                if (allSongs.isEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.local_playlist_sync_netease_empty)
                        )
                    }
                    return
                }
                if (syncInProgress) return
                syncInProgress = true
                scope.launch {
                    val plan = repo.prepareNeteaseLikeSyncPlan(
                        AppContainer.neteaseClient,
                        allSongs
                    )
                    syncInProgress = false
                    if (plan.pendingSongs.isEmpty()) {
                        snackbarHostState.showSnackbar(
                            plan.message ?: context.getString(R.string.local_playlist_sync_netease_all_synced)
                        )
                        return@launch
                    }
                    neteaseSyncPreviewSongs = plan.pendingSongs
                    neteaseSyncSelectedKeys = plan.pendingSongs.map { it.stableKey() }.toSet()
                    neteaseSyncPreviewQuery = ""
                    showNeteaseSyncPreview = true
                }
            }

            fun requestNeteaseSync() {
                showNeteaseSyncConfirm = true
            }
            val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(initial = false)

            LaunchedEffect(showSearch, selectionMode) {
                if (showSearch && !selectionMode && autoShowKeyboard) {
                    delay(120)
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                }
            }

            // 重命名
            var showRename by remember { mutableStateOf(false) }
            val maxNameLength = LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH
            var renameText by remember {
                mutableStateOf(playlistNameFieldValue(playlist.name, maxNameLength))
            }
            var renameError by remember { mutableStateOf<String?>(null) }
            fun normalizedRenameName(input: String): String = input.trim().take(maxNameLength)
            fun isSameRenameName(input: String): Boolean {
                return normalizedRenameName(input).equals(
                    normalizedRenameName(playlist.name),
                    ignoreCase = true
                )
            }

            fun validateRename(input: String): String? {
                val name = normalizedRenameName(input)
                if (isSameRenameName(input)) return null
                if (name.isEmpty()) return context.getString(R.string.playlist_name_empty)
                if (SystemLocalPlaylists.matchesReservedName(name, context)) {
                    val reservedName = SystemLocalPlaylists.resolve(
                        playlistId = 0L,
                        playlistName = name,
                        context = context
                    )?.currentName ?: name
                    return context.getString(R.string.library_name_reserved, reservedName)
                }
                if (allPlaylists.any {
                        it.id != playlist.id && it.name.equals(
                            name,
                            ignoreCase = true
                        )
                    }) {
                    return context.getString(R.string.library_name_exists)
                }
                return null
            }

            if (showRename) {
                AlertDialog(
                    onDismissRequest = { showRename = false },
                    confirmButton = {
                        val trimmed = normalizedRenameName(renameText.text)
                        val disabled = renameError != null || isSameRenameName(renameText.text)
                        HapticTextButton(
                            onClick = {
                                val error = validateRename(renameText.text)
                                if (error != null) {
                                    renameError = error
                                } else if (!disabled) {
                                    vm.rename(trimmed)
                                    showRename = false
                                }
                            },
                            enabled = !disabled
                        ) { Text(stringResource(R.string.action_confirm)) }
                    },
                    dismissButton = {
                        HapticTextButton(onClick = {
                            showRename = false
                        }) { Text(stringResource(R.string.action_cancel)) }
                    },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = {
                                val limitedValue = limitedPlaylistNameFieldValue(it, maxNameLength)
                                renameText = limitedValue
                                renameError = validateRename(limitedValue.text)
                            },
                            singleLine = true,
                            isError = renameError != null,
                            supportingText = {
                                val err = renameError
                                if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
                            }
                        )
                    },
                    title = { Text(stringResource(R.string.local_playlist_rename)) }
                )
            }

            // 拖拽
            val headerKey = "header"

            val reorderState = rememberReorderableLazyListState(
                onMove = { from: ItemPosition, to: ItemPosition ->
                    if (!blockSync) blockSync = true
                    val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
                    val toKey = to.key as? String ?: return@rememberReorderableLazyListState
                    val fromIdx = localSongs.indexOfFirst { it.stableKey() == fromKey }
                    val toIdx = localSongs.indexOfFirst { it.stableKey() == toKey }
                    if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                        localSongs.add(toIdx, localSongs.removeAt(fromIdx))
                    }
                },
                canDragOver = { _, over -> (over.key as? String) != headerKey },
                onDragEnd = { _, _ ->
                    val newOrder = localSongs.map { it.identity() }
                    pendingOrderIdentities = newOrder
                    blockSync = true
                    scope.launch {
                        vm.reorderSongs(newOrder)
                    }
                }
            )

            // 记住滚动位置，避免切换页面后回到顶部（用稳定 key 防止列表变动导致错位）
            val savedListKey = rememberSaveable(playlistId) { mutableStateOf<String?>(null) }
            var savedListOffset by rememberSaveable(playlistId) { mutableIntStateOf(0) }
            val hasRestoredScroll = rememberSaveable(playlistId) { mutableStateOf(false) }
            val listState = reorderState.listState
            val baseQueue by remember(localSongs) {
                derivedStateOf { snapshotReversedList(localSongs) }
            }
            val queueIndexBySongKey by remember(baseQueue) {
                derivedStateOf {
                    buildMap(baseQueue.size) {
                        baseQueue.forEachIndexed { index, song ->
                            put(song.stableKey(), index)
                        }
                    }
                }
            }
            val playlistCover = rememberPlaylistDisplayCoverUrl(
                playlist = playlist,
                resolveLocalFallback = true
            )
            val headerCover = playlistCover ?: remember(baseQueue) {
                baseQueue.firstNotNullOfOrNull { song ->
                    song.displayCoverUrl()?.takeIf(String::isNotBlank)
                }
            }
            val displayedSongs by remember(baseQueue, searchQuery, context) {
                derivedStateOf {
                    val base = baseQueue
                    if (searchQuery.isBlank()) base
                    else base.filter { song ->
                        listOfNotNull(
                            song.name,
                            song.artist,
                            song.customName,
                            song.customArtist,
                            song.displayAlbum(context),
                            song.localFileName,
                            song.localFilePath,
                            song.originalName,
                            song.originalArtist
                        ).any { field ->
                            field.contains(searchQuery, ignoreCase = true)
                        }
                    }
                }
            }

            LaunchedEffect(listState) {
                snapshotFlow {
                    Triple(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                        listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                    )
                }
                    .distinctUntilChanged()
                    .collect { (_, offset, key) ->
                        if (key != null) {
                            savedListKey.value = key
                            savedListOffset = offset
                        }
                    }
            }
            LaunchedEffect(playlistId, displayedSongs) {
                if (!hasRestoredScroll.value) {
                    val targetIndex = when (val key = savedListKey.value) {
                        null -> null
                        headerKey -> 0
                        else -> {
                            val idx = displayedSongs.indexOfFirst { it.stableKey() == key }
                            if (idx >= 0) idx + 1 else null
                        }
                    }
                    if (targetIndex != null && (targetIndex != 0 || savedListOffset != 0)) {
                        listState.scrollToItem(targetIndex, savedListOffset)
                    }
                    hasRestoredScroll.value = true
                }
            }

            // 统计
            val totalDurationMs by remember(localSongs) {
                derivedStateOf { localSongs.sumOf { it.durationMs } }
            }

            // 当前播放 & FAB
            val currentSong by PlayerManager.currentSongFlow.collectAsState()
            val currentIndexInSource = remember(localSongs, currentSong) {
                localSongs.indexOfFirst { it.sameIdentityAs(currentSong) }
            }
            val selectedSongsForAction by remember(localSongs, selectedKeysState.value) {
                derivedStateOf {
                    localSongs.filter { it.stableKey() in selectedKeysState.value }
                }
            }
            val hasSelectedOnlineSongs by remember(selectedSongsForAction) {
                derivedStateOf { selectedSongsForAction.any { !it.isLocalSong() } }
            }

            if (scanPreviewState.visible) {
                LocalScanPreviewScreen(
                    isScanning = scanPreviewState.isScanning,
                    songs = scanPreviewState.songs,
                    query = scanPreviewState.query,
                    onQueryChange = vm::updateScanPreviewQuery,
                    metadataOnly = scanPreviewState.metadataOnly,
                    onMetadataOnlyChange = vm::updateScanPreviewMetadataOnly,
                    selectedKeys = scanPreviewState.selectedKeys,
                    onSelectedKeysChange = vm::updateScanPreviewSelection,
                    snackbarHostState = snackbarHostState,
                    onBack = ::dismissScanPreviewPage,
                    onImport = {
                        val selectedSongs = scanPreviewState.songs.filter {
                            it.stableKey() in scanPreviewState.selectedKeys
                        }
                        appendSongsOptimistically(LocalFilesPlaylist.SYSTEM_ID, selectedSongs)
                        vm.applyScannedSongs(selectedSongs, ::showAudioImportResult)
                        dismissScanPreviewPage(cancelScan = false)
                    },
                    onSecondaryAction = {
                        showScanPlaylistExportSheet = true
                    },
                    secondaryActionLabel = stringResource(R.string.download_scan_add_to_playlist)
                )
                if (showScanPlaylistExportSheet) {
                    PlaylistExportSheet(
                        title = stringResource(R.string.download_scan_add_to_playlist),
                        playlists = allPlaylists.filterNot {
                            LocalFilesPlaylist.isSystemPlaylist(it, context)
                        },
                        selectedCount = scanPreviewState.selectedKeys.size,
                        onDismissRequest = { showScanPlaylistExportSheet = false },
                        onCreateAndExport = { name ->
                            val selectedSongs = scanPreviewState.songs.filter {
                                it.stableKey() in scanPreviewState.selectedKeys
                            }
                            launchWithLocalSyncWarning(
                                songs = selectedSongs,
                                actionLabel = context.getString(R.string.playlist_add_to)
                            ) {
                                vm.createPlaylistWithScannedSongs(
                                    name = name,
                                    songs = selectedSongs,
                                    onResult = ::showScannedPlaylistAddResult
                                )
                            }
                        },
                        onExportToPlaylist = { target ->
                            val selectedSongs = scanPreviewState.songs.filter {
                                it.stableKey() in scanPreviewState.selectedKeys
                            }
                            launchWithLocalSyncWarning(
                                songs = selectedSongs,
                                actionLabel = context.getString(R.string.playlist_add_to)
                            ) {
                                appendSongsOptimistically(target.id, selectedSongs)
                                vm.addScannedSongsToPlaylist(
                                    targetPlaylistId = target.id,
                                    songs = selectedSongs,
                                    onResult = ::showScannedPlaylistAddResult
                                )
                            }
                        },
                        createActionLabel = stringResource(R.string.playlist_create_and_add)
                    )
                }
                pendingSyncConfirmAction?.let { action ->
                    LocalSongSyncConfirmDialog(
                        actionLabel = pendingSyncConfirmLabel,
                        onConfirm = {
                            pendingSyncConfirmAction = null
                            pendingSyncConfirmLabel = ""
                            action()
                        },
                        onDismiss = {
                            pendingSyncConfirmAction = null
                            pendingSyncConfirmLabel = ""
                        }
                    )
                }
                return@Surface
            }

            if (showNeteaseSyncPreview) {
                LocalScanPreviewScreen(
                    isScanning = false,
                    songs = neteaseSyncPreviewSongs,
                    query = neteaseSyncPreviewQuery,
                    onQueryChange = { neteaseSyncPreviewQuery = it },
                    selectedKeys = neteaseSyncSelectedKeys,
                    onSelectedKeysChange = { neteaseSyncSelectedKeys = it },
                    snackbarHostState = snackbarHostState,
                    onBack = { showNeteaseSyncPreview = false },
                    onImport = { syncSelectedNeteaseSongs() },
                    title = stringResource(R.string.local_playlist_sync_netease_preview_title),
                    actionLabel = { count ->
                        context.getString(R.string.local_playlist_sync_selected, count)
                    },
                    searchPlaceholder = stringResource(R.string.local_playlist_sync_search),
                    emptyText = stringResource(R.string.local_playlist_sync_empty),
                    isBusy = syncInProgress
                )
                return@Surface
            }

            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { 
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(bottom = LocalMiniPlayerHeight.current)
                    ) 
                },
                topBar = {
                    if (!selectionMode) {
                        TopAppBar(
                            title = {
                                val displayName = when {
                                    isFavorites -> stringResource(R.string.favorite_my_music)
                                    isLocalFilesPlaylist -> stringResource(R.string.local_files)
                                    else -> playlist.name
                                }
                                Text(
                                    displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                HapticIconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back)
                                    )
                                }
                            },
                            actions = {
                                HapticIconButton(onClick = {
                                    showSearch = !showSearch
                                    if (!showSearch) {
                                        searchQuery = ""
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search_songs)) }
                                
                                if (hasDownloadManagerEntry) {
                                    HapticIconButton(
                                        onClick = { showDownloadManager = true }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Download,
                                            contentDescription = stringResource(R.string.cd_download_manager),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                if (isLocalFilesPlaylist) {
                                    HapticIconButton(onClick = {
                                        showLocalScanModeDialog = true
                                    }, enabled = !scanPreviewState.isScanning) {
                                        Icon(
                                            Icons.Outlined.LibraryMusic,
                                            contentDescription = stringResource(R.string.download_scan_local)
                                        )
                                    }
                                }
                                if (isFavorites) {
                                    HapticIconButton(
                                        onClick = { requestNeteaseSync() },
                                        enabled = !syncInProgress
                                    ) {
                                        if (syncInProgress) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Outlined.Sync,
                                                contentDescription = stringResource(R.string.local_playlist_sync_netease_liked)
                                            )
                                        }
                                    }
                                }
                                
                                if (!isSystemPlaylist) {
                                    HapticIconButton(onClick = {
                                        renameText = playlistNameFieldValue(playlist.name, maxNameLength)
                                        renameError = null
                                        showRename = true
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.local_playlist_rename))
                                    }
                                    HapticIconButton(onClick = {
                                        showDeletePlaylistConfirm = true
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.local_playlist_delete)
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
                        val displayedSongKeys = displayedSongs.map { it.stableKey() }.toSet()
                        val allSelected = areDisplayedSongKeysSelected(
                            selectedKeys = selectedKeysState.value,
                            displayedKeys = displayedSongKeys
                        )
                        TopAppBar(
                            title = {
                                Text(
                                    pluralStringResource(
                                        R.plurals.common_selected_count,
                                        selectedKeysState.value.size,
                                        selectedKeysState.value.size
                                    )
                                )
                            },
                            navigationIcon = {
                                HapticIconButton(onClick = { exitSelectionMode() }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_exit_select)
                                    )
                                }
                            },
                            actions = {
                                HapticIconButton(
                                    onClick = {
                                        selectedKeysState.value = toggleDisplayedSongSelection(
                                            selectedKeys = selectedKeysState.value,
                                            displayedKeys = displayedSongKeys
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                        contentDescription = if (allSelected) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)
                                    )
                                }
                                HapticIconButton(
                                    onClick = {
                                        if (selectedKeysState.value.isNotEmpty()) {
                                            showExportSheet = true
                                        }
                                    },
                                    enabled = selectedKeysState.value.isNotEmpty()
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.PlaylistAdd,
                                        contentDescription = stringResource(R.string.cd_export_playlist)
                                    )
                                }
                                HapticIconButton(
                                    onClick = {
                                        if (selectedSongsForAction.isNotEmpty() && hasSelectedOnlineSongs) {
                                            val onlineSongs = selectedSongsForAction.filterNot { it.isLocalSong() }
                                            showDownloadManager = true
                                            exitSelectionMode()
                                            GlobalDownloadManager.startBatchDownload(context, onlineSongs)
                                        }
                                    },
                                    enabled = selectedSongsForAction.isNotEmpty() && hasSelectedOnlineSongs
                                ) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.cd_download_selected)
                                    )
                                }
                                HapticIconButton(
                                    onClick = {
                                        if (selectedKeysState.value.isNotEmpty()) {
                                            showDeleteMultiConfirm = true
                                        }
                                    },
                                    enabled = selectedKeysState.value.isNotEmpty()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete_selected))
                                }
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            ) { padding ->
                val miniPlayerHeight = LocalMiniPlayerHeight.current
                Column(Modifier.padding(padding).fillMaxSize()) {
                    AnimatedVisibility(showSearch && !selectionMode) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text(stringResource(R.string.search_playlist)) },
                            singleLine = true
                        )
                    }

                    Box(Modifier.fillMaxSize()) {
                        val headerHeight: Dp = 240.dp

                        key(playlistId) {
                            LazyColumn(
                                state = reorderState.listState,
                                contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
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
                                    // 头图取"展示顺序"的第一张有封面的
                                    AsyncImage(
                                        model = offlineCachedImageRequest(
                                            context = context,
                                            data = normalizeLocalPlaylistHeaderCoverModel(headerCover),
                                            sizePx = 768,
                                            allowHardware = false,
                                            crossfade = true,
                                            offlineMode = offlineMode
                                        ),
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
                                        val headerDisplayName = when {
                                            isFavorites -> stringResource(R.string.favorite_my_music)
                                            isLocalFilesPlaylist -> stringResource(R.string.local_files)
                                            else -> playlist.name
                                        }
                                        Text(
                                            text = headerDisplayName,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                shadow = Shadow(
                                                    color = Color.Black.copy(alpha = 0.6f),
                                                    offset = Offset(2f, 2f),
                                                    blurRadius = 4f
                                                )
                                            ),
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.local_playlist_total_duration, formatTotalDuration(context, totalDurationMs), localSongs.size),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                shadow = Shadow(
                                                    color = Color.Black.copy(alpha = 0.6f),
                                                    offset = Offset(2f, 2f),
                                                    blurRadius = 4f
                                                )
                                            ),
                                            color = Color.White.copy(alpha = 0.92f)
                                        )
                                    }
                                }
                            }

                            if (visibleMetadataProcessingState.isProcessing) {
                                item(
                                    key = "metadata_processing_card",
                                    contentType = "local_metadata_processing"
                                ) {
                                    LocalMetadataProcessingCard(visibleMetadataProcessingState)
                                }
                            }

                            // 列表（倒序）
                            itemsIndexed(
                                items = displayedSongs,
                                key = { _, song -> song.stableKey() },
                                contentType = { _, _ -> "local_playlist_song" }
                            ) { revIndex, song ->
                                ReorderableItem(state = reorderState, key = song.stableKey()) { isDragging ->
                                    val rowScale by animateFloatAsState(
                                        targetValue = if (isDragging) 1.02f else 1f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "row-scale"
                                    )
                                    val isSelectedSong =
                                        selectionMode && selectedKeysState.value.contains(song.stableKey())
                                    val rowContainerColor = if (isSelectedSong) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                    } else {
                                        Color.Transparent
                                    }

                                    Row(
                                        modifier = Modifier
                                            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
                                            .fillMaxWidth()
                                            .background(rowContainerColor)
                                            .combinedClickable(
                                                onClick = {
                                                    context.performHapticFeedback()
                                                    if (selectionMode) {
                                                        toggleSelect(song)
                                                    } else {
                                                        val pos = queueIndexBySongKey[song.stableKey()] ?: -1
                                                        if (pos >= 0) onSongClick(baseQueue, pos)
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!selectionMode) {
                                                        selectionMode = true
                                                        selectedKeysState.value = setOf(song.stableKey())
                                                    } else {
                                                        toggleSelect(song)
                                                    }
                                                }
                                            )
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // 序号/复选框
                                            Box(
                                                Modifier.width(48.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (selectionMode) {
                                                    Checkbox(
                                                        checked = selectedKeysState.value.contains(song.stableKey()),
                                                        onCheckedChange = { toggleSelect(song) }
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
                                            val itemContext = LocalContext.current
                                            val displayCoverUrl = rememberSongDisplayCoverUrl(song)
                                            if (!displayCoverUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = fastScrollableImageRequest(
                                                        context = itemContext,
                                                        data = displayCoverUrl,
                                                        sizePx = 128,
                                                        crossfade = false,
                                                        offlineMode = offlineMode
                                                    ),
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
                                                val downloaded = remember(downloadPresenceVersion, song) {
                                                    hasCachedLocalDownload(song)
                                                }
                                                Text(
                                                    text = song.displayName(),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                SongDownloadSubtitle(
                                                    text = song.displayArtist(),
                                                    downloaded = downloaded
                                                )
                                            }
                                        }

                                        // 右侧：非多选为时间/播放态；多选为手柄
                                        val isPlayingSong = currentSong?.sameIdentityAs(song) == true
                                        val trailingVisible = !isDragging && !selectionMode

                                        if (!selectionMode) {
                                            AnimatedVisibility(
                                                visible = trailingVisible,
                                                enter = fadeIn(tween(120)),
                                                exit = fadeOut(tween(100))
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    if (isPlayingSong) {
                                                        PlayingIndicator(
                                                            color = MaterialTheme.colorScheme.primary,
                                                            animate = isPlaying
                                                        )
                                                    } else {
                                                        Text(
                                                            text = formatDuration(song.durationMs),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }

                                                    // 更多操作菜单
                                                    var showMoreMenu by remember { mutableStateOf(false) }
                                                    Box {
                                                        IconButton(
                                                            onClick = { showMoreMenu = true }
                                                        ) {
                                                            Icon(
                                                                Icons.Filled.MoreVert,
                                                                contentDescription = stringResource(R.string.cd_more_actions),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }

                                                        DropdownMenu(
                                                            expanded = showMoreMenu,
                                                            onDismissRequest = { showMoreMenu = false }
                                                        ) {
                                                            if (song.isLocalSong()) {
                                                                DropdownMenuItem(
                                                                    text = { Text(stringResource(R.string.local_song_open_details)) },
                                                                    onClick = {
                                                                        detailSong = song
                                                                        showMoreMenu = false
                                                                    }
                                                                )
                                                                DropdownMenuItem(
                                                                    text = { Text(stringResource(R.string.action_share)) },
                                                                    onClick = {
                                                                        showMoreMenu = false
                                                                        scope.launch {
                                                                            val shared = runCatching {
                                                                                LocalMediaSupport.shareSongFile(context, song)
                                                                            }.getOrElse { false }
                                                                            if (!shared) {
                                                                                snackbarHostState.showSnackbar(
                                                                                    context.getString(R.string.local_song_share_failed)
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.local_playlist_play_next)) },
                                                                onClick = {
                                                                    PlayerManager.addToQueueNext(song)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.playlist_add_to_end)) },
                                                                onClick = {
                                                                    PlayerManager.addToQueueEnd(song)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.action_copy_song_info)) },
                                                                onClick = {
                                                                    val songInfo =
                                                                        "${song.displayName()}-${song.displayArtist()}"
                                                                    scope.launch {
                                                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", songInfo)))
                                                                        snackbarHostState.showSnackbar(
                                                                            context.getString(R.string.toast_copied)
                                                                        )
                                                                    }
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .detectReorder(reorderState)
                                                    .padding(8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.DragHandle,
                                                    contentDescription = stringResource(R.string.common_drag_handle),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }

                        // 定位到正在播放
                        val currentIndexInDisplay = if (currentIndexInSource >= 0) {
                            displayedSongs.indexOfFirst { it.sameIdentityAs(currentSong) }
                        } else -1

                        if (currentIndexInDisplay >= 0) {
                            HapticFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        reorderState.listState.animateScrollToItem(
                                            currentIndexInDisplay + 1
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(
                                        bottom = 16.dp + miniPlayerHeight,
                                        end = 16.dp
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = stringResource(R.string.cd_locate_playing)
                                )
                            }
                        }
                        

                    }
                }

                // 删除歌单二次确认
                if (showDeletePlaylistConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeletePlaylistConfirm = false },
                        title = { Text(stringResource(R.string.local_playlist_delete)) },
                        text = { Text(stringResource(R.string.local_playlist_delete_confirm)) },
                        confirmButton = {
                            HapticTextButton(onClick = {
                                showDeletePlaylistConfirm = false
                                vm.delete { ok ->
                                    if (ok) navigateAfterPlaylistDeleted()
                                }
                            }) { Text(stringResource(R.string.action_delete)) }
                        },
                        dismissButton = {
                            HapticTextButton(onClick = {
                                showDeletePlaylistConfirm = false
                            }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }

                // 多选删除确认
                if (showDeleteMultiConfirm) {
                    val count = selectedKeysState.value.size
                    AlertDialog(
                        onDismissRequest = { showDeleteMultiConfirm = false },
                        title = { Text(stringResource(R.string.local_playlist_delete_songs)) },
                        text = {
                            Text(
                                pluralStringResource(
                                    R.plurals.local_playlist_delete_songs_confirm,
                                    count,
                                    count
                                )
                            )
                        },
                        confirmButton = {
                            HapticTextButton(onClick = {
                                val selectedKeys = selectedKeysState.value
                                val removeAll = localSongs.isNotEmpty() &&
                                    selectedKeys.size == localSongs.size &&
                                    localSongs.all { it.stableKey() in selectedKeys }
                                var songsToRemove = emptyList<SongItem>()
                                val expectedSongs = if (removeAll) {
                                    emptyList()
                                } else {
                                    songsToRemove = localSongs.filter {
                                        it.stableKey() in selectedKeys
                                    }
                                    val removeIdentities = songsToRemove.map { it.identity() }.toSet()
                                    localSongs.filterNot { it.identity() in removeIdentities }
                                }
                                pendingOrderIdentities = expectedSongs.map { it.identity() }
                                blockSync = true

                                localSongs.clear()
                                localSongs.addAll(expectedSongs)
                                showDeleteMultiConfirm = false
                                exitSelectionMode()

                                if (removeAll) {
                                    vm.clearSongs()
                                } else {
                                    vm.removeSongs(songsToRemove)
                                }
                            }) { Text(stringResource(R.string.local_playlist_delete_count, count)) }
                        },
                        dismissButton = {
                            HapticTextButton(onClick = {
                                showDeleteMultiConfirm = false
                            }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }

                // 多选导出
                if (showExportSheet) {
                    PlaylistExportSheet(
                        title = stringResource(R.string.local_playlist_export_to),
                        playlists = allPlaylists.filter {
                            it.id != playlist.id && !LocalFilesPlaylist.isSystemPlaylist(it, context)
                        },
                        selectedCount = selectedKeysState.value.size,
                        onDismissRequest = { showExportSheet = false },
                        onCreateAndExport = { name ->
                            val songs = selectedStoredLocalSongsForExport(
                                storedSongs = localSongs,
                                selectedKeys = selectedKeysState.value
                            )
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = context.getString(R.string.playlist_add_to)
                            ) {
                                scope.launch {
                                    repo.createPlaylistWithPreparedSongs(name, songs)
                                }
                                exitSelectionMode()
                            }
                        },
                        onExportToPlaylist = { target ->
                            val songs = selectedStoredLocalSongsForExport(
                                storedSongs = localSongs,
                                selectedKeys = selectedKeysState.value
                            )
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = context.getString(R.string.playlist_add_to)
                            ) {
                                scope.launch {
                                    repo.addPreparedSongsToPlaylist(target.id, songs)
                                }
                                exitSelectionMode()
                            }
                        }
                    )
                }

                // 下载管理器
                if (showDownloadManager) {
                    val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
                    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
                    val pendingTaskCount = remember(downloadTasks) {
                        countPendingDownloadTasks(downloadTasks)
                    }
                    val progress = batchDownloadProgress
                    BatchDownloadManagerSheet(
                        batchDownloadProgress = progress,
                        downloadTasks = downloadTasks,
                        progressSummaryText = if (progress != null) {
                            stringResource(
                                R.string.bili_download_progress_format,
                                progress.completedSongs,
                                progress.totalSongs
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.download_tasks_count,
                                pendingTaskCount,
                                pendingTaskCount
                            )
                        },
                        onDismiss = { showDownloadManager = false }
                    )
                }

                detailSong?.let { song ->
                    LocalSongDetailsDialog(
                        song = song,
                        onDismiss = { detailSong = null }
                    )
                }

                if (showNeteaseSyncConfirm) {
                    AlertDialog(
                        onDismissRequest = { showNeteaseSyncConfirm = false },
                        title = { Text(stringResource(R.string.local_playlist_sync_netease_confirm_title)) },
                        text = { Text(stringResource(R.string.local_playlist_sync_netease_confirm_message)) },
                        confirmButton = {
                            HapticTextButton(
                                onClick = {
                                    showNeteaseSyncConfirm = false
                                    openNeteaseSyncPreview()
                                }
                            ) { Text(stringResource(R.string.action_confirm)) }
                        },
                        dismissButton = {
                            HapticTextButton(
                                onClick = { showNeteaseSyncConfirm = false }
                            ) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }

                pendingSyncConfirmAction?.let { action ->
                    LocalSongSyncConfirmDialog(
                        actionLabel = pendingSyncConfirmLabel,
                        onConfirm = {
                            pendingSyncConfirmAction = null
                            pendingSyncConfirmLabel = ""
                            action()
                        },
                        onDismiss = {
                            pendingSyncConfirmAction = null
                            pendingSyncConfirmLabel = ""
                        }
                    )
                }

                // 多选优先退出
                BackHandler(enabled = selectionMode) { exitSelectionMode() }
            }
        }
    }
}

@Composable
private fun LocalMetadataProcessingCard(state: LocalMetadataProcessingState) {
    val total = state.totalCount.coerceAtLeast(0)
    val processed = state.processedCount.coerceIn(0, total.takeIf { it > 0 } ?: Int.MAX_VALUE)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.local_playlist_metadata_processing_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = if (total > 0) {
                        stringResource(
                            R.string.local_playlist_metadata_processing_message,
                            processed,
                            total
                        )
                    } else {
                        stringResource(R.string.local_playlist_metadata_processing_message_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
        }
    }
}

private data class LocalScanPreviewItem(
    val song: SongItem,
    val stableKey: String,
    val title: String,
    val fileName: String,
    val filePath: String,
    val subtitle: String,
    val hasMetadata: Boolean,
    val searchText: String
)

private fun SongItem.toLocalScanPreviewItem(context: Context): LocalScanPreviewItem {
    val resolvedPath = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?: mediaUri?.takeIf { it.startsWith("/") }
        ?: mediaUri.orEmpty()
    val displayName = displayName()
    val displayArtist = displayArtist()
    val displayAlbum = displayAlbum(context)
    val resolvedFileName = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.name
        ?: localFileName?.takeIf { it.isNotBlank() }
        ?: displayName
    val hasMetadata = hasMeaningfulPreviewMetadata(context, resolvedFileName)
    val subtitle = buildList {
        displayArtist.takeIf { it.isNotBlank() }?.let(::add)
        displayAlbum.takeIf { it.isNotBlank() }?.let(::add)
        resolvedFileName.takeIf { it.isNotBlank() && it != displayName }?.let(::add)
        durationMs.takeIf { it > 0L }?.let { add(formatDuration(it)) }
    }.joinToString(" · ")
    return LocalScanPreviewItem(
        song = this,
        stableKey = stableKey(),
        title = displayName,
        fileName = resolvedFileName,
        filePath = resolvedPath,
        subtitle = subtitle,
        hasMetadata = hasMetadata,
        searchText = listOf(resolvedFileName, resolvedPath, displayName, displayArtist, displayAlbum)
            .joinToString("\n")
    )
}

private fun SongItem.hasMeaningfulPreviewMetadata(context: Context, fileName: String): Boolean {
    val fileTitle = fileName.substringBeforeLast('.', fileName).trim()
    val unknownArtist = context.getString(R.string.music_unknown_artist)
    val hasTitleMetadata = name.isNotBlank() &&
        (fileTitle.isBlank() || !name.equals(fileTitle, ignoreCase = true))
    val hasArtistMetadata = artist.trim().isNotBlank() &&
        !artist.equals(unknownArtist, ignoreCase = true)
    val hasAlbumMetadata = album.trim().isNotBlank() &&
        album != moe.ouom.neriplayer.data.local.media.LocalSongSupport.LOCAL_ALBUM_IDENTITY &&
        !LocalFilesPlaylist.matches(album, context)
    return hasTitleMetadata || hasArtistMetadata || hasAlbumMetadata ||
        !coverUrl.isNullOrBlank() || !originalCoverUrl.isNullOrBlank()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LocalScanPreviewScreen(
    isScanning: Boolean,
    songs: List<SongItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    metadataOnly: Boolean = false,
    onMetadataOnlyChange: ((Boolean) -> Unit)? = null,
    selectedKeys: Set<String>,
    onSelectedKeysChange: (Set<String>) -> Unit,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null,
    title: String? = null,
    actionLabel: ((Int) -> String)? = null,
    secondaryActionLabel: String? = null,
    searchPlaceholder: String? = null,
    emptyText: String? = null,
    isBusy: Boolean = false
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val previewItems by produceState<List<LocalScanPreviewItem>>(
        initialValue = emptyList(),
        songs,
        appContext
    ) {
        value = withContext(Dispatchers.Default) {
            songs.map { it.toLocalScanPreviewItem(appContext) }
        }
    }
    val listState = rememberLazyListState()
    val displayedItems by produceState<List<LocalScanPreviewItem>>(
        initialValue = emptyList(),
        previewItems,
        query,
        metadataOnly
    ) {
        val keyword = query.trim()
        value = withContext(Dispatchers.Default) {
            previewItems
                .asSequence()
                .filter { item -> !metadataOnly || item.hasMetadata }
                .filter { item -> keyword.isBlank() || item.searchText.contains(keyword, ignoreCase = true) }
                .toList()
        }
    }
    LaunchedEffect(metadataOnly, previewItems) {
        if (metadataOnly) {
            val metadataKeys = previewItems
                .asSequence()
                .filter { it.hasMetadata }
                .mapTo(LinkedHashSet()) { it.stableKey }
            val nextSelectedKeys = selectedKeys.intersect(metadataKeys)
            if (nextSelectedKeys != selectedKeys) {
                onSelectedKeysChange(nextSelectedKeys)
            }
        }
    }
    var showMoreMenu by remember { mutableStateOf(false) }
    val metadataFilterAvailable = onMetadataOnlyChange != null
    val displayedKeys by remember(displayedItems) {
        derivedStateOf {
            displayedItems.mapTo(LinkedHashSet(displayedItems.size)) { it.stableKey }
        }
    }
    val allDisplayedSelected = displayedKeys.isNotEmpty() && displayedKeys.all(selectedKeys::contains)
    val resolvedTitle = title ?: stringResource(R.string.local_playlist_scan_preview_title)
    val resolvedSearchPlaceholder =
        searchPlaceholder ?: stringResource(R.string.local_playlist_scan_preview_search)
    val resolvedEmptyText = emptyText ?: if (metadataOnly) {
        stringResource(R.string.local_playlist_scan_metadata_empty)
    } else {
        stringResource(R.string.download_scan_empty)
    }
    val resolvedActionLabel = actionLabel?.invoke(selectedKeys.size)
        ?: stringResource(R.string.download_scan_add_selected, selectedKeys.size)
    val showBusy = isScanning || isBusy

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalMiniPlayerHeight.current)
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(resolvedTitle) },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (showBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    if (metadataFilterAvailable) {
                        Box {
                            HapticIconButton(onClick = { showMoreMenu = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.common_more_options)
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.local_playlist_scan_filter_metadata))
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = metadataOnly,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        onMetadataOnlyChange?.invoke(!metadataOnly)
                                        showMoreMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(bottom = LocalMiniPlayerHeight.current)
                ) {
                    if (isScanning && songs.isEmpty()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = pluralStringResource(
                            R.plurals.common_selected_count,
                            selectedKeys.size,
                            selectedKeys.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        if (onSecondaryAction != null && secondaryActionLabel != null) {
                            HapticOutlinedButton(
                                enabled = selectedKeys.isNotEmpty() && !showBusy,
                                onClick = onSecondaryAction
                            ) {
                                Text(secondaryActionLabel)
                            }
                        }
                        HapticTextButton(
                            enabled = selectedKeys.isNotEmpty() && !showBusy,
                            onClick = onImport
                        ) {
                            Text(resolvedActionLabel)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (isScanning && songs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.download_scanning),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(resolvedSearchPlaceholder)
                    },
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HapticTextButton(
                        enabled = displayedItems.isNotEmpty(),
                        onClick = {
                            onSelectedKeysChange(
                                if (allDisplayedSelected) {
                                    selectedKeys - displayedKeys
                                } else {
                                    selectedKeys + displayedKeys
                                }
                            )
                        }
                    ) {
                        Text(
                            if (allDisplayedSelected) {
                                stringResource(R.string.action_deselect_all)
                            } else {
                                stringResource(R.string.action_select_all)
                            }
                        )
                    }
                    HapticTextButton(
                        enabled = displayedItems.isNotEmpty(),
                        onClick = {
                            onSelectedKeysChange(
                                selectedKeys
                                    .subtract(displayedKeys)
                                    .plus(displayedKeys - selectedKeys)
                            )
                        }
                    ) {
                        Text(stringResource(R.string.action_inverse_select))
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (displayedItems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resolvedEmptyText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = displayedItems,
                            key = { _, item -> item.stableKey },
                            contentType = { _, _ -> "local_scan_preview_song" }
                        ) { _, item ->
                            val selected = item.stableKey in selectedKeys
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            onSelectedKeysChange(
                                                if (selected) {
                                                    selectedKeys - item.stableKey
                                                } else {
                                                    selectedKeys + item.stableKey
                                                }
                                            )
                                        }
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        onSelectedKeysChange(
                                            if (selected) {
                                                selectedKeys - item.stableKey
                                            } else {
                                                selectedKeys + item.stableKey
                                            }
                                        )
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (item.filePath.isNotBlank()) {
                                        Text(
                                            text = item.filePath,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
