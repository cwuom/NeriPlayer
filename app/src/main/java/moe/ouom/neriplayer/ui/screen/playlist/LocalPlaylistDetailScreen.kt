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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportResult
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioScanPhase
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioScanProgress
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSongDeleteResult
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseLikeSyncResult
import moe.ouom.neriplayer.data.local.playlist.sync.NeteaseRemotePlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.isSyncableRemoteSong
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.rememberMainTabDetailVisibilityState
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedSongs
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedPlaylist
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportFailure
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistDeleteResultGlobally
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistSongDeleteResult
import moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.download.SongDownloadSubtitle
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.dismissCurrentNeriSnackbar
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalPlaylistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.playlist.LocalMetadataProcessingState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.format.formatTotalDuration
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.util.search.playlistSearchValues
import moe.ouom.neriplayer.util.search.SearchTextMatcher
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.io.File
import java.util.LinkedHashMap
import kotlin.random.Random

internal enum class LocalFilesSongTab {
    MANUALLY_ADDED,
    DOWNLOADED
}

internal class SongIdentityLookup(songs: List<SongItem>) {
    private val identities = HashSet<SongIdentity>(songs.size)
    private val localSourceKeys = HashSet<String>()

    init {
        songs.forEach { song ->
            identities += song.identity()
            if (song.isLocalSong()) {
                localSourceKeys += LocalSongSupport.localDuplicateKeys(song)
            }
        }
    }

    fun contains(song: SongItem): Boolean {
        if (identities.isEmpty() && localSourceKeys.isEmpty()) return false
        if (song.identity() in identities) return true
        if (!song.isLocalSong() || localSourceKeys.isEmpty()) return false
        return LocalSongSupport.localDuplicateKeys(song).any(localSourceKeys::contains)
    }
}

internal fun localFilesSongsForTab(
    manuallyAddedSongs: List<SongItem>,
    downloadedSongs: List<SongItem>,
    tab: LocalFilesSongTab
): List<SongItem> {
    return when (tab) {
        LocalFilesSongTab.MANUALLY_ADDED -> manuallyAddedSongs
        LocalFilesSongTab.DOWNLOADED -> downloadedSongs
    }
}

internal const val BLANK_COVER_MODEL = "about:blank"

internal fun playlistNameFieldValue(text: String, maxLength: Int): TextFieldValue {
    val limited = text.take(maxLength)
    return TextFieldValue(
        text = limited,
        selection = TextRange(limited.length)
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

internal fun selectedSongsInSourceOrder(
    songs: List<SongItem>,
    selectedKeys: Set<String>
): List<SongItem> {
    return songs.filter { it.stableKey() in selectedKeys }
}

internal fun retainExistingSongSelectionKeys(
    songs: List<SongItem>,
    selectedKeys: Set<String>
): Set<String> {
    if (selectedKeys.isEmpty()) return emptySet()
    val existingKeys = songs.mapTo(HashSet(songs.size)) { it.stableKey() }
    return selectedKeys.intersect(existingKeys)
}

internal fun <T> snapshotDisplayOrderList(items: List<T>): List<T> {
    return items.toList()
}

internal fun selectedStoredLocalSongsForExport(
    storedSongs: List<SongItem>,
    selectedKeys: Set<String>
): List<SongItem> {
    return storedSongs.filter { it.stableKey() in selectedKeys }
}

internal fun SongItem.optimisticPlaylistInsertKeys(): Set<String> {
    return buildSet {
        add("identity:${stableKey()}")
        LocalSongSupport.localDuplicateKeys(
            song = this@optimisticPlaylistInsertKeys,
            includeMetadataFallback = true
        ).forEach { key -> add("local:$key") }
    }
}

internal fun normalizeLocalPlaylistHeaderCoverModel(headerCover: String?): String {
    return headerCover?.trim()?.takeIf { it.isNotEmpty() } ?: BLANK_COVER_MODEL
}

internal fun shouldResolveLocalPlaylistHeaderCoverFallback(
    isListArtworkIdle: Boolean
): Boolean = isListArtworkIdle

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

internal fun shouldHandleMissingLocalPlaylistAsDeleted(
    uiState: LocalPlaylistDetailUiState
): Boolean {
    return uiState.isResolved && uiState.playlist == null && !uiState.initializationFailed
}

internal data class PendingNeteaseRemotePlaylistSync(
    val songs: List<SongItem>,
    val unsupportedCount: Int,
    val target: NeteaseRemotePlaylist
)

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
    val playlistPlayCount by produceState(initialValue = 0L, key1 = playlistId) {
        val statsRepository = withContext(Dispatchers.IO) {
            AppContainer.localPlaylistPlaybackStatsRepo
        }
        statsRepository.statsFlow.collect { stats ->
            value = stats
                .firstOrNull { stat -> stat.playlistId == playlistId }
                ?.totalPlayCount
                ?: 0L
        }
    }
    val scanPreviewState by vm.scanPreviewState.collectAsState()
    val metadataProcessingState by vm.metadataProcessingState.collectAsState()
    val downloadedSongs by GlobalDownloadManager.downloadedSongs.collectAsState()
    val downloadedPlaybackCoverCandidates = remember(downloadedSongs) {
        downloadedSongs.map { it.toPlaybackSongItem() }
    }
    val latestDownloadedPlaybackCoverCandidates by rememberUpdatedState(
        downloadedPlaybackCoverCandidates
    )
    val visibleMetadataProcessingState = metadataProcessingState
        .takeIf { it.playlistId == playlistId }
        ?: LocalMetadataProcessingState()
    LaunchedEffect(playlistId) { vm.start(playlistId) }

    // 保存最新的歌单数据, 用于在Screen销毁时更新使用记录
    var latestPlaylist by remember { mutableStateOf<moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist?>(null) }
    var playlistDeleted by remember(playlistId) { mutableStateOf(false) }
    LaunchedEffect(uiState.playlist) {
        uiState.playlist?.let { latestPlaylist = it }
    }

    // 在Screen销毁时更新使用记录, 确保返回主页时卡片显示最新信息
    DisposableEffect(Unit) {
        onDispose {
            if (playlistDeleted) return@onDispose
            latestPlaylist?.let { playlist ->
                AppContainer.launchBackgroundIo {
                    AppContainer.playlistUsageRepo.updateInfo(
                        id = playlist.id,
                        name = playlist.name,
                        picUrl = playlist.displayCoverUrl(
                            context = context,
                            additionalCoverCandidates = if (LocalFilesPlaylist.isSystemPlaylist(
                                    playlist,
                                    context
                                )
                            ) {
                                latestDownloadedPlaybackCoverCandidates
                            } else {
                                emptyList()
                            }
                        ),
                        trackCount = playlist.songs.size,
                        source = "local"
                    )
                }
            }
        }
    }

    val playlist = uiState.playlist
    val isResolved = uiState.isResolved
    val initializationFailed = uiState.initializationFailed
    var deleteNavigationHandled by remember(playlistId) { mutableStateOf(false) }

    fun navigateAfterPlaylistDeleted() {
        if (deleteNavigationHandled) return
        deleteNavigationHandled = true
        playlistDeleted = true
        onDeleted()
    }

    LaunchedEffect(isResolved, initializationFailed, playlist, playlistId) {
        if (shouldHandleMissingLocalPlaylistAsDeleted(uiState)) {
            playlistDeleted = true
            withContext(Dispatchers.IO) {
                AppContainer.playlistUsageRepo.removeEntry(playlistId, "local")
            }
            navigateAfterPlaylistDeleted()
        }
    }

    val detailVisibilityState = rememberMainTabDetailVisibilityState(playlistId)
    AnimatedVisibility(
        visibleState = detailVisibilityState,
        enter = slideInVertically(
            tween(300, easing = FastOutSlowInEasing),
            initialOffsetY = { it }
        ) + fadeIn(tween(150)),
        exit = slideOutVertically(
            tween(250, easing = FastOutSlowInEasing),
            targetOffsetY = { it }) + fadeOut(tween(150))
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            if (playlist == null) {
                if (isResolved && !initializationFailed) {
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
                        if (initializationFailed) {
                            Text(
                                text = stringResource(
                                    R.string.playlist_load_failed_format,
                                    stringResource(R.string.local_playlist_initialization_failed)
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                return@Surface
            }

            val context = LocalContext.current
            val composeResources = LocalResources.current
            val clipboard = LocalClipboard.current
            val isFavorites = FavoritesPlaylist.isSystemPlaylist(playlist, context)
            val isLocalFilesPlaylist = LocalFilesPlaylist.isSystemPlaylist(playlist, context)
            val isSystemPlaylist = isFavorites || isLocalFilesPlaylist
            val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
            val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
            val shuffleEnabled by PlayerManager.shuffleModeFlow.collectAsState()
            val repeatMode by PlayerManager.repeatModeFlow.collectAsState()

            val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
            val allPlaylists by repo.playlists.collectAsState()
            val favoriteSongs = remember(allPlaylists, context) {
                FavoritesPlaylist.firstOrNull(allPlaylists, context)?.songs.orEmpty()
            }
            val favoriteSongLookup = remember(favoriteSongs) {
                SongIdentityLookup(favoriteSongs)
            }
            val scope = rememberCoroutineScope()
            var syncInProgress by remember { mutableStateOf(false) }
            var showNeteaseSyncConfirm by remember { mutableStateOf(false) }
            var showNeteaseSyncPreview by remember { mutableStateOf(false) }
            var neteaseSyncPreviewSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
            var neteaseSyncPreviewQuery by rememberSaveable { mutableStateOf("") }
            var neteaseSyncSelectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
            var showNeteaseRemotePlaylistPicker by remember { mutableStateOf(false) }
            var neteaseRemotePlaylists by remember {
                mutableStateOf<List<NeteaseRemotePlaylist>>(emptyList())
            }
            var neteaseRemotePlaylistsLoading by remember { mutableStateOf(false) }
            var neteaseRemotePlaylistsError by remember { mutableStateOf<String?>(null) }
            var neteaseRemotePlaylistsLoadJob by remember { mutableStateOf<Job?>(null) }
            var neteaseRemotePlaylistsRequestGeneration by remember { mutableIntStateOf(0) }
            var pendingNeteaseRemoteSyncSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
            var pendingNeteaseRemoteSyncConfirm by remember {
                mutableStateOf<PendingNeteaseRemotePlaylistSync?>(null)
            }



            var showDeletePlaylistConfirm by remember { mutableStateOf(false) }
            var showDeleteMultiConfirm by remember { mutableStateOf(false) }
            var showExportSheet by remember { mutableStateOf(false) }
            var showExportAllSheet by remember { mutableStateOf(false) }
            var detailSong by remember { mutableStateOf<SongItem?>(null) }
            var pendingSyncConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var pendingSyncConfirmLabel by remember { mutableStateOf("") }

            var showSearch by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            var headerSearchFocused by remember { mutableStateOf(false) }
            var dockedSearchFocused by remember { mutableStateOf(false) }
            val searchInputState = rememberPlaylistSearchInputState(
                query = searchQuery,
                onQueryChange = { searchQuery = it }
            )
            var showDownloadManager by remember { mutableStateOf(false) }
            var showLocalScanModeDialog by remember { mutableStateOf(false) }
            var showScanPlaylistExportSheet by remember { mutableStateOf(false) }
            val searchFocusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            
            // 下载进度
            val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
            val hasDownloadManagerEntry = downloadTaskSummary.hasDownloadManagerEntry

            // Snackbar状态
            val snackbarHostState = remember { SnackbarHostState() }
            val favoriteAddedText = stringResource(R.string.favorite_added)
            val favoriteRemovedText = stringResource(R.string.favorite_removed)
            fun toggleSongFavorite(song: SongItem, isFavoriteSong: Boolean) {
                val message = if (isFavoriteSong) favoriteRemovedText else favoriteAddedText
                scope.launchLocalPlaylistMutation(
                    operation = "toggleLocalDetailSongFavorite",
                    onResult = { result ->
                        if (result.isSuccess) {
                            scope.launch {
                                snackbarHostState.showNeriSnackbar(message)
                            }
                        }
                    }
                ) {
                    if (isFavoriteSong) {
                        repo.removeFromFavorites(song)
                    } else {
                        repo.addToFavorites(song)
                    }
                }
            }
            val requiredAudioPermission = remember {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            }

            fun showAudioImportResult(result: moe.ouom.neriplayer.ui.viewmodel.playlist.LocalAudioImportUiResult) {
                if (result.addedSongs.isNotEmpty()) {
                    scope.showPlaylistBatchExportAddedSongs(
                        context = context,
                        snackbarHostState = snackbarHostState,
                        repository = repo,
                        targetPlaylistId = LocalFilesPlaylist.SYSTEM_ID,
                        targetPlaylistName = composeResources.getString(R.string.local_files),
                        addedSongs = result.addedSongs
                    )
                    return
                }
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
                            composeResources.getString(R.string.local_playlist_import_audio_no_new)
                        }
                        else -> {
                            resources.getQuantityString(
                                R.plurals.local_playlist_import_audio_failed,
                                result.failedCount,
                                result.failedCount
                            )
                        }
                    }
                    snackbarHostState.showNeriSnackbar(message)
                }
            }

            fun showScannedPlaylistAddResult(
                result: moe.ouom.neriplayer.ui.viewmodel.playlist.LocalAudioImportUiResult,
                targetPlaylistId: Long? = null,
                targetPlaylistName: String? = null
            ) {
                if (result.failedCount > 0) {
                    scope.showPlaylistBatchExportFailure(context, snackbarHostState)
                    return
                }
                result.createdPlaylist?.let { createdPlaylist ->
                    scope.showPlaylistBatchExportCreatedPlaylist(
                        context = context,
                        snackbarHostState = snackbarHostState,
                        repository = repo,
                        playlist = createdPlaylist
                    )
                    return
                }
                if (targetPlaylistId != null && targetPlaylistName != null) {
                    scope.showPlaylistBatchExportAddedSongs(
                        context = context,
                        snackbarHostState = snackbarHostState,
                        repository = repo,
                        targetPlaylistId = targetPlaylistId,
                        targetPlaylistName = targetPlaylistName,
                        addedSongs = result.addedSongs
                    )
                    return
                }
                scope.launch {
                    val message = if (result.importedCount > 0) {
                        context.resources.getQuantityString(
                            R.plurals.local_playlist_add_scanned_success,
                            result.importedCount,
                            result.importedCount
                        )
                    } else {
                        composeResources.getString(R.string.local_playlist_add_scanned_no_new)
                    }
                    snackbarHostState.showNeriSnackbar(message)
                }
            }

            fun handleLocalAudioScanResult(result: LocalAudioImportResult) {
                scope.launch {
                    if (!result.completed) {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(R.string.local_playlist_scan_preserve_existing)
                        )
                        return@launch
                    }

                    if (result.failedCount > 0) {
                        snackbarHostState.showNeriSnackbar(
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
                snackbarHostState.dismissCurrentNeriSnackbar()
                detailSong = null
                vm.scanDeviceSongs(::handleLocalAudioScanResult)
            }

            fun startFolderAudioScan(folderUri: Uri) {
                snackbarHostState.dismissCurrentNeriSnackbar()
                detailSong = null
                vm.scanFolderSongs(folderUri, ::handleLocalAudioScanResult)
            }

            fun dismissScanPreviewPage(cancelScan: Boolean = true) {
                showScanPlaylistExportSheet = false
                vm.clearScanPreview(cancelScan = cancelScan)
            }

            val folderScanContract = remember {
                object : ActivityResultContracts.OpenDocumentTree() {
                    override fun createIntent(context: Context, input: Uri?): Intent {
                        return super.createIntent(context, input).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        )
                    }
                }
            }
            val folderScanLauncher = rememberLauncherForActivityResult(
                contract = folderScanContract
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                val persistGranted = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }.isSuccess
                if (!persistGranted) {
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(
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
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(R.string.download_scan_permission_required)
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

            // 可变列表保持展示顺序, 数据层会负责兼容旧版本存储
            val localSongs = remember(playlistId) {
                mutableStateListOf<SongItem>().also { it.addAll(playlist.songs) }
            }

            // 阻断 VM->UI 同步; 同时用 pendingOrderIdentities 兼容重排和批删
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

            fun handleLocalSongDeleteResult(
                previousSongs: List<SongItem>,
                result: Result<List<LocalPlaylistSongDeleteResult>>
            ) {
                val deleteResults = result.getOrNull().orEmpty()
                if (result.isFailure || deleteResults.isEmpty()) {
                    localSongs.clear()
                    localSongs.addAll(previousSongs)
                    pendingOrderIdentities = null
                    blockSync = false
                }
                scope.showPlaylistSongDeleteResult(
                    context = context,
                    snackbarHostState = snackbarHostState,
                    repository = repo,
                    result = result
                )
            }

            // 多选
            var selectionMode by remember(playlistId) { mutableStateOf(false) }
            val selectedKeysState = remember(playlistId) { mutableStateOf<Set<String>>(emptySet()) }

            fun toggleSelect(songKey: String) {
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
                if (songs.any { !it.isSyncableRemoteSong(context) }) {
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
                    song
                }.mapIndexed { index, song ->
                    song.copy(addedAt = (now - index).coerceAtLeast(1L))
                }
                if (additions.isNotEmpty()) {
                    localSongs.addAll(0, additions)
                }
            }

            fun handleNeteaseSyncResult(
                result: NeteaseLikeSyncResult,
                unsupportedCount: Int = 0,
                targetPlaylistName: String? = null
            ) {
                syncInProgress = false
                val syncMessage = result.message ?: if (result.totalSongs == 0) {
                    composeResources.getString(R.string.local_playlist_sync_netease_empty)
                } else {
                    composeResources.getString(
                        R.string.local_playlist_sync_netease_result,
                        result.totalSongs,
                        result.added,
                        result.skippedExisting,
                        result.skippedUnsupported,
                        result.failed
                    )
                }
                val unsupportedMessage = if (unsupportedCount > 0) {
                    context.resources.getQuantityString(
                        R.plurals.local_playlist_sync_netease_unsupported,
                        unsupportedCount,
                        unsupportedCount
                    )
                } else {
                    null
                }
                val targetMessage = targetPlaylistName?.let {
                    composeResources.getString(
                        R.string.local_playlist_sync_netease_target,
                        it
                    )
                }
                val message = listOfNotNull(targetMessage, syncMessage, unsupportedMessage)
                    .joinToString(" ")
                scope.launch {
                    snackbarHostState.showNeriSnackbar(message)
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
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(R.string.local_playlist_sync_netease_empty)
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
                        snackbarHostState.showNeriSnackbar(
                            plan.message ?: composeResources.getString(R.string.local_playlist_sync_netease_all_synced)
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
            val backgroundImageUri by AppContainer.settingsRepo.backgroundImageUriFlow.collectAsState(initial = null)
            val hasCustomBackground = backgroundImageUri != null

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
                if (name.isEmpty()) return composeResources.getString(R.string.playlist_name_empty)
                if (SystemLocalPlaylists.matchesReservedName(name, context)) {
                    val reservedName = SystemLocalPlaylists.resolve(
                        playlistId = 0L,
                        playlistName = name,
                        context = context
                    )?.currentName ?: name
                    return composeResources.getString(R.string.library_name_reserved, reservedName)
                }
                if (allPlaylists.any {
                        it.id != playlist.id && it.name.equals(
                            name,
                            ignoreCase = true
                        )
                    }) {
                    return composeResources.getString(R.string.library_name_exists)
                }
                return null
            }

            if (showRename) {
                MiuixSettingsDialog(
                    onDismissRequest = { showRename = false },
                    confirmButton = {
                        val trimmed = normalizedRenameName(renameText.text)
                        val disabled = renameError != null || isSameRenameName(renameText.text)
                        MiuixSettingsButton(
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
                        MiuixSettingsTextButton(onClick = {
                            showRename = false
                        }) { Text(stringResource(R.string.action_cancel)) }
                    },
                    text = {
                        MiuixSettingsDialogContent(verticalSpacing = 12.dp) {
                            MiuixSettingsTextField(
                                value = renameText.text,
                                onValueChange = {
                                    val limitedValue = playlistNameFieldValue(it, maxNameLength)
                                    renameText = limitedValue
                                    renameError = validateRename(limitedValue.text)
                                },
                                placeholder = { Text(playlist.name) },
                                singleLine = true
                            )
                            renameError?.let { error ->
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    title = { Text(stringResource(R.string.local_playlist_rename)) }
                )
            }

            val headerKey = LOCAL_PLAYLIST_HEADER_KEY
            val metadataProcessingVisible = visibleMetadataProcessingState.isProcessing
            var selectedLocalFilesTabIndex by rememberSaveable(playlistId) {
                mutableIntStateOf(0)
            }
            val selectedLocalFilesTab = if (
                selectedLocalFilesTabIndex == LocalFilesSongTab.DOWNLOADED.ordinal
            ) {
                LocalFilesSongTab.DOWNLOADED
            } else {
                LocalFilesSongTab.MANUALLY_ADDED
            }
            val canReorderCurrentSongs = !isLocalFilesPlaylist ||
                selectedLocalFilesTab == LocalFilesSongTab.MANUALLY_ADDED
            val canReorderCurrentSongsState = rememberUpdatedState(canReorderCurrentSongs)

            val reorderState = rememberReorderableLazyListState(
                onMove = { from: ItemPosition, to: ItemPosition ->
                    if (!canReorderCurrentSongsState.value) {
                        return@rememberReorderableLazyListState
                    }
                    if (!blockSync) blockSync = true
                    val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
                    val toKey = to.key as? String ?: return@rememberReorderableLazyListState
                    val fromIdx = localSongs.indexOfFirst { it.stableKey() == fromKey }
                    val toIdx = localSongs.indexOfFirst { it.stableKey() == toKey }
                    if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                        localSongs.add(toIdx, localSongs.removeAt(fromIdx))
                    }
                },
                canDragOver = { _, over ->
                    canReorderCurrentSongsState.value &&
                        (over.key as? String) !in LOCAL_PLAYLIST_FIXED_ITEM_KEYS
                },
                onDragEnd = { _, _ ->
                    if (!canReorderCurrentSongsState.value) {
                        return@rememberReorderableLazyListState
                    }
                    val newOrder = localSongs.map { it.identity() }
                    pendingOrderIdentities = newOrder
                    blockSync = true
                    scope.launch {
                        vm.reorderSongs(newOrder)
                    }
                }
            )

            // 记住滚动位置, 避免切换页面后回到顶部 (用稳定 key 防止列表变动导致错位)
            val savedListKey = rememberSaveable(playlistId) { mutableStateOf<String?>(null) }
            var savedListOffset by rememberSaveable(playlistId) { mutableIntStateOf(0) }
            val hasRestoredScroll = rememberSaveable(playlistId) { mutableStateOf(false) }
            val listState = reorderState.listState
            val isListScrolling by remember(listState) {
                derivedStateOf { listState.isScrollInProgress }
            }
            val isListArtworkIdle = rememberLocalPlaylistArtworkIdle(
                sessionKey = playlistId to selectedLocalFilesTab,
                isScrollInProgress = isListScrolling
            )
            val baseQueue by remember(localSongs) {
                derivedStateOf { snapshotDisplayOrderList(localSongs) }
            }
            val downloadedPlaybackSongs = remember(downloadedSongs) {
                downloadedSongs.map { it.toPlaybackSongItem() }
            }
            val downloadedSongsBySongKey = remember(downloadedSongs, downloadedPlaybackSongs) {
                buildMap {
                    downloadedSongs.forEachIndexed { index, downloadedSong ->
                        put(downloadedPlaybackSongs[index].stableKey(), downloadedSong)
                    }
                }
            }
            val downloadedSongKeys = remember(baseQueue, downloadedSongsBySongKey) {
                buildSet {
                    addAll(downloadedSongsBySongKey.keys)
                    baseQueue.forEach { song ->
                        if (GlobalDownloadManager.findDownloadedSongCached(song) != null) {
                            add(song.stableKey())
                        }
                    }
                }
            }
            val tabSongs = if (isLocalFilesPlaylist) {
                localFilesSongsForTab(
                    manuallyAddedSongs = baseQueue,
                    downloadedSongs = downloadedPlaybackSongs,
                    tab = selectedLocalFilesTab
                )
            } else {
                baseQueue
            }
            val queueIndexBySongKey by remember(tabSongs) {
                derivedStateOf {
                    buildMap(tabSongs.size) {
                        tabSongs.forEachIndexed { index, song ->
                            put(song.stableKey(), index)
                        }
                    }
                }
            }
            val displayOrderPlaylistForCover = remember(playlist, tabSongs) {
                playlist.copy(songs = tabSongs.toMutableList())
            }
            val resolveHeaderCoverFallback = shouldResolveLocalPlaylistHeaderCoverFallback(
                isListArtworkIdle
            )
            val headerCover = rememberPlaylistDisplayCoverUrl(
                playlist = displayOrderPlaylistForCover,
                resolveLocalFallback = resolveHeaderCoverFallback,
                allowEmbeddedCoverFallback = resolveHeaderCoverFallback
            )
            LaunchedEffect(headerCover, offlineMode, isListArtworkIdle) {
                if (!isListArtworkIdle) return@LaunchedEffect
                CoverArtColorCache.preload(context, headerCover, offlineMode)
            }
            val displayedSongs = rememberPlaylistSearchResults(
                query = searchQuery,
                items = tabSongs,
                tokens = { song -> song.playlistSearchValues(context) },
                buildIndex = shouldBuildPlaylistSearchIndex(
                    searchVisible = showSearch,
                    query = searchQuery
                )
            )

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
                        LOCAL_PLAYLIST_ACTIONS_KEY -> 1
                        LOCAL_PLAYLIST_METADATA_PROCESSING_KEY -> {
                            if (metadataProcessingVisible) 2 else null
                        }
                        else -> {
                            val idx = displayedSongs.indexOfFirst { it.stableKey() == key }
                            if (idx >= 0) {
                                resolveLocalPlaylistSongListIndex(
                                    songIndex = idx,
                                    metadataProcessingVisible = metadataProcessingVisible
                                )
                            } else {
                                null
                            }
                        }
                    }
                    if (targetIndex != null && (targetIndex != 0 || savedListOffset != 0)) {
                        listState.scrollToItem(targetIndex, savedListOffset)
                    }
                    hasRestoredScroll.value = true
                }
            }

            val totalDurationMs by remember(tabSongs) {
                derivedStateOf { tabSongs.sumOf { it.durationMs } }
            }
            val totalDurationText = if (tabSongs.any { it.durationMs <= 0L }) {
                stringResource(R.string.local_playlist_duration_loading)
            } else {
                formatTotalDuration(context, totalDurationMs)
            }
            val headerDisplayName = when {
                isFavorites -> stringResource(R.string.favorite_my_music)
                isLocalFilesPlaylist -> stringResource(R.string.local_files)
                else -> playlist.name
            }

            fun playPlaylist(shuffle: Boolean) {
                val startIndex = resolvePlaylistPlaybackStartIndex(
                    songCount = tabSongs.size,
                    shuffleEnabled = shuffle,
                    randomIndex = if (tabSongs.isEmpty()) 0 else Random.nextInt(tabSongs.size)
                )
                if (startIndex < 0) return
                PlayerManager.setShuffle(shuffle)
                onSongClick(tabSongs, startIndex)
            }

            // 当前播放 & FAB
            val currentSong by PlayerManager.currentSongFlow.collectAsState()
            val currentSongLookup = remember(currentSong) {
                SongIdentityLookup(listOfNotNull(currentSong))
            }
            val currentIndexInSource = remember(tabSongs, currentSong) {
                tabSongs.indexOfFirst { it.sameIdentityAs(currentSong) }
            }
            val currentIndexInDisplay = remember(
                currentIndexInSource,
                currentSong,
                displayedSongs
            ) {
                if (currentIndexInSource >= 0) {
                    displayedSongs.indexOfFirst { it.sameIdentityAs(currentSong) }
                } else {
                    -1
                }
            }
            val selectedSongsForAction by remember(tabSongs, selectedKeysState.value) {
                derivedStateOf {
                    selectedSongsInSourceOrder(tabSongs, selectedKeysState.value)
                }
            }
            val selectedDownloadedSongsForAction by remember(
                selectedSongsForAction,
                downloadedSongsBySongKey
            ) {
                derivedStateOf {
                    selectedSongsForAction
                        .mapNotNull { song -> downloadedSongsBySongKey[song.stableKey()] }
                        .distinct()
                }
            }
            fun dismissNeteaseRemotePlaylistPicker() {
                neteaseRemotePlaylistsRequestGeneration += 1
                neteaseRemotePlaylistsLoadJob?.cancel()
                neteaseRemotePlaylistsLoadJob = null
                neteaseRemotePlaylistsLoading = false
                showNeteaseRemotePlaylistPicker = false
            }

            fun startNeteaseRemotePlaylistSync(
                target: NeteaseRemotePlaylist,
                songs: List<SongItem>,
                unsupportedCount: Int
            ) {
                if (syncInProgress || songs.isEmpty()) return
                syncInProgress = true
                showNeteaseRemotePlaylistPicker = false
                pendingNeteaseRemoteSyncConfirm = null
                exitSelectionMode()
                vm.syncSongsToNeteasePlaylist(
                    targetPlaylistId = target.id,
                    songs = songs
                ) { result ->
                    handleNeteaseSyncResult(
                        result = result,
                        unsupportedCount = unsupportedCount,
                        targetPlaylistName = target.name
                    )
                }
            }

            fun selectNeteaseRemotePlaylist(target: NeteaseRemotePlaylist) {
                val selectedSongs = pendingNeteaseRemoteSyncSongs
                if (selectedSongs.isEmpty()) return
                val supportedSongs =
                    repo.filterNeteaseLikeSyncCandidatesPreservingDuplicates(selectedSongs)
                val unsupportedCount = selectedSongs.size - supportedSongs.size
                if (supportedSongs.isEmpty()) {
                    dismissNeteaseRemotePlaylistPicker()
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(
                                R.string.local_playlist_sync_netease_no_supported
                            )
                        )
                    }
                    return
                }
                dismissNeteaseRemotePlaylistPicker()
                if (unsupportedCount > 0) {
                    pendingNeteaseRemoteSyncConfirm = PendingNeteaseRemotePlaylistSync(
                        songs = supportedSongs,
                        unsupportedCount = unsupportedCount,
                        target = target
                    )
                } else {
                    startNeteaseRemotePlaylistSync(
                        target = target,
                        songs = supportedSongs,
                        unsupportedCount = 0
                    )
                }
            }

            fun openNeteaseRemotePlaylistPicker() {
                val selectedSongs = selectedSongsForAction
                if (selectedSongs.isEmpty() || syncInProgress) return
                val supportedSongs =
                    repo.filterNeteaseLikeSyncCandidatesPreservingDuplicates(selectedSongs)
                if (supportedSongs.isEmpty()) {
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(
                                R.string.local_playlist_sync_netease_no_supported
                            )
                        )
                    }
                    return
                }
                pendingNeteaseRemoteSyncSongs = selectedSongs
                neteaseRemotePlaylistsLoadJob?.cancel()
                val requestGeneration = neteaseRemotePlaylistsRequestGeneration + 1
                neteaseRemotePlaylistsRequestGeneration = requestGeneration
                neteaseRemotePlaylists = emptyList()
                neteaseRemotePlaylistsError = null
                neteaseRemotePlaylistsLoading = true
                showNeteaseRemotePlaylistPicker = true
                neteaseRemotePlaylistsLoadJob = vm.fetchNeteaseRemotePlaylists { result ->
                    if (requestGeneration == neteaseRemotePlaylistsRequestGeneration) {
                        neteaseRemotePlaylistsLoadJob = null
                        neteaseRemotePlaylistsLoading = false
                        result.onSuccess { playlists ->
                            neteaseRemotePlaylists = playlists
                            if (playlists.isEmpty()) {
                                neteaseRemotePlaylistsError = composeResources.getString(
                                    R.string.local_playlist_sync_netease_no_playlists
                                )
                            }
                        }.onFailure { error ->
                            neteaseRemotePlaylistsError = error.message
                                ?.takeIf(String::isNotBlank)
                                ?: composeResources.getString(
                                    R.string.local_playlist_sync_netease_load_failed
                                )
                        }
                    }
                }
            }

            LaunchedEffect(scanPreviewState.visible) {
                if (scanPreviewState.visible) {
                    snackbarHostState.dismissCurrentNeriSnackbar()
                }
            }

            if (scanPreviewState.visible) {
                LocalScanPreviewScreen(
                    isScanning = scanPreviewState.isScanning,
                    scanProgress = scanPreviewState.scanProgress,
                    songs = scanPreviewState.songs,
                    query = scanPreviewState.query,
                    onQueryChange = vm::updateScanPreviewQuery,
                    metadataOnly = scanPreviewState.metadataOnly,
                    onMetadataOnlyChange = vm::updateScanPreviewMetadataOnly,
                    hideExistingLocalPlaylistSongs = scanPreviewState.hideExistingLocalPlaylistSongs,
                    onHideExistingLocalPlaylistSongsChange =
                        vm::updateScanPreviewHideExistingLocalPlaylistSongs,
                    existingLocalPlaylistKeys = scanPreviewState.existingLocalPlaylistKeys,
                    hideDuplicateMetadataSongs = scanPreviewState.hideDuplicateMetadataSongs,
                    onHideDuplicateMetadataSongsChange =
                        vm::updateScanPreviewHideDuplicateMetadataSongs,
                    duplicateMetadataKeys = scanPreviewState.duplicateMetadataKeys,
                    metadataPendingKeys = scanPreviewState.metadataPendingKeys,
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
                        dismissScanPreviewPage(cancelScan = true)
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
                                actionLabel = composeResources.getString(R.string.playlist_add_to)
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
                                actionLabel = composeResources.getString(R.string.playlist_add_to)
                            ) {
                                appendSongsOptimistically(target.id, selectedSongs)
                                vm.addScannedSongsToPlaylist(
                                    targetPlaylistId = target.id,
                                    songs = selectedSongs,
                                    onResult = { result ->
                                        showScannedPlaylistAddResult(
                                            result = result,
                                            targetPlaylistId = target.id,
                                            targetPlaylistName = target.name
                                        )
                                    }
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
                        composeResources.getString(R.string.local_playlist_sync_selected, count)
                    },
                    searchPlaceholder = stringResource(R.string.local_playlist_sync_search),
                    emptyText = stringResource(R.string.local_playlist_sync_empty),
                    isBusy = syncInProgress
                )
                return@Surface
            }

            val modernContentScope = LocalPlaylistDetailModernContentScope(
                context = context,
                composeResources = composeResources,
                scope = scope,
                playlist = playlist,
                playlistId = playlistId,
                onBack = onBack,
                onSongClick = onSongClick,
                offlineMode = offlineMode,
                isFavorites = isFavorites,
                isLocalFilesPlaylist = isLocalFilesPlaylist,
                isSystemPlaylist = isSystemPlaylist,
                isPlaying = isPlaying,
                downloadPresenceVersion = downloadPresenceVersion,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                autoShowKeyboard = autoShowKeyboard,
                hasDownloadManagerEntry = hasDownloadManagerEntry,
                scanPreviewState = scanPreviewState,
                searchInputState = searchInputState,
                searchFocusRequester = searchFocusRequester,
                focusManager = focusManager,
                keyboardController = keyboardController,
                snackbarHostState = snackbarHostState,
                maxNameLength = maxNameLength,
                displayedSongs = displayedSongs,
                selectedKeysState = selectedKeysState,
                selectedSongsForAction = selectedSongsForAction,
                selectedDownloadedSongsForAction = selectedDownloadedSongsForAction,
                downloadedSongKeys = downloadedSongKeys,
                playlistPlayCount = playlistPlayCount,
                hasCustomBackground = hasCustomBackground,
                tabSongs = tabSongs,
                headerKey = headerKey,
                headerCover = headerCover,
                headerDisplayName = headerDisplayName,
                totalDurationText = totalDurationText,
                metadataProcessingVisible = metadataProcessingVisible,
                visibleMetadataProcessingState = visibleMetadataProcessingState,
                favoriteSongLookup = favoriteSongLookup,
                currentSongLookup = currentSongLookup,
                queueIndexBySongKey = queueIndexBySongKey,
                canReorderCurrentSongs = canReorderCurrentSongs,
                reorderState = reorderState,
                currentIndexInDisplay = currentIndexInDisplay,
                vm = vm,
                repo = repo,
                localSongs = localSongs,
                allPlaylists = allPlaylists,
                navigateAfterPlaylistDeleted = ::navigateAfterPlaylistDeleted,
                toggleSongFavorite = ::toggleSongFavorite,
                toggleSelect = ::toggleSelect,
                exitSelectionModeAction = ::exitSelectionMode,
                requestNeteaseSync = ::requestNeteaseSync,
                openNeteaseRemotePlaylistPickerAction = ::openNeteaseRemotePlaylistPicker,
                dismissNeteaseRemotePlaylistPickerAction = ::dismissNeteaseRemotePlaylistPicker,
                selectNeteaseRemotePlaylistAction = ::selectNeteaseRemotePlaylist,
                startNeteaseRemotePlaylistSyncAction = ::startNeteaseRemotePlaylistSync,
                handleLocalSongDeleteResult = ::handleLocalSongDeleteResult,
                launchWithLocalSyncWarningAction = ::launchWithLocalSyncWarning,
                openNeteaseSyncPreview = ::openNeteaseSyncPreview,
                playPlaylistAction = ::playPlaylist,
                copyText = { text ->
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
                    }
                },
                syncInProgressState = LocalPlaylistDetailMutableValue(
                    { syncInProgress }, { syncInProgress = it }
                ),
                showNeteaseSyncConfirmState = LocalPlaylistDetailMutableValue(
                    { showNeteaseSyncConfirm }, { showNeteaseSyncConfirm = it }
                ),
                showNeteaseRemotePlaylistPickerState = LocalPlaylistDetailMutableValue(
                    { showNeteaseRemotePlaylistPicker }, { showNeteaseRemotePlaylistPicker = it }
                ),
                neteaseRemotePlaylistsState = LocalPlaylistDetailMutableValue(
                    { neteaseRemotePlaylists }, { neteaseRemotePlaylists = it }
                ),
                neteaseRemotePlaylistsLoadingState = LocalPlaylistDetailMutableValue(
                    { neteaseRemotePlaylistsLoading }, { neteaseRemotePlaylistsLoading = it }
                ),
                neteaseRemotePlaylistsErrorState = LocalPlaylistDetailMutableValue(
                    { neteaseRemotePlaylistsError }, { neteaseRemotePlaylistsError = it }
                ),
                pendingNeteaseRemoteSyncConfirmState = LocalPlaylistDetailMutableValue(
                    { pendingNeteaseRemoteSyncConfirm }, { pendingNeteaseRemoteSyncConfirm = it }
                ),
                showDeletePlaylistConfirmState = LocalPlaylistDetailMutableValue(
                    { showDeletePlaylistConfirm }, { showDeletePlaylistConfirm = it }
                ),
                showDeleteMultiConfirmState = LocalPlaylistDetailMutableValue(
                    { showDeleteMultiConfirm }, { showDeleteMultiConfirm = it }
                ),
                showExportSheetState = LocalPlaylistDetailMutableValue(
                    { showExportSheet }, { showExportSheet = it }
                ),
                showExportAllSheetState = LocalPlaylistDetailMutableValue(
                    { showExportAllSheet }, { showExportAllSheet = it }
                ),
                detailSongState = LocalPlaylistDetailMutableValue(
                    { detailSong }, { detailSong = it }
                ),
                pendingSyncConfirmActionState = LocalPlaylistDetailMutableValue(
                    { pendingSyncConfirmAction }, { pendingSyncConfirmAction = it }
                ),
                pendingSyncConfirmLabelState = LocalPlaylistDetailMutableValue(
                    { pendingSyncConfirmLabel }, { pendingSyncConfirmLabel = it }
                ),
                showSearchState = LocalPlaylistDetailMutableValue(
                    { showSearch }, { showSearch = it }
                ),
                searchQueryState = LocalPlaylistDetailMutableValue(
                    { searchQuery }, { searchQuery = it }
                ),
                headerSearchFocusedState = LocalPlaylistDetailMutableValue(
                    { headerSearchFocused }, { headerSearchFocused = it }
                ),
                dockedSearchFocusedState = LocalPlaylistDetailMutableValue(
                    { dockedSearchFocused }, { dockedSearchFocused = it }
                ),
                showDownloadManagerState = LocalPlaylistDetailMutableValue(
                    { showDownloadManager }, { showDownloadManager = it }
                ),
                showLocalScanModeDialogState = LocalPlaylistDetailMutableValue(
                    { showLocalScanModeDialog }, { showLocalScanModeDialog = it }
                ),
                selectionModeState = LocalPlaylistDetailMutableValue(
                    { selectionMode }, { selectionMode = it }
                ),
                showRenameState = LocalPlaylistDetailMutableValue(
                    { showRename }, { showRename = it }
                ),
                renameTextState = LocalPlaylistDetailMutableValue(
                    { renameText }, { renameText = it }
                ),
                renameErrorState = LocalPlaylistDetailMutableValue(
                    { renameError }, { renameError = it }
                ),
                selectedLocalFilesTabIndexState = LocalPlaylistDetailMutableValue(
                    { selectedLocalFilesTabIndex }, { selectedLocalFilesTabIndex = it }
                ),
                pendingOrderIdentitiesState = LocalPlaylistDetailMutableValue(
                    { pendingOrderIdentities }, { pendingOrderIdentities = it }
                ),
                blockSyncState = LocalPlaylistDetailMutableValue(
                    { blockSync }, { blockSync = it }
                )
            )
            LocalPlaylistDetailModernContent(modernContentScope)
        }
    }
}
