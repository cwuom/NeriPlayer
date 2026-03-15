package moe.ouom.neriplayer.ui.screen.playlist

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.outlined.DownloadDone
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.DelicateCoroutinesApi
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.FavoritesPlaylist
import moe.ouom.neriplayer.data.LocalFilesPlaylist
import moe.ouom.neriplayer.data.LocalMediaSupport
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.data.SystemLocalPlaylists
import moe.ouom.neriplayer.data.displayAlbum
import moe.ouom.neriplayer.data.displayArtist
import moe.ouom.neriplayer.data.displayCoverUrl
import moe.ouom.neriplayer.data.displayName
import moe.ouom.neriplayer.data.identity
import moe.ouom.neriplayer.data.isLocalSong
import moe.ouom.neriplayer.data.sameIdentityAs
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.viewmodel.DownloadManagerViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)
@Composable
fun LocalPlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val vm: LocalPlaylistDetailViewModel = viewModel()
    val ui = vm.uiState.collectAsState()
    val scanPreviewState by vm.scanPreviewState.collectAsState()
    LaunchedEffect(playlistId) { vm.start(playlistId) }

    // 保存最新的歌单数据，用于在Screen销毁时更新使用记录
    var latestPlaylist by remember { mutableStateOf<moe.ouom.neriplayer.data.LocalPlaylist?>(null) }
    LaunchedEffect(ui.value.playlist) {
        ui.value.playlist?.let { latestPlaylist = it }
    }

    // 在Screen销毁时更新使用记录，确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
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

    val playlistOrNull = ui.value.playlist
    val isResolved = ui.value.isResolved

    LaunchedEffect(isResolved, playlistOrNull, playlistId) {
        if (isResolved && playlistOrNull == null) {
            AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
            onDeleted()
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
            if (playlistOrNull == null) {
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
            val playlist = playlistOrNull
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
            val exportSheetState = rememberModalBottomSheetState()

            var showSearch by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            var showDownloadManager by remember { mutableStateOf(false) }
            val searchFocusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            
            // 下载进度
            val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
            val downloadedSongs by GlobalDownloadManager.downloadedSongs.collectAsState()

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
                    val message = when {
                        result.importedCount > 0 && result.failedCount > 0 -> {
                            context.getString(
                                R.string.local_playlist_import_audio_partial,
                                result.importedCount,
                                result.failedCount
                            )
                        }
                        result.importedCount > 0 -> {
                            context.getString(
                                R.string.local_playlist_import_audio_success,
                                result.importedCount
                            )
                        }
                        else -> {
                            context.getString(
                                R.string.local_playlist_import_audio_failed,
                                result.failedCount
                            )
                        }
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }

            fun startDeviceAudioScan() {
                detailSong = null
                vm.scanDeviceSongs { result ->
                    scope.launch {
                        if (!result.completed) {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.local_playlist_scan_preserve_existing)
                            )
                            return@launch
                        }

                        if (result.failedCount > 0) {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.download_scan_failed, result.failedCount)
                            )
                        }
                    }
                }
            }

            fun dismissScanPreviewPage(cancelScan: Boolean = true) {
                vm.clearScanPreview(cancelScan = cancelScan)
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

            // 可变列表：保持存储层顺序（正序），UI 用 asReversed() 倒序展示
            val localSongs = remember(playlistId) {
                mutableStateListOf<SongItem>().also { it.addAll(playlist.songs) }
            }

            // 阻断 VM->UI 同步；同时用 pendingOrderKeys 兼容重排和批删
            var blockSync by remember(playlistId) { mutableStateOf(false) }
            var pendingOrderKeys by remember(playlistId) { mutableStateOf<List<String>?>(null) }
            LaunchedEffect(playlist.songs, blockSync, pendingOrderKeys) {
                val repoKeys = playlist.songs.map { it.stableKey() }
                val wanted = pendingOrderKeys
                if (!blockSync) {
                    localSongs.clear()
                    localSongs.addAll(playlist.songs)
                } else if (wanted != null && wanted == repoKeys) {
                    localSongs.clear()
                    localSongs.addAll(playlist.songs)
                    pendingOrderKeys = null
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

            fun selectAll() {
                selectedKeysState.value = localSongs.map { it.stableKey() }.toSet()
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

            fun handleNeteaseSyncResult(result: moe.ouom.neriplayer.data.NeteaseLikeSyncResult) {
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
            var renameText by remember {
                mutableStateOf(TextFieldValue(playlist.name.take(LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH)))
            }
            var renameError by remember { mutableStateOf<String?>(null) }
            val maxNameLength = LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH
            fun validateRename(input: String): String? {
                val name = input.trim().take(maxNameLength)
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
                renameError = validateRename(renameText.text)
                AlertDialog(
                    onDismissRequest = { showRename = false },
                    confirmButton = {
                        val trimmed = renameText.text.trim().take(maxNameLength)
                        val disabled =
                            renameError != null || trimmed.equals(playlist.name, ignoreCase = true)
                        HapticTextButton(
                            onClick = {
                                if (!disabled) {
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
                                val limited = it.text.take(maxNameLength)
                                renameText = it.copy(
                                    text = limited,
                                    selection = TextRange(limited.length)
                                )
                                renameError = validateRename(limited)
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
                    pendingOrderKeys = localSongs.map { it.stableKey() }
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
            val displayedSongs by remember {
                derivedStateOf {
                    val base = localSongs.asReversed()
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
                    val key = savedListKey.value
                    val targetIndex = when {
                        key == null -> null
                        key == headerKey -> 0
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
            val totalDurationMs by remember(playlistId) {
                derivedStateOf { localSongs.sumOf { it.durationMs } }
            }

            // 当前播放 & FAB
            val currentSong by PlayerManager.currentSongFlow.collectAsState()
            val currentIndexInSource = localSongs.indexOfFirst { it.sameIdentityAs(currentSong) }
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
                    selectedKeys = scanPreviewState.selectedKeys,
                    onSelectedKeysChange = vm::updateScanPreviewSelection,
                    snackbarHostState = snackbarHostState,
                    onBack = ::dismissScanPreviewPage,
                    onImport = {
                        val selectedSongs = scanPreviewState.songs.filter {
                            it.stableKey() in scanPreviewState.selectedKeys
                        }
                        vm.applyScannedSongs(selectedSongs, ::showAudioImportResult)
                        dismissScanPreviewPage(cancelScan = false)
                    }
                )
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
                                
                                if (batchDownloadProgress != null) {
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
                                        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                requiredAudioPermission
                                            ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            startDeviceAudioScan()
                                        } else {
                                            audioPermissionLauncher.launch(requiredAudioPermission)
                                        }
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
                                        renameText = TextFieldValue(playlist.name)
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
                        val allSelected =
                            selectedKeysState.value.size == localSongs.size && localSongs.isNotEmpty()
                        TopAppBar(
                            title = { Text(stringResource(R.string.common_selected_count, selectedKeysState.value.size)) },
                            navigationIcon = {
                                HapticIconButton(onClick = { exitSelectionMode() }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_exit_select)
                                    )
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
                                    val headerContext = LocalContext.current
                                    val baseQueue = localSongs.asReversed()
                                    val headerCover =
                                        baseQueue.firstOrNull { !it.displayCoverUrl(headerContext).isNullOrBlank() }
                                            ?.displayCoverUrl(headerContext)
                                    AsyncImage(
                                        model = offlineCachedImageRequest(headerContext, headerCover),
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

                            // 列表（倒序）
                            itemsIndexed(
                                items = displayedSongs,
                                key = { _, song -> song.stableKey() }
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
                                                        val baseQueue = localSongs.asReversed()
                                                        val pos =
                                                            baseQueue.indexOfFirst { it.sameIdentityAs(song) }
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
                                            val displayCoverUrl = song.displayCoverUrl(itemContext)
                                            if (!displayCoverUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = offlineCachedImageRequest(itemContext, displayCoverUrl),
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
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = song.displayName(),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    // 下载完成标志
                                                    if (remember(downloadedSongs, song, itemContext) {
                                                            AudioDownloadManager.getLocalFilePath(
                                                                itemContext,
                                                                song
                                                            ) != null
                                                        }) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.DownloadDone,
                                                            contentDescription = stringResource(R.string.downloaded),
                                                            modifier = Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = song.displayArtist(),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                                        val shared = runCatching {
                                                                            LocalMediaSupport.shareSongFile(context, song)
                                                                        }.getOrElse { false }
                                                                        if (!shared) {
                                                                            scope.launch {
                                                                                snackbarHostState.showSnackbar(
                                                                                    context.getString(R.string.local_song_share_failed)
                                                                                )
                                                                            }
                                                                        }
                                                                        showMoreMenu = false
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
                                vm.delete { ok -> if (ok) onDeleted() }
                                showDeletePlaylistConfirm = false
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
                        text = { Text(stringResource(R.string.local_playlist_delete_songs_confirm, count)) },
                        confirmButton = {
                            HapticTextButton(onClick = {
                                val songsToRemove = localSongs.filter {
                                    it.stableKey() in selectedKeysState.value
                                }
                                val expected = localSongs
                                    .filterNot { it.stableKey() in selectedKeysState.value }
                                    .map { it.stableKey() }
                                pendingOrderKeys = expected
                                blockSync = true

                                localSongs.removeAll { it.stableKey() in selectedKeysState.value }
                                showDeleteMultiConfirm = false
                                exitSelectionMode()

                                vm.removeSongs(songsToRemove)
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
                    ModalBottomSheet(
                        onDismissRequest = { showExportSheet = false },
                        sheetState = exportSheetState
                    ) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text(stringResource(R.string.local_playlist_export_to), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))

                            LazyColumn {
                    itemsIndexed(
                        allPlaylists.filter {
                            it.id != playlist.id && !LocalFilesPlaylist.isSystemPlaylist(it, context)
                        }
                    ) { _, pl ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp)
                                            .combinedClickable(onClick = {
                                                context.performHapticFeedback()
                                                val songs = localSongs.filter {
                                                    it.stableKey() in selectedKeysState.value
                                                }
                                                launchWithLocalSyncWarning(
                                                    songs = songs,
                                                    actionLabel = context.getString(R.string.playlist_add_to)
                                                ) {
                                                    scope.launch {
                                                        repo.addSongsToPlaylist(pl.id, songs)
                                                        showExportSheet = false
                                                    }
                                                }
                                            }),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            stringResource(R.string.explore_song_count, pl.songs.size),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
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
                                    enabled = newName.isNotBlank() && selectedKeysState.value.isNotEmpty(),
                                    onClick = {
                                        val name = newName.trim()
                                        if (name.isBlank()) return@HapticTextButton
                                        val displayedSongs = localSongs.asReversed()
                                        val songs = displayedSongs.filter {
                                            it.stableKey() in selectedKeysState.value
                                        }
                                        launchWithLocalSyncWarning(
                                            songs = songs,
                                            actionLabel = context.getString(R.string.playlist_add_to)
                                        ) {
                                            scope.launch {
                                                repo.createPlaylist(name)
                                                val target =
                                                    repo.playlists.value.lastOrNull { it.name == name }
                                                if (target != null) {
                                                    repo.addSongsToPlaylist(target.id, songs)
                                                }
                                                showExportSheet = false
                                            }
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
                                HapticIconButton(
                                    onClick = { showDownloadManager = false }
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_close)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            batchDownloadProgress?.let { progress ->
                                // 下载进度显示
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                stringResource(R.string.bili_download_progress_format, progress.completedSongs, progress.totalSongs),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            HapticTextButton(
                                                onClick = {
                                                    AudioDownloadManager.cancelDownload()
                                                }
                                            ) {
                                                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                                            }
                                        }

                                        if (progress.currentSong.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                stringResource(R.string.settings_downloading, progress.currentSong),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        // 总体进度条
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
                                        
                                        // 单首歌曲进度条
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
                                // 没有下载任务时的显示
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

private data class LocalScanPreviewItem(
    val song: SongItem,
    val stableKey: String,
    val fileName: String,
    val filePath: String,
    val subtitle: String
)

private fun SongItem.toLocalScanPreviewItem(): LocalScanPreviewItem {
    val resolvedPath = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?: mediaUri?.takeIf { it.startsWith("/") }
        ?: mediaUri.orEmpty()
    val resolvedFileName = localFilePath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.name
        ?: localFileName?.takeIf { it.isNotBlank() }
        ?: name
    val subtitle = buildList {
        name.takeIf { it.isNotBlank() && it != resolvedFileName }?.let(::add)
        artist.takeIf { it.isNotBlank() }?.let(::add)
        album.takeIf { it.isNotBlank() }?.let(::add)
        durationMs.takeIf { it > 0L }?.let { add(formatDuration(it)) }
    }.joinToString(" · ")
    return LocalScanPreviewItem(
        song = this,
        stableKey = stableKey(),
        fileName = resolvedFileName,
        filePath = resolvedPath,
        subtitle = subtitle
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LocalScanPreviewScreen(
    isScanning: Boolean,
    songs: List<SongItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedKeys: Set<String>,
    onSelectedKeysChange: (Set<String>) -> Unit,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    title: String? = null,
    actionLabel: ((Int) -> String)? = null,
    searchPlaceholder: String? = null,
    emptyText: String? = null,
    isBusy: Boolean = false
) {
    val context = LocalContext.current
    val previewItems = remember(songs) { songs.map { it.toLocalScanPreviewItem() } }
    val listState = rememberLazyListState()
    val displayedItems = remember(previewItems, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            previewItems
        } else {
            previewItems.filter { item ->
                item.fileName.contains(keyword, ignoreCase = true) ||
                    item.filePath.contains(keyword, ignoreCase = true) ||
                    item.song.displayName().contains(keyword, ignoreCase = true) ||
                    item.song.displayArtist().contains(keyword, ignoreCase = true) ||
                    item.song.displayAlbum(context).contains(keyword, ignoreCase = true)
            }
        }
    }
    val allDisplayedSelected = displayedItems.isNotEmpty() &&
        displayedItems.all { it.stableKey in selectedKeys }
    val resolvedTitle = title ?: stringResource(R.string.local_playlist_scan_preview_title)
    val resolvedSearchPlaceholder =
        searchPlaceholder ?: stringResource(R.string.local_playlist_scan_preview_search)
    val resolvedEmptyText = emptyText ?: stringResource(R.string.download_scan_empty)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_selected_count, selectedKeys.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        HapticTextButton(
                            enabled = selectedKeys.isNotEmpty() && !isBusy,
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
                                    selectedKeys - displayedItems.map { it.stableKey }.toSet()
                                } else {
                                    selectedKeys + displayedItems.map { it.stableKey }.toSet()
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
                            val displayedKeys = displayedItems.map { it.stableKey }.toSet()
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
                        itemsIndexed(displayedItems, key = { _, item -> item.stableKey }) { _, item ->
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
                                        text = item.fileName,
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
