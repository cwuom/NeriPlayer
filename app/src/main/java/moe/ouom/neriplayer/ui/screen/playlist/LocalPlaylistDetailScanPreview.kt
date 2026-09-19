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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalScanPreviewScreen(
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
                            val phaseElapsedSeconds =
                                ((scanProgress.phaseElapsedMs + 999L) / 1_000L)
                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt()
                            Text(
                                text = when (scanProgress.phase) {
                                    LocalAudioScanPhase.PREPARING -> pluralStringResource(
                                        R.plurals.local_playlist_scan_progress_preparing,
                                        phaseElapsedSeconds,
                                        phaseElapsedSeconds
                                    )
                                    LocalAudioScanPhase.READING_DOWNLOAD_INDEX -> pluralStringResource(
                                        R.plurals.local_playlist_scan_progress_download_index,
                                        phaseElapsedSeconds,
                                        phaseElapsedSeconds
                                    )
                                    LocalAudioScanPhase.QUERYING_MEDIA_STORE -> pluralStringResource(
                                        R.plurals.local_playlist_scan_progress_media_store,
                                        phaseElapsedSeconds,
                                        phaseElapsedSeconds
                                    )
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
                                    LocalAudioScanPhase.BUILDING_ENTRIES -> stringResource(
                                        R.string.local_playlist_scan_progress_building,
                                        scanProgress.processed,
                                        scanProgress.total
                                    )
                                    LocalAudioScanPhase.COMPLETED -> pluralStringResource(
                                        R.plurals.local_playlist_scan_progress_completed,
                                        scanProgress.discoveredSongs,
                                        scanProgress.discoveredSongs
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
