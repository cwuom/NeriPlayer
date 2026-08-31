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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.toPlaybackSongItem
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

private data class PendingNeteaseRemotePlaylistSync(
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
            var pendingDownloadSongs by remember { mutableStateOf<List<SongItem>?>(null) }
            var pendingDownloadExitSelection by remember { mutableStateOf(false) }
            var showInsertAtDialog by remember { mutableStateOf(false) }
            var insertAtSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
            var insertAtSelectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
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
            var reorderBeforeDrag by remember(playlistId) {
                mutableStateOf<List<SongItem>?>(null)
            }
            var reorderMutationGeneration by remember(playlistId) {
                mutableIntStateOf(0)
            }

            fun repositoryPlaylistSongsSnapshot(): List<SongItem>? {
                return repo.playlists.value
                    .firstOrNull { it.id == playlistId }
                    ?.songs
                    ?.toList()
            }

            fun beginLocalPlaylistMutation(expectedSongs: List<SongItem>): Int {
                val generation = reorderMutationGeneration + 1
                reorderMutationGeneration = generation
                pendingOrderIdentities = expectedSongs.map { it.identity() }
                blockSync = true
                return generation
            }

            fun settleLocalPlaylistMutation(
                generation: Int,
                expectedOrder: List<SongIdentity>,
                previousSongs: List<SongItem>? = null,
                restorePreviousWhenRepositoryUnavailable: Boolean = false
            ): List<SongItem> {
                if (generation != reorderMutationGeneration) return localSongs.toList()
                val repositorySongs = repositoryPlaylistSongsSnapshot()
                if (repositorySongs != null) {
                    localSongs.clear()
                    localSongs.addAll(repositorySongs)
                } else if (
                    restorePreviousWhenRepositoryUnavailable &&
                        previousSongs != null &&
                        canUndoPlaylistReorder(
                            currentOrder = localSongs.map { it.identity() },
                            appliedOrder = expectedOrder
                        )
                ) {
                    localSongs.clear()
                    localSongs.addAll(previousSongs)
                }
                pendingOrderIdentities = null
                blockSync = false
                return localSongs.toList()
            }

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
                expectedSongs: List<SongItem>,
                mutationGeneration: Int,
                result: Result<List<LocalPlaylistSongDeleteResult>>
            ) {
                if (mutationGeneration != reorderMutationGeneration) return
                val deleteResults = result.getOrNull().orEmpty()
                if (result.isFailure || deleteResults.isEmpty()) {
                    settleLocalPlaylistMutation(
                        generation = mutationGeneration,
                        expectedOrder = expectedSongs.map { it.identity() },
                        previousSongs = previousSongs,
                        restorePreviousWhenRepositoryUnavailable = true
                    )
                } else {
                    settleLocalPlaylistMutation(
                        generation = mutationGeneration,
                        expectedOrder = expectedSongs.map { it.identity() }
                    )
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
                    reorderMutationGeneration += 1
                    pendingOrderIdentities = null
                    blockSync = false
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
                // the library still supplies drop-target bookkeeping; this screen owns
                // the visible-edge velocity so the two loops cannot add up
                maxScrollPerFrame = 0.dp,
                onMove = { from: ItemPosition, to: ItemPosition ->
                    if (!canReorderCurrentSongsState.value) {
                        return@rememberReorderableLazyListState
                    }
                    if (reorderBeforeDrag == null) {
                        reorderBeforeDrag = localSongs.toList()
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
                    val previousSongs = reorderBeforeDrag
                    reorderBeforeDrag = null
                    val mutationGeneration = beginLocalPlaylistMutation(localSongs.toList())
                    vm.reorderSongs(newOrder) { result ->
                        if (mutationGeneration != reorderMutationGeneration) return@reorderSongs
                        if (result.isFailure) {
                            settleLocalPlaylistMutation(
                                generation = mutationGeneration,
                                expectedOrder = newOrder,
                                previousSongs = previousSongs,
                                restorePreviousWhenRepositoryUnavailable = true
                            )
                            scope.launch {
                                snackbarHostState.showNeriSnackbar(
                                    composeResources.getString(R.string.playlist_reorder_failed)
                                )
                            }
                        }
                    }
                }
            )

            LaunchedEffect(reorderState) {
                snapshotFlow { reorderState.draggingItemIndex }
                    .distinctUntilChanged()
                    .collect { draggingIndex ->
                        if (draggingIndex != null && reorderBeforeDrag == null) {
                            reorderBeforeDrag = localSongs.toList()
                        }
                    }
            }

            // 记住滚动位置, 避免切换页面后回到顶部 (用稳定 key 防止列表变动导致错位)
            val savedListKey = rememberSaveable(playlistId) { mutableStateOf<String?>(null) }
            var savedListOffset by rememberSaveable(playlistId) { mutableIntStateOf(0) }
            val hasRestoredScroll = rememberSaveable(playlistId) { mutableStateOf(false) }
            val listState = reorderState.listState
            val reorderDensity = LocalDensity.current
            val reorderAutoScrollEdgePx = with(reorderDensity) { 112.dp.toPx() }
            val reorderAutoScrollMaxPx = with(reorderDensity) { 18.dp.toPx() }
            val reorderOffscreenGraceFrames = 36
            val reorderIsDragging = reorderState.draggingItemIndex != null
            val reorderOverscrollEffect = rememberReorderOverscrollEffect(
                isDragging = reorderIsDragging
            )
            LaunchedEffect(reorderState, canReorderCurrentSongs) {
                if (!canReorderCurrentSongs) return@LaunchedEffect
                snapshotFlow { reorderState.draggingItemIndex != null }
                    .distinctUntilChanged()
                    .collectLatest { isDragging ->
                        if (!isDragging) return@collectLatest
                        var lastStableEdgeDelta = 0f
                        var offscreenFrames = 0
                        while (isActive && reorderState.draggingItemIndex != null) {
                            withFrameNanos { }
                            val layoutInfo = listState.layoutInfo
                            val draggingKey = reorderState.draggingItemKey
                            val currentDraggingIndex = reorderState.draggingItemIndex
                            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                info.key == draggingKey || info.index == currentDraggingIndex
                            }
                            val delta = itemInfo?.let { info ->
                                offscreenFrames = 0
                                val draggingItemTop = resolveReorderDraggingItemTop(
                                    itemOffset = info.offset,
                                    itemHeight = info.size,
                                    viewportHeight = layoutInfo.viewportSize.height,
                                    draggingOffset = reorderState.draggingItemTop,
                                    reverseLayout = layoutInfo.reverseLayout
                                )
                                val edgeDelta = resolveReorderAutoScrollDelta(
                                    draggingItemTop = draggingItemTop,
                                    draggingItemHeight = info.size,
                                    viewportStart = layoutInfo.viewportStartOffset,
                                    viewportEnd = layoutInfo.viewportEndOffset,
                                    edgeDistance = reorderAutoScrollEdgePx,
                                    maxPerFrame = reorderAutoScrollMaxPx
                                )
                                val gatedDelta = gateReorderScrollDelta(
                                    delta = edgeDelta,
                                    reverseLayout = layoutInfo.reverseLayout,
                                    canScrollForward = listState.canScrollForward,
                                    canScrollBackward = listState.canScrollBackward
                                )
                                if (gatedDelta != 0f) {
                                    lastStableEdgeDelta = gatedDelta
                                } else {
                                    lastStableEdgeDelta = 0f
                                }
                                gatedDelta
                            } ?: run {
                                offscreenFrames += 1
                                if (offscreenFrames > reorderOffscreenGraceFrames) {
                                    lastStableEdgeDelta = 0f
                                    0f
                                } else {
                                    val continuation = resolveReorderOffscreenContinuation(
                                        lastDelta = lastStableEdgeDelta,
                                        maxPerFrame = reorderAutoScrollMaxPx
                                    )
                                    val gatedContinuation = gateReorderScrollDelta(
                                        delta = continuation,
                                        reverseLayout = layoutInfo.reverseLayout,
                                        canScrollForward = listState.canScrollForward,
                                        canScrollBackward = listState.canScrollBackward
                                    )
                                    if (gatedContinuation != 0f) {
                                        gatedContinuation
                                    } else {
                                        lastStableEdgeDelta = 0f
                                        0f
                                    }
                                }
                            }
                            if (delta != 0f) {
                                val scrollArgument = if (layoutInfo.reverseLayout) -delta else delta
                                val consumed = listState.scrollBy(scrollArgument)
                                if (
                                    consumed == 0f ||
                                        kotlin.math.abs(consumed) + 0.5f <
                                        kotlin.math.abs(scrollArgument)
                                ) {
                                    lastStableEdgeDelta = 0f
                                }
                            }
                        }
                    }
            }
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
            LaunchedEffect(tabSongs) {
                val validKeys = tabSongs.map { it.stableKey() }.toSet()
                val retainedKeys = selectedKeysState.value.intersect(validKeys)
                if (retainedKeys != selectedKeysState.value) {
                    selectedKeysState.value = retainedKeys
                }
                if (selectionMode && retainedKeys.isEmpty()) {
                    selectionMode = false
                }
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

            fun requestBatchDownload(
                songs: List<SongItem>,
                exitSelectionAfterConfirm: Boolean = false
            ) {
                val snapshot = songs.toList()
                if (snapshot.isEmpty()) return
                pendingDownloadSongs = snapshot
                pendingDownloadExitSelection = exitSelectionAfterConfirm
            }

            fun startPendingDownload() {
                val songs = pendingDownloadSongs ?: return
                pendingDownloadSongs = null
                showDownloadManager = true
                GlobalDownloadManager.startBatchDownload(context, songs)
                if (pendingDownloadExitSelection) {
                    pendingDownloadExitSelection = false
                    exitSelectionMode()
                }
            }

            fun openInsertAtDialog() {
                if (!canReorderCurrentSongs || selectedSongsForAction.isEmpty()) return
                val snapshot = baseQueue.toList()
                val selectedIndices = selectedIndicesForPlaylistInsert(
                    items = snapshot,
                    selectedKeys = selectedKeysState.value,
                    keyOf = SongItem::stableKey
                )
                if (selectedIndices.isEmpty()) return
                insertAtSongs = snapshot
                insertAtSelectedIndices = selectedIndices
                showInsertAtDialog = true
            }

            fun applyInsertAtPosition(position: Int) {
                val source = insertAtSongs
                val selectedIndices = insertAtSelectedIndices
                val currentSource = baseQueue
                if (
                    pendingOrderIdentities != null ||
                        source.map { it.identity() } != currentSource.map { it.identity() } ||
                        repositoryPlaylistSongsSnapshot()?.map { it.identity() } !=
                        source.map { it.identity() }
                ) {
                    showInsertAtDialog = false
                    insertAtSongs = emptyList()
                    insertAtSelectedIndices = emptySet()
                    scope.launch {
                        snackbarHostState.showNeriSnackbar(
                            composeResources.getString(R.string.playlist_insert_at_stale)
                        )
                    }
                    return
                }
                val currentSongByIdentity = currentSource.associateBy { it.identity() }
                val currentItems = source.map { song ->
                    currentSongByIdentity[song.identity()] ?: song
                }
                val reordered = moveSelectedItemsToOneBasedPosition(
                    items = currentItems,
                    selectedIndices = selectedIndices,
                    requestedPosition = position
                ) ?: return
                val previousSongs = localSongs.toList()
                localSongs.clear()
                localSongs.addAll(reordered)
                val mutationGeneration = beginLocalPlaylistMutation(reordered)
                showInsertAtDialog = false
                insertAtSongs = emptyList()
                insertAtSelectedIndices = emptySet()
                exitSelectionMode()
                vm.reorderSongs(reordered.map { it.identity() }) { result ->
                    if (mutationGeneration != reorderMutationGeneration) return@reorderSongs
                    if (result.isFailure) {
                        settleLocalPlaylistMutation(
                            generation = mutationGeneration,
                            expectedOrder = reordered.map { it.identity() },
                            previousSongs = previousSongs,
                            restorePreviousWhenRepositoryUnavailable = true
                        )
                        scope.launch {
                            snackbarHostState.showNeriSnackbar(
                                composeResources.getString(R.string.playlist_reorder_failed)
                            )
                        }
                    } else {
                        val appliedSongs = settleLocalPlaylistMutation(
                            generation = mutationGeneration,
                            expectedOrder = reordered.map { it.identity() }
                        )
                        val appliedOrder = appliedSongs.map { it.identity() }
                        scope.launch {
                            val snackbarResult = snackbarHostState.showNeriSnackbar(
                                message = composeResources.getString(
                                    R.string.playlist_insert_at_applied
                                ),
                                actionLabel = composeResources.getString(
                                    R.string.playlist_insert_at_undo
                                ),
                                withDismissAction = true,
                                duration = SnackbarDuration.Long
                            )
                            if (snackbarResult != SnackbarResult.ActionPerformed) return@launch
                            if (mutationGeneration != reorderMutationGeneration) return@launch
                            if (!canUndoPlaylistReorder(
                                    currentOrder = localSongs.map { it.identity() },
                                    appliedOrder = appliedOrder
                                ) ||
                                repositoryPlaylistSongsSnapshot()?.map { it.identity() } !=
                                    appliedOrder
                            ) {
                                snackbarHostState.showNeriSnackbar(
                                    composeResources.getString(
                                        R.string.playlist_insert_at_undo_unavailable
                                    )
                                )
                                return@launch
                            }
                            localSongs.clear()
                            localSongs.addAll(previousSongs)
                            val undoGeneration = beginLocalPlaylistMutation(previousSongs)
                            vm.reorderSongs(previousSongs.map { it.identity() }) { undoResult ->
                                if (undoGeneration != reorderMutationGeneration) return@reorderSongs
                                if (undoResult.isSuccess) {
                                    settleLocalPlaylistMutation(
                                        generation = undoGeneration,
                                        expectedOrder = previousSongs.map { it.identity() }
                                    )
                                    scope.launch {
                                        snackbarHostState.showNeriSnackbar(
                                            composeResources.getString(
                                                R.string.playlist_insert_at_undone
                                            )
                                        )
                                    }
                                } else {
                                    settleLocalPlaylistMutation(
                                        generation = undoGeneration,
                                        expectedOrder = previousSongs.map { it.identity() },
                                        previousSongs = appliedSongs,
                                        restorePreviousWhenRepositoryUnavailable = true
                                    )
                                    scope.launch {
                                        snackbarHostState.showNeriSnackbar(
                                            composeResources.getString(
                                                R.string.playlist_insert_at_undo_failed
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val insertedIndex = (position - 1).coerceAtMost(reordered.lastIndex)
                val insertedSongKey = reordered.getOrNull(insertedIndex)?.stableKey()
                val displayedAfterInsert = if (searchQuery.isBlank()) {
                    reordered
                } else {
                    SearchTextMatcher.filterAndRank(
                        query = searchQuery,
                        items = reordered,
                        tokens = { song -> song.playlistSearchValues(context) }
                    )
                }
                val displayedInsertedIndex = insertedSongKey?.let { key ->
                    displayedAfterInsert.indexOfFirst { it.stableKey() == key }
                } ?: -1
                if (displayedInsertedIndex >= 0) {
                    scope.launch {
                        listState.animateScrollToItem(
                            resolveLocalPlaylistSongListIndex(
                                songIndex = displayedInsertedIndex,
                                metadataProcessingVisible = metadataProcessingVisible
                            )
                        )
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

            PlaylistModernVisualColorsProvider(
                coverUrl = headerCover,
                offlineMode = offlineMode
            ) {
                val playlistChromeColor = rememberPlaylistModernHeroBackgroundColor(
                    coverUrl = headerCover,
                    offlineMode = offlineMode
                )
                val density = LocalDensity.current
                val searchVisible = shouldShowPlaylistSearch(
                    showSearch = showSearch,
                    selectionMode = selectionMode
                )
                val searchVisibilityProgress by animateFloatAsState(
                    targetValue = if (searchVisible) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "playlist-search-visibility"
                )
                val searchSlotProgress = searchVisibilityProgress.coerceIn(0f, 1f)
                val searchVisibilityEased = FastOutSlowInEasing.transform(searchSlotProgress)
                val searchDockedRevealTarget by remember(
                    reorderState.listState,
                    density
                ) {
                    derivedStateOf {
                        resolvePlaylistDockedSearchRevealProgress(
                            firstVisibleItemIndex = reorderState.listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffsetPx =
                                reorderState.listState.firstVisibleItemScrollOffset,
                            revealDistancePx = with(density) {
                                PlaylistModernDockedSearchSlotHeight.roundToPx()
                            }
                        )
                    }
                }
                val searchDockedRevealProgress = playlistModernAnimatedScrollProgress(
                    targetProgress = searchDockedRevealTarget,
                    label = "playlist-docked-search-reveal"
                )
                val searchDockedVisualProgress = FastOutSlowInEasing.transform(
                    searchDockedRevealProgress
                )
                val dockedSearchProgress = resolvePlaylistDockedSearchSlotProgress(
                    searchVisibilityProgress = searchSlotProgress,
                    dockedRevealProgress = searchDockedRevealProgress
                )
                val searchSlotVisible = shouldComposePlaylistSearchSlot(
                    searchVisible = searchVisible,
                    visibilityProgress = dockedSearchProgress
                )
                val searchSlotHeight = interpolatePlaylistDp(
                    start = 0.dp,
                    end = PlaylistModernDockedSearchSlotHeight,
                    fraction = dockedSearchProgress
                )
                val searchSlotAlpha = FastOutSlowInEasing.transform(dockedSearchProgress)
                val playlistHeroHeight = interpolatePlaylistDp(
                    start = PlaylistModernHeroHeight,
                    end = PlaylistModernHeroSearchHeight,
                    fraction = searchVisibilityEased
                )
                val playlistChromeCollapseTarget by remember(
                    reorderState.listState,
                    density,
                    playlistHeroHeight
                ) {
                    derivedStateOf {
                        resolvePlaylistChromeCollapseProgress(
                            firstVisibleItemIndex = reorderState.listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffsetPx =
                                reorderState.listState.firstVisibleItemScrollOffset,
                            expandedHeroHeightPx = with(density) {
                                playlistHeroHeight.roundToPx()
                            }
                        )
                    }
                }
                val playlistChromeCollapseProgress = playlistModernAnimatedScrollProgress(
                    targetProgress = playlistChromeCollapseTarget,
                    label = "playlist-chrome-collapse"
                )
                val playlistChromeVisualProgress =
                    FastOutSlowInEasing.transform(playlistChromeCollapseProgress)
                val headerSearchAlpha = resolvePlaylistHeaderSearchAlpha(
                    searchVisibilityProgress = searchSlotProgress,
                    chromeCollapseProgress = playlistChromeCollapseProgress
                )
                val headerSearchVisible = shouldComposePlaylistSearchSlot(
                    searchVisible = searchVisible,
                    visibilityProgress = headerSearchAlpha
                )
                val playlistTopBarColor = resolvePlaylistTranslucentTopBarColor(
                    playlistColor = playlistChromeColor,
                    collapseProgress = playlistChromeVisualProgress
                )
                val playlistTopBarContentColor = interpolatePlaylistColor(
                    start = resolvePlaylistSolidTopBarContentColor(playlistChromeColor),
                    end = playlistModernCollapsedTopBarContentColor(),
                    fraction = playlistChromeVisualProgress
                )
                val playlistSelectionTopBarColor = resolvePlaylistSelectionTopBarColor(
                    playlistColor = playlistChromeColor,
                    collapseProgress = playlistChromeCollapseProgress
                )
                val playlistSelectionTopBarContentColor = resolvePlaylistSelectionTopBarContentColor(
                    playlistColor = playlistChromeColor,
                    collapsedContentColor = playlistModernCollapsedTopBarContentColor(),
                    collapseProgress = playlistChromeCollapseProgress
                )
                val dockedSearchGlassColor = playlistModernDockedSearchGlassColor(
                    playlistColor = playlistChromeColor
                )
                val searchFieldFocusInHeader =
                    headerSearchVisible && searchDockedRevealProgress < 0.5f
                val searchFieldComposed = headerSearchVisible || searchSlotVisible
                LaunchedEffect(
                    showSearch,
                    selectionMode,
                    autoShowKeyboard,
                    searchFieldComposed,
                    searchFieldFocusInHeader
                ) {
                    if (!searchFieldComposed) return@LaunchedEffect
                    val shouldAutoFocus = shouldRequestPlaylistSearchFocus(
                        showSearch,
                        selectionMode,
                        autoShowKeyboard
                    )
                    val shouldTransferFocus = shouldTransferPlaylistSearchFocus(
                        showSearch = showSearch,
                        selectionMode = selectionMode,
                        searchFieldComposed = searchFieldComposed,
                        searchInputFocused = headerSearchFocused || dockedSearchFocused,
                        searchQuery = searchQuery
                    )
                    if (!shouldAutoFocus && !shouldTransferFocus) return@LaunchedEffect
                    if (shouldAutoFocus) delay(120)
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                }
                Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { 
                    NeriSnackbarHost(
                        hostState = snackbarHostState,
                        bottomPadding = LocalMiniPlayerHeight.current
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
                                    val openingSearch = !showSearch
                                    if (!openingSearch) {
                                        searchQuery = ""
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                    showSearch = openingSearch
                                }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search_songs)) }
                                
                                if (
                                    isLocalFilesPlaylist &&
                                    selectedLocalFilesTab == LocalFilesSongTab.MANUALLY_ADDED
                                ) {
                                    HapticIconButton(onClick = {
                                        showLocalScanModeDialog = true
                                    }, enabled = !scanPreviewState.isScanning) {
                                        Icon(
                                            Icons.Outlined.LibraryMusic,
                                            contentDescription = stringResource(R.string.download_scan_local)
                                        )
                                    }
                                }
                                PlaylistMoreMenuButton(
                                    tint = playlistTopBarContentColor,
                                    actions = buildList {
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.download_to_local),
                                                icon = Icons.Outlined.Download,
                                                enabled = tabSongs.isNotEmpty(),
                                                onClick = { requestBatchDownload(tabSongs) }
                                            )
                                        )
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.playlist_random_play),
                                                icon = Icons.Outlined.Shuffle,
                                                enabled = tabSongs.isNotEmpty(),
                                                onClick = { playPlaylist(shuffle = true) }
                                            )
                                        )
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.action_enter_multi_select),
                                                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                                                enabled = tabSongs.isNotEmpty(),
                                                onClick = { selectionMode = true }
                                            )
                                        )
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.cd_download_manager),
                                                icon = Icons.Outlined.DownloadDone,
                                                enabled = hasDownloadManagerEntry,
                                                onClick = { showDownloadManager = true }
                                            )
                                        )
                                        if (isFavorites) {
                                            add(
                                                PlaylistMoreMenuAction(
                                                    label = stringResource(
                                                        R.string.local_playlist_sync_netease_liked
                                                    ),
                                                    icon = Icons.Outlined.Sync,
                                                    enabled = !syncInProgress,
                                                    onClick = { requestNeteaseSync() }
                                                )
                                            )
                                        }
                                        if (!isSystemPlaylist) {
                                            add(
                                                PlaylistMoreMenuAction(
                                                    label = stringResource(R.string.local_playlist_rename),
                                                    icon = Icons.Filled.Edit,
                                                    onClick = {
                                                        renameText = playlistNameFieldValue(
                                                            playlist.name,
                                                            maxNameLength
                                                        )
                                                        renameError = null
                                                        showRename = true
                                                    }
                                                )
                                            )
                                            add(
                                                PlaylistMoreMenuAction(
                                                    label = stringResource(R.string.local_playlist_delete),
                                                    icon = Icons.Filled.Delete,
                                                    onClick = { showDeletePlaylistConfirm = true }
                                                )
                                            )
                                        }
                                    }
                                )
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = playlistTopBarColor,
                                scrolledContainerColor = playlistTopBarColor,
                                titleContentColor = playlistTopBarContentColor,
                                navigationIconContentColor = playlistTopBarContentColor,
                                actionIconContentColor = playlistTopBarContentColor
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
                                        selectedSongsForAction.size,
                                        selectedSongsForAction.size
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
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
                                PlaylistMoreMenuButton(
                                    tint = playlistSelectionTopBarContentColor,
                                    actions = buildList {
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(
                                                    R.string.local_playlist_sync_netease_playlist
                                                ),
                                                icon = Icons.Outlined.Sync,
                                                enabled = selectedKeysState.value.isNotEmpty() &&
                                                    !syncInProgress,
                                                onClick = { openNeteaseRemotePlaylistPicker() }
                                            )
                                        )
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.cd_export_playlist),
                                                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                                enabled = selectedKeysState.value.isNotEmpty(),
                                                onClick = { showExportSheet = true }
                                            )
                                        )
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.cd_download_selected),
                                                icon = Icons.Outlined.Download,
                                                enabled = selectedSongsForAction.isNotEmpty(),
                                                onClick = {
                                                    requestBatchDownload(
                                                        selectedSongsForAction,
                                                        exitSelectionAfterConfirm = true
                                                    )
                                                }
                                            )
                                        )
                                        if (canReorderCurrentSongs) {
                                            add(
                                                PlaylistMoreMenuAction(
                                                    label = stringResource(
                                                        R.string.playlist_insert_at_title
                                                    ),
                                                    icon = Icons.Outlined.SwapVert,
                                                    enabled = selectedSongsForAction.isNotEmpty(),
                                                    onClick = ::openInsertAtDialog
                                                )
                                            )
                                        }
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.common_delete_selected),
                                                icon = Icons.Filled.Delete,
                                                enabled = if (
                                                    isLocalFilesPlaylist &&
                                                        selectedLocalFilesTab == LocalFilesSongTab.DOWNLOADED
                                                ) {
                                                    selectedDownloadedSongsForAction.isNotEmpty()
                                                } else {
                                                    selectedSongsForAction.isNotEmpty()
                                                },
                                                onClick = { showDeleteMultiConfirm = true }
                                            )
                                        )
                                        add(
                                            PlaylistMoreMenuAction(
                                                label = stringResource(R.string.action_exit_multi_select),
                                                icon = Icons.Outlined.Close,
                                                onClick = ::exitSelectionMode
                                            )
                                        )
                                    }
                                )
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = playlistSelectionTopBarColor,
                                scrolledContainerColor = playlistSelectionTopBarColor,
                                titleContentColor = playlistSelectionTopBarContentColor,
                                navigationIconContentColor = playlistSelectionTopBarContentColor,
                                actionIconContentColor = playlistSelectionTopBarContentColor
                            )
                        )
                    }
                }
            ) { padding ->
                val miniPlayerHeight = LocalMiniPlayerHeight.current
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if (searchSlotVisible) {
                        PlaylistModernVisualColorsProvider(
                            coverUrl = headerCover,
                            offlineMode = offlineMode
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(searchSlotHeight)
                                    .clipToBounds()
                                    .graphicsLayer {
                                        alpha = searchSlotAlpha
                                    }
                            ) {
                                PlaylistModernStableSearchField(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    placeholder = stringResource(R.string.search_playlist),
                                    inputState = searchInputState,
                                    onFocusChanged = { dockedSearchFocused = it },
                                    focusRequester = if (searchFieldFocusInHeader) {
                                        null
                                    } else {
                                        searchFocusRequester
                                    },
                                    dockedProgress = searchDockedVisualProgress,
                                    glassColor = dockedSearchGlassColor,
                                    modifier = Modifier.graphicsLayer {
                                        translationY = with(density) {
                                            ((1f - searchSlotAlpha) * -8.dp.toPx())
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        key(playlistId) {
                            PlaylistModernVisualColorsProvider(
                                coverUrl = headerCover,
                                offlineMode = offlineMode
                            ) {
                                LazyColumn(
                                    state = reorderState.listState,
	                                    overscrollEffect = reorderOverscrollEffect,
	                                    contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
	                                    modifier = Modifier
	                                        .fillMaxSize()
	                                        .reorderable(reorderState)
	                                ) {
                                item(
                                    key = headerKey,
                                    contentType = "playlist_header"
                                ) {
                                    LocalPlaylistHeroHeader(
                                        displayName = headerDisplayName,
                                        headerCover = headerCover,
                                        totalDurationText = totalDurationText,
                                        songCount = tabSongs.size,
                                        playCount = playlistPlayCount,
                                        offlineMode = offlineMode,
                                        height = playlistHeroHeight,
                                        actions = if (headerSearchVisible) {
                                            {
                                                Box(
                                                    modifier = Modifier.graphicsLayer {
                                                        alpha = headerSearchAlpha
                                                    }
                                                ) {
                                                    PlaylistModernHeroSearchField(
                                                        query = searchQuery,
                                                        onQueryChange = { searchQuery = it },
                                                        placeholder = stringResource(R.string.search_playlist),
                                                        inputState = searchInputState,
                                                        onFocusChanged = { headerSearchFocused = it },
                                                        focusRequester = if (searchFieldFocusInHeader) {
                                                            searchFocusRequester
                                                        } else {
                                                            null
                                                        }
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                }

                                item(
                                    key = LOCAL_PLAYLIST_ACTIONS_KEY,
                                    contentType = "playlist_actions"
                                ) {
                                    PlaylistModernActionSheet(
                                        coverUrl = headerCover,
                                        offlineMode = offlineMode,
                                        hasCustomBackground = hasCustomBackground
                                    ) {
                                        Column {
                                            if (isLocalFilesPlaylist) {
                                                PrimaryTabRow(
                                                    selectedTabIndex = selectedLocalFilesTabIndex,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp),
                                                    containerColor = Color.Transparent,
                                                    contentColor = MaterialTheme.colorScheme.primary
                                                ) {
                                                    Tab(
                                                        selected = selectedLocalFilesTab == LocalFilesSongTab.MANUALLY_ADDED,
                                                        onClick = {
                                                            selectedLocalFilesTabIndex =
                                                                LocalFilesSongTab.MANUALLY_ADDED.ordinal
                                                            if (selectionMode) exitSelectionMode()
                                                        },
                                                        text = {
                                                            Text(
                                                                stringResource(R.string.local_files_manual_added)
                                                            )
                                                        }
                                                    )
                                                    Tab(
                                                        selected = selectedLocalFilesTab == LocalFilesSongTab.DOWNLOADED,
                                                        onClick = {
                                                            selectedLocalFilesTabIndex =
                                                                LocalFilesSongTab.DOWNLOADED.ordinal
                                                            if (selectionMode) exitSelectionMode()
                                                        },
                                                        text = {
                                                            Text(
                                                                stringResource(R.string.local_files_downloaded)
                                                            )
                                                        }
                                                    )
                                                }
                                                HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(
                                                        alpha = 0.5f
                                                    )
                                                )
                                            }
                                            LocalPlaylistPlaybackActions(
                                                songCount = tabSongs.size,
                                                shuffleEnabled = shuffleEnabled,
                                                repeatMode = repeatMode,
                                                onPlayInOrder = { playPlaylist(shuffle = false) },
                                                onShufflePlay = { playPlaylist(shuffle = true) },
                                                onToggleShuffle = {
                                                    PlayerManager.setShuffle(!shuffleEnabled)
                                                },
                                                onCycleRepeatMode = {
                                                    PlayerManager.cycleRepeatMode()
                                                },
                                                onExportToLocalPlaylist = {
                                                    showExportAllSheet = true
                                                }
                                            )
                                        }
                                    }
                                }

                                if (metadataProcessingVisible) {
                                    item(
                                        key = LOCAL_PLAYLIST_METADATA_PROCESSING_KEY,
                                        contentType = "local_metadata_processing"
                                    ) {
                                        PlaylistModernListItemSurface(
                                            coverUrl = headerCover,
                                            offlineMode = offlineMode
                                        ) {
                                            LocalMetadataProcessingCard(visibleMetadataProcessingState)
                                        }
                                    }
                                }

                                // 列表
                                itemsIndexed(
                                    items = displayedSongs,
                                    key = { _, song -> song.stableKey() },
                                    contentType = { _, _ -> "local_playlist_song" }
                                ) { revIndex, song ->
                                val songKey = remember(song) { song.stableKey() }
                                ReorderableItem(state = reorderState, key = songKey) { isDragging ->
                                    val rowScale by animateFloatAsState(
                                        targetValue = if (isDragging) 1.02f else 1f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        label = "row-scale"
                                    )
                                    val isSelectedSong =
                                        selectionMode && selectedKeysState.value.contains(songKey)
                                    val isFavoriteSong = favoriteSongLookup.contains(song)
                                    val rowContainerColor = if (isSelectedSong) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                                    } else {
                                        Color.Transparent
                                    }

                                    PlaylistModernListItemSurface(
                                        coverUrl = headerCover,
                                        offlineMode = offlineMode,
                                        modifier = Modifier
                                            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(rowContainerColor)
                                                .combinedClickable(
                                                    onClick = {
                                                        context.performHapticFeedback()
                                                        if (selectionMode) {
                                                            toggleSelect(songKey)
                                                        } else {
                                                            val pos = queueIndexBySongKey[songKey] ?: -1
                                                            if (pos >= 0) onSongClick(tabSongs, pos)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!selectionMode) {
                                                            selectionMode = true
                                                            selectedKeysState.value = setOf(songKey)
                                                        } else {
                                                            toggleSelect(songKey)
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
                                                        checked = selectedKeysState.value.contains(songKey),
                                                        onCheckedChange = { toggleSelect(songKey) }
                                                    )
                                                } else {
                                                    Text(
                                                        text = (revIndex + 1).toString(),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = playlistModernListTertiaryContentColor(),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Clip
                                                    )
                                                }
                                            }

                                            // 可见行立即排入受限的封面队列, 不让首屏等待滚动空闲
                                            val resolveArtworkFallback =
                                                shouldResolveLocalPlaylistRowArtworkFallback()
                                            LocalPlaylistSongArtwork(
                                                song = song,
                                                offlineMode = offlineMode,
                                                resolveLocalFallback = resolveArtworkFallback,
                                                downloadPresenceVersion = downloadPresenceVersion,
                                                allowEmbeddedCoverFallback = resolveArtworkFallback
                                            )
                                            Spacer(Modifier.width(12.dp))

                                            // 标题/歌手
                                            Column(Modifier.weight(1f)) {
                                                val downloaded = songKey in downloadedSongKeys
                                                Text(
                                                    text = song.displayName(),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = playlistModernListPrimaryContentColor()
                                                )
                                                SongDownloadSubtitle(
                                                    text = song.displayArtist(),
                                                    downloaded = downloaded,
                                                    color = playlistModernListSecondaryContentColor()
                                                )
                                            }
                                        }

                                        // 右侧: 非多选为时间/播放态; 多选为手柄
                                        val isPlayingSong = currentSongLookup.contains(song)
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
                                                            color = playlistModernListSecondaryContentColor(),
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
                                                                tint = playlistModernListSecondaryContentColor()
                                                            )
                                                        }

                                                        DropdownMenu(
                                                            expanded = showMoreMenu,
                                                            onDismissRequest = { showMoreMenu = false }
                                                        ) {
                                                                if (song.isLocalSong()) {
                                                                    DropdownMenuItem(
                                                                        text = { Text(stringResource(R.string.local_song_open_details)) },
                                                                        leadingIcon = {
                                                                            Icon(
                                                                                imageVector = Icons.Outlined.Info,
                                                                                contentDescription = null
                                                                            )
                                                                        },
                                                                        onClick = {
                                                                            detailSong = song
                                                                            showMoreMenu = false
                                                                    }
                                                                )
                                                                    DropdownMenuItem(
                                                                        text = { Text(stringResource(R.string.action_share)) },
                                                                        leadingIcon = {
                                                                            Icon(
                                                                                imageVector = Icons.Outlined.Share,
                                                                                contentDescription = null
                                                                            )
                                                                        },
                                                                        onClick = {
                                                                            showMoreMenu = false
                                                                            scope.launch {
                                                                            val shared = runCatching {
                                                                                LocalMediaSupport.shareSongFile(context, song)
                                                                            }.getOrElse { false }
                                                                            if (!shared) {
                                                                                snackbarHostState.showNeriSnackbar(
                                                                                    composeResources.getString(R.string.local_song_share_failed)
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.local_playlist_play_next)) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                                                        contentDescription = null
                                                                    )
                                                                },
                                                                onClick = {
                                                                    PlayerManager.addToQueueNext(song)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.playlist_add_to_end)) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                                                        contentDescription = null
                                                                    )
                                                                },
                                                                onClick = {
                                                                    PlayerManager.addToQueueEnd(song)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        stringResource(
                                                                            if (isFavoriteSong) {
                                                                                R.string.favorite_remove
                                                                            } else {
                                                                                R.string.favorite_add
                                                                            }
                                                                        )
                                                                    )
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        imageVector = if (isFavoriteSong) {
                                                                            Icons.Filled.Favorite
                                                                        } else {
                                                                            Icons.Outlined.FavoriteBorder
                                                                        },
                                                                        contentDescription = null
                                                                    )
                                                                },
                                                                onClick = {
                                                                    toggleSongFavorite(song, isFavoriteSong)
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(R.string.action_copy_song_info)) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        imageVector = Icons.Outlined.ContentCopy,
                                                                        contentDescription = null
                                                                    )
                                                                },
                                                                onClick = {
                                                                    val songInfo =
                                                                        "${song.displayName()}-${song.displayArtist()}"
                                                                    scope.launch {
                                                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", songInfo)))
                                                                        snackbarHostState.showNeriSnackbar(
                                                                            composeResources.getString(R.string.toast_copied)
                                                                        )
                                                                    }
                                                                    showMoreMenu = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (canReorderCurrentSongs) {
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
                                        } else {
                                            Spacer(Modifier.size(40.dp))
                                        }
                                    }
                                }
                                }
                            }
                        }
                        }
                        }
                        if (currentIndexInDisplay >= 0) {
                            HapticFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        reorderState.listState.animateScrollToItem(
                                            resolveLocalPlaylistPlayingItemIndex(
                                                songIndex = currentIndexInDisplay,
                                                metadataProcessingVisible = metadataProcessingVisible
                                            )
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
                                vm.delete { result ->
                                    showPlaylistDeleteResultGlobally(
                                        context = context,
                                        repository = repo,
                                        result = result
                                    )
                                    if (result.getOrNull().orEmpty().isNotEmpty()) {
                                        navigateAfterPlaylistDeleted()
                                    }
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
                    val deletesDownloadedSongs = isLocalFilesPlaylist &&
                        selectedLocalFilesTab == LocalFilesSongTab.DOWNLOADED
                    val count = if (deletesDownloadedSongs) {
                        selectedDownloadedSongsForAction.size
                    } else {
                        selectedSongsForAction.size
                    }
                    AlertDialog(
                        onDismissRequest = { showDeleteMultiConfirm = false },
                        title = {
                            Text(
                                stringResource(
                                    if (deletesDownloadedSongs) {
                                        R.string.local_files_delete_downloaded_title
                                    } else {
                                        R.string.local_playlist_delete_songs
                                    }
                                )
                            )
                        },
                        text = {
                            Text(
                                if (deletesDownloadedSongs) {
                                    stringResource(
                                        R.string.local_files_delete_downloaded_confirm,
                                        count
                                    )
                                } else {
                                    pluralStringResource(
                                        R.plurals.local_playlist_delete_songs_confirm,
                                        count,
                                        count
                                    )
                                }
                            )
                        },
                        confirmButton = {
                            HapticTextButton(onClick = {
                                if (deletesDownloadedSongs) {
                                    val songsToDelete = selectedDownloadedSongsForAction
                                    showDeleteMultiConfirm = false
                                    exitSelectionMode()
                                    vm.deleteDownloadedSongs(songsToDelete) { result ->
                                        scope.launch {
                                            val message = when {
                                                result.deletedCount > 0 && result.notDeletedCount == 0 -> {
                                                    context.resources.getQuantityString(
                                                        R.plurals.local_files_delete_downloaded_success,
                                                        result.deletedCount,
                                                        result.deletedCount
                                                    )
                                                }
                                                result.deletedCount > 0 -> {
                                                    composeResources.getString(
                                                        R.string.local_files_delete_downloaded_partial,
                                                        result.deletedCount,
                                                        result.notDeletedCount
                                                    )
                                                }
                                                else -> {
                                                    composeResources.getString(
                                                        R.string.local_files_delete_downloaded_failed
                                                    )
                                                }
                                            }
                                            snackbarHostState.showNeriSnackbar(message)
                                        }
                                    }
                                    return@HapticTextButton
                                }
                                val previousSongs = localSongs.toList()
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
                                localSongs.clear()
                                localSongs.addAll(expectedSongs)
                                val mutationGeneration = beginLocalPlaylistMutation(expectedSongs)
                                showDeleteMultiConfirm = false
                                exitSelectionMode()

                                if (removeAll) {
                                    vm.clearSongs { result ->
                                        handleLocalSongDeleteResult(
                                            previousSongs = previousSongs,
                                            expectedSongs = expectedSongs,
                                            mutationGeneration = mutationGeneration,
                                            result = result
                                        )
                                    }
                                } else {
                                    vm.removeSongs(songsToRemove) { result ->
                                        handleLocalSongDeleteResult(
                                            previousSongs = previousSongs,
                                            expectedSongs = expectedSongs,
                                            mutationGeneration = mutationGeneration,
                                            result = result
                                        )
                                    }
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
                                storedSongs = tabSongs,
                                selectedKeys = selectedKeysState.value
                            )
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = composeResources.getString(R.string.playlist_add_to)
                            ) {
                                scope.launchLocalPlaylistMutation(
                                    operation = "createPlaylistFromLocalPlaylist",
                                    onResult = { result ->
                                        scope.showPlaylistBatchExportCreatedResult(
                                            context = context,
                                            snackbarHostState = snackbarHostState,
                                            repository = repo,
                                            result = result
                                        )
                                    }
                                ) {
                                    repo.createPlaylistWithPreparedSongs(name, songs)
                                }
                                exitSelectionMode()
                            }
                        },
                        onExportToPlaylist = { target ->
                            val songs = selectedStoredLocalSongsForExport(
                                storedSongs = tabSongs,
                                selectedKeys = selectedKeysState.value
                            )
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = composeResources.getString(R.string.playlist_add_to)
                            ) {
                                scope.launchLocalPlaylistMutation(
                                    operation = "exportSongsFromLocalPlaylist",
                                    onResult = { result ->
                                        scope.showPlaylistBatchExportAddedResult(
                                            context = context,
                                            snackbarHostState = snackbarHostState,
                                            repository = repo,
                                            targetPlaylistId = target.id,
                                            targetPlaylistName = target.name,
                                            result = result
                                        )
                                    }
                                ) {
                                    repo.addPreparedSongsToPlaylistWithResult(target.id, songs)
                                }
                                exitSelectionMode()
                            }
                        }
                    )
                }

                if (showExportAllSheet) {
                    PlaylistExportSheet(
                        title = stringResource(R.string.playlist_export_to_local),
                        playlists = allPlaylists.filter {
                            it.id != playlist.id && !LocalFilesPlaylist.isSystemPlaylist(it, context)
                        },
                        selectedCount = tabSongs.size,
                        onDismissRequest = { showExportAllSheet = false },
                        onCreateAndExport = { name ->
                            val songs = tabSongs
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = composeResources.getString(R.string.playlist_add_to)
                            ) {
                                scope.launchLocalPlaylistMutation(
                                    operation = "createPlaylistFromLocalPlaylistAll",
                                    onResult = { result ->
                                        scope.showPlaylistBatchExportCreatedResult(
                                            context = context,
                                            snackbarHostState = snackbarHostState,
                                            repository = repo,
                                            result = result
                                        )
                                    }
                                ) {
                                    repo.createPlaylistWithPreparedSongs(name, songs)
                                }
                                showExportAllSheet = false
                            }
                        },
                        onExportToPlaylist = { target ->
                            val songs = tabSongs
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = composeResources.getString(R.string.playlist_add_to)
                            ) {
                                scope.launchLocalPlaylistMutation(
                                    operation = "exportAllSongsFromLocalPlaylist",
                                    onResult = { result ->
                                        scope.showPlaylistBatchExportAddedResult(
                                            context = context,
                                            snackbarHostState = snackbarHostState,
                                            repository = repo,
                                            targetPlaylistId = target.id,
                                            targetPlaylistName = target.name,
                                            result = result
                                        )
                                    }
                                ) {
                                    repo.addPreparedSongsToPlaylistWithResult(target.id, songs)
                                }
                                showExportAllSheet = false
                            }
                        }
                    )
                }

                if (showNeteaseRemotePlaylistPicker) {
                    NeteaseRemotePlaylistPickerDialog(
                        playlists = neteaseRemotePlaylists,
                        loading = neteaseRemotePlaylistsLoading,
                        errorMessage = neteaseRemotePlaylistsError,
                        onPlaylistClick = ::selectNeteaseRemotePlaylist,
                        onDismissRequest = ::dismissNeteaseRemotePlaylistPicker
                    )
                }

                pendingDownloadSongs?.let { songs ->
                    PlaylistDownloadConfirmationDialog(
                        songCount = songs.size,
                        onConfirm = { startPendingDownload() },
                        onDismiss = {
                            pendingDownloadSongs = null
                            pendingDownloadExitSelection = false
                        }
                    )
                }

                if (showInsertAtDialog) {
                    PlaylistInsertAtDialog(
                        itemCount = insertAtSongs.size,
                        selectedCount = insertAtSelectedIndices.size,
                        previewForPosition = { position ->
                            val preview = moveSelectedItemsToOneBasedPosition(
                                items = insertAtSongs,
                                selectedIndices = insertAtSelectedIndices,
                                requestedPosition = position
                            ).orEmpty()
                            if (preview.isEmpty()) {
                                emptyList()
                            } else {
                                val first = (position - 1).coerceIn(0, preview.lastIndex)
                                val start = (first - 2).coerceAtLeast(0)
                                val end = (first + insertAtSelectedIndices.size + 2)
                                    .coerceAtMost(preview.size)
                                preview.subList(start, end).mapIndexed { offset, song ->
                                    val index = start + offset + 1
                                    PlaylistInsertPreviewRow(
                                        position = index,
                                        title = song.displayName(),
                                        subtitle = song.displayArtist(),
                                        isMoved = index in (
                                            first + 1
                                        )..(first + insertAtSelectedIndices.size)
                                    )
                                }
                            }
                        },
                        onConfirm = ::applyInsertAtPosition,
                        onDismiss = {
                            showInsertAtDialog = false
                            insertAtSongs = emptyList()
                            insertAtSelectedIndices = emptySet()
                        }
                    )
                }

                pendingNeteaseRemoteSyncConfirm?.let { pending ->
                    val supportedCount = pending.songs.size
                    AlertDialog(
                        onDismissRequest = { pendingNeteaseRemoteSyncConfirm = null },
                        title = {
                            Text(
                                stringResource(
                                    R.string.local_playlist_sync_netease_partial_confirm_title
                                )
                            )
                        },
                        text = {
                            Text(
                                "${pluralStringResource(
                                    R.plurals.local_playlist_sync_netease_partial_confirm_unsupported,
                                    pending.unsupportedCount,
                                    pending.unsupportedCount
                                )} ${pluralStringResource(
                                    R.plurals.local_playlist_sync_netease_partial_confirm_target,
                                    supportedCount,
                                    supportedCount,
                                    pending.target.name
                                )}"
                            )
                        },
                        confirmButton = {
                            HapticTextButton(
                                onClick = {
                                    startNeteaseRemotePlaylistSync(
                                        target = pending.target,
                                        songs = pending.songs,
                                        unsupportedCount = pending.unsupportedCount
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.action_confirm))
                            }
                        },
                        dismissButton = {
                            HapticTextButton(
                                onClick = { pendingNeteaseRemoteSyncConfirm = null }
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                // 下载管理器
                if (showDownloadManager) {
                    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
                    BatchDownloadManagerSheet(
                        downloadTasks = downloadTasks,
                        onDismiss = { showDownloadManager = false }
                    )
                }

                detailSong?.let { song ->
                    LocalSongDetailsDialog(
                        song = song,
                        onDismiss = { detailSong = null },
                        onShowMessage = { message ->
                            scope.launch {
                                snackbarHostState.showNeriSnackbar(message)
                            }
                        }
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
}

@Composable
internal fun NeteaseRemotePlaylistPickerDialog(
    playlists: List<NeteaseRemotePlaylist>,
    loading: Boolean,
    errorMessage: String?,
    onPlaylistClick: (NeteaseRemotePlaylist) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.local_playlist_sync_netease_picker_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.local_playlist_sync_netease_loading_playlists))
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (playlists.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        items(
                            items = playlists,
                            key = { playlist -> playlist.id }
                        ) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !loading) {
                                        onPlaylistClick(playlist)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                    contentDescription = null
                                )
                                Column {
                                    Text(
                                        text = playlist.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.local_playlist_sync_netease_track_count,
                                            playlist.trackCount,
                                            playlist.trackCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            HapticTextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
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
    val rowKey: String,
    val title: String,
    val fileName: String,
    val filePath: String,
    val subtitle: String,
    val hasMetadata: Boolean,
    val searchText: String
)

private fun SongItem.toLocalScanPreviewItem(
    context: Context,
    metadataPending: Boolean = false
): LocalScanPreviewItem {
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
    val hasMetadata = metadataPending || hasMeaningfulPreviewMetadata(context, resolvedFileName)
    val subtitle = buildList {
        displayArtist.takeIf { it.isNotBlank() }?.let(::add)
        displayAlbum.takeIf { it.isNotBlank() }?.let(::add)
        resolvedFileName.takeIf { it.isNotBlank() && it != displayName }?.let(::add)
        durationMs.takeIf { it > 0L }?.let { add(formatDuration(it)) }
    }.joinToString(" · ")
    return LocalScanPreviewItem(
        song = this,
        stableKey = stableKey(),
        rowKey = scanPreviewRowKey(),
        title = displayName,
        fileName = resolvedFileName,
        filePath = resolvedPath,
        subtitle = subtitle,
        hasMetadata = hasMetadata,
        searchText = listOf(resolvedFileName, resolvedPath, displayName, displayArtist, displayAlbum)
            .joinToString("\n")
    )
}

private fun SongItem.scanPreviewRowKey(): String {
    val source = mediaUri
        ?.takeIf(String::isNotBlank)
        ?: localFilePath?.takeIf(String::isNotBlank)
        ?: localFileName?.takeIf(String::isNotBlank)
    return source?.let { "local-scan:$it" } ?: "local-scan:${stableKey()}"
}

private const val LOCAL_PLAYLIST_ARTWORK_IDLE_DELAY_MS = 96L
private const val LOCAL_PLAYLIST_ARTWORK_MEMORY_CACHE_LIMIT = 256
private val retainedLocalPlaylistArtworkCache = object : LinkedHashMap<String, String>(
    LOCAL_PLAYLIST_ARTWORK_MEMORY_CACHE_LIMIT,
    0.75f,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
        return size > LOCAL_PLAYLIST_ARTWORK_MEMORY_CACHE_LIMIT
    }
}

@Composable
private fun rememberLocalPlaylistArtworkIdle(
    sessionKey: Any,
    isScrollInProgress: Boolean
): Boolean {
    var hasReachedIdleWindow by remember(sessionKey) { mutableStateOf(false) }
    LaunchedEffect(sessionKey, isScrollInProgress) {
        if (isScrollInProgress) {
            hasReachedIdleWindow = false
        } else {
            delay(LOCAL_PLAYLIST_ARTWORK_IDLE_DELAY_MS)
            hasReachedIdleWindow = true
        }
    }
    return hasReachedIdleWindow
}

@Composable
private fun LocalPlaylistSongArtwork(
    song: SongItem,
    offlineMode: Boolean,
    resolveLocalFallback: Boolean,
    downloadPresenceVersion: Int,
    allowEmbeddedCoverFallback: Boolean
) {
    val requestedCoverUrl = rememberSongDisplayCoverUrl(
        song = song,
        resolveLocalFallback = resolveLocalFallback,
        downloadPresenceVersion = downloadPresenceVersion,
        allowEmbeddedCoverFallback = allowEmbeddedCoverFallback
    )
    val context = LocalContext.current
    val artworkIdentityKey = remember(song, downloadPresenceVersion) {
        "${song.stableKey()}|generation=$downloadPresenceVersion"
    }
    var displayedCoverUrl by remember(artworkIdentityKey) {
        mutableStateOf(
            initialLocalPlaylistArtworkUrl(
                retainedCoverUrl = cachedRetainedLocalPlaylistArtworkUrl(artworkIdentityKey),
                requestedCoverUrl = requestedCoverUrl,
                immediateCoverUrl = song.displayCoverUrl()
            )
        )
    }
    val latestRequestedCoverUrl by rememberUpdatedState(requestedCoverUrl)
    val visibleCoverUrl = retainedLocalPlaylistArtworkUrl(
        displayedCoverUrl = displayedCoverUrl,
        requestedCoverUrl = requestedCoverUrl
    )
    val visibleImageRequest = remember(context, visibleCoverUrl, offlineMode) {
        visibleCoverUrl?.let { coverUrl ->
            fastScrollableImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 128,
                crossfade = false,
                offlineMode = offlineMode,
                cacheKey = listOf(
                    "playlist-row-cover",
                    artworkIdentityKey,
                    coverUrl
                ).joinToString("|")
            )
        }
    }
    val pendingCoverUrl = requestedCoverUrl
        ?.takeIf { displayedCoverUrl != null && it != displayedCoverUrl }
    val pendingImageRequest = remember(context, pendingCoverUrl, offlineMode) {
        pendingCoverUrl?.let { coverUrl ->
            fastScrollableImageRequest(
                context = context,
                data = coverUrl,
                sizePx = 128,
                crossfade = false,
                offlineMode = offlineMode,
                cacheKey = listOf(
                    "playlist-row-cover",
                    artworkIdentityKey,
                    coverUrl
                ).joinToString("|")
            )
        }
    }

    fun promoteLoadedCover(coverUrl: String) {
        if (latestRequestedCoverUrl == coverUrl) {
            displayedCoverUrl = coverUrl
            rememberRetainedLocalPlaylistArtworkUrl(artworkIdentityKey, coverUrl)
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        visibleImageRequest?.let { imageRequest ->
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = {
                    visibleCoverUrl?.let { coverUrl ->
                        if (displayedCoverUrl == null) {
                            promoteLoadedCover(coverUrl)
                        } else {
                            rememberRetainedLocalPlaylistArtworkUrl(artworkIdentityKey, coverUrl)
                        }
                    }
                }
            )
        }
        pendingImageRequest?.let { imageRequest ->
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0f },
                onSuccess = {
                    pendingCoverUrl?.let(::promoteLoadedCover)
                }
            )
        }
    }
}

internal fun retainedLocalPlaylistArtworkUrl(
    displayedCoverUrl: String?,
    requestedCoverUrl: String?
): String? = displayedCoverUrl ?: requestedCoverUrl

internal fun initialLocalPlaylistArtworkUrl(
    retainedCoverUrl: String?,
    requestedCoverUrl: String?,
    immediateCoverUrl: String?
): String? = retainedCoverUrl ?: requestedCoverUrl ?: immediateCoverUrl

private fun cachedRetainedLocalPlaylistArtworkUrl(artworkIdentityKey: String): String? {
    return synchronized(retainedLocalPlaylistArtworkCache) {
        retainedLocalPlaylistArtworkCache[artworkIdentityKey]
    }
}

private fun rememberRetainedLocalPlaylistArtworkUrl(
    artworkIdentityKey: String,
    coverUrl: String
) {
    if (coverUrl.isBlank()) return
    synchronized(retainedLocalPlaylistArtworkCache) {
        retainedLocalPlaylistArtworkCache[artworkIdentityKey] = coverUrl
    }
}

internal fun shouldResolveLocalPlaylistRowArtworkFallback(): Boolean = true

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
    scanProgress: LocalAudioScanProgress = LocalAudioScanProgress(),
    songs: List<SongItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    metadataOnly: Boolean = false,
    onMetadataOnlyChange: ((Boolean) -> Unit)? = null,
    hideExistingLocalPlaylistSongs: Boolean = false,
    onHideExistingLocalPlaylistSongsChange: ((Boolean) -> Unit)? = null,
    existingLocalPlaylistKeys: Set<String> = emptySet(),
    hideDuplicateMetadataSongs: Boolean = false,
    onHideDuplicateMetadataSongsChange: ((Boolean) -> Unit)? = null,
    duplicateMetadataKeys: Set<String> = emptySet(),
    metadataPendingKeys: Set<String> = emptySet(),
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
        metadataPendingKeys,
        appContext
    ) {
        value = withContext(Dispatchers.Default) {
            songs.map {
                it.toLocalScanPreviewItem(
                    context = appContext,
                    metadataPending = it.stableKey() in metadataPendingKeys
                )
            }
        }
    }
    val listState = rememberLazyListState()
    val displayedItems by produceState<List<LocalScanPreviewItem>>(
        initialValue = emptyList(),
        previewItems,
        query,
        metadataOnly,
        hideExistingLocalPlaylistSongs,
        existingLocalPlaylistKeys,
        hideDuplicateMetadataSongs,
        duplicateMetadataKeys
    ) {
        value = withContext(Dispatchers.Default) {
            val candidates = previewItems
                .asSequence()
                .filter { item -> !metadataOnly || item.hasMetadata }
                .filter {
                    item -> !hideExistingLocalPlaylistSongs ||
                        item.stableKey !in existingLocalPlaylistKeys
                }
                .filter {
                    item -> !hideDuplicateMetadataSongs ||
                        item.stableKey !in duplicateMetadataKeys
                }
                .toList()
            SearchTextMatcher.filterAndRank(query, candidates) { item ->
                listOf(item.title, item.fileName, item.filePath, item.subtitle, item.searchText)
            }
        }
    }
    var showMoreMenu by remember { mutableStateOf(false) }
    val metadataFilterAvailable = onMetadataOnlyChange != null
    val existingLocalPlaylistSongsFilterAvailable =
        onHideExistingLocalPlaylistSongsChange != null
    val duplicateMetadataSongsFilterAvailable =
        onHideDuplicateMetadataSongsChange != null
    val displayedKeys by remember(displayedItems) {
        derivedStateOf {
            displayedItems.mapTo(LinkedHashSet(displayedItems.size)) { it.stableKey }
        }
    }
    val allDisplayedSelected = displayedKeys.isNotEmpty() && displayedKeys.all(selectedKeys::contains)
    val resolvedTitle = title ?: stringResource(R.string.local_playlist_scan_preview_title)
    val resolvedSearchPlaceholder =
        searchPlaceholder ?: stringResource(R.string.local_playlist_scan_preview_search)
    val resolvedEmptyText = emptyText ?: when {
        hideDuplicateMetadataSongs || (metadataOnly && hideExistingLocalPlaylistSongs) -> {
            stringResource(R.string.local_playlist_scan_filtered_empty)
        }
        metadataOnly -> stringResource(R.string.local_playlist_scan_metadata_empty)
        hideExistingLocalPlaylistSongs -> stringResource(R.string.local_playlist_scan_existing_empty)
        else -> stringResource(R.string.download_scan_empty)
    }
    val resolvedActionLabel = actionLabel?.invoke(selectedKeys.size)
        ?: stringResource(R.string.download_scan_add_selected, selectedKeys.size)
    val showBusy = isScanning || isBusy

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            NeriSnackbarHost(
                hostState = snackbarHostState,
                bottomPadding = LocalMiniPlayerHeight.current
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
                    if (
                        metadataFilterAvailable ||
                        existingLocalPlaylistSongsFilterAvailable ||
                        duplicateMetadataSongsFilterAvailable
                    ) {
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
                                if (metadataFilterAvailable) {
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
                                            onMetadataOnlyChange(!metadataOnly)
                                            showMoreMenu = false
                                        }
                                    )
                                }
                                if (existingLocalPlaylistSongsFilterAvailable) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.local_playlist_scan_filter_existing))
                                        },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = hideExistingLocalPlaylistSongs,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            onHideExistingLocalPlaylistSongsChange(
                                                !hideExistingLocalPlaylistSongs
                                            )
                                            showMoreMenu = false
                                        }
                                    )
                                }
                                if (duplicateMetadataSongsFilterAvailable) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.local_playlist_scan_filter_duplicates
                                                )
                                            )
                                        },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = hideDuplicateMetadataSongs,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            onHideDuplicateMetadataSongsChange(
                                                !hideDuplicateMetadataSongs
                                            )
                                            showMoreMenu = false
                                        }
                                    )
                                }
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
                    if (isScanning) {
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.download_scanning),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = when (scanProgress.phase) {
                                    LocalAudioScanPhase.TRAVERSING -> stringResource(
                                        R.string.local_playlist_scan_progress_traversing,
                                        scanProgress.visitedDirectories,
                                        scanProgress.discoveredSongs
                                    )
                                    LocalAudioScanPhase.HYDRATING_METADATA -> stringResource(
                                        R.string.local_playlist_scan_progress_metadata,
                                        scanProgress.processed,
                                        scanProgress.total
                                    )
                                    LocalAudioScanPhase.BUILDING_ENTRIES,
                                    LocalAudioScanPhase.COMPLETED -> stringResource(
                                        R.string.local_playlist_scan_progress_building,
                                        scanProgress.processed,
                                        scanProgress.total
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                            key = { _, item -> item.rowKey },
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
