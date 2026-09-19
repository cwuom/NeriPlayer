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


@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, DelicateCoroutinesApi::class)
internal fun LocalPlaylistDetailModernContent(
    contentScope: LocalPlaylistDetailModernContentScope
) {
    with(contentScope) {
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
                val searchDockedRevealProgress by remember(
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
                val playlistChromeCollapseProgress by remember(
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

                                if (hasDownloadManagerEntry) {
                                    HapticIconButton(
                                        onClick = { showDownloadManager = true }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Download,
                                            contentDescription = stringResource(R.string.cd_download_manager),
                                            tint = playlistTopBarContentColor
                                        )
                                    }
                                }

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
                                        selectedKeysState.value.size,
                                        selectedKeysState.value.size
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
                                HapticIconButton(
                                    onClick = { openNeteaseRemotePlaylistPicker() },
                                    enabled = selectedKeysState.value.isNotEmpty() && !syncInProgress
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Sync,
                                        contentDescription = stringResource(
                                            R.string.local_playlist_sync_netease_playlist
                                        )
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
                                        val selectedSongs = selectedSongsForAction
                                        if (selectedSongs.isNotEmpty()) {
                                            showDownloadManager = true
                                            exitSelectionMode()
                                            GlobalDownloadManager.startBatchDownload(
                                                context,
                                                selectedSongs
                                            )
                                        }
                                    },
                                    enabled = selectedSongsForAction.isNotEmpty()
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
                                                                        copyText(songInfo)
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
                    val count = selectedKeysState.value.size
                    val deletesDownloadedSongs = isLocalFilesPlaylist &&
                        selectedLocalFilesTab == LocalFilesSongTab.DOWNLOADED
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
                                pendingOrderIdentities = expectedSongs.map { it.identity() }
                                blockSync = true

                                localSongs.clear()
                                localSongs.addAll(expectedSongs)
                                showDeleteMultiConfirm = false
                                exitSelectionMode()

                                if (removeAll) {
                                    vm.clearSongs { result ->
                                        handleLocalSongDeleteResult(previousSongs, result)
                                    }
                                } else {
                                    vm.removeSongs(songsToRemove) { result ->
                                        handleLocalSongDeleteResult(previousSongs, result)
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
