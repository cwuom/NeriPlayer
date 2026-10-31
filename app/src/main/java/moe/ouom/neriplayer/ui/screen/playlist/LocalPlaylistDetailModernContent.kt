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

import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.stableKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.download.DownloadedSongDeleteProgressCard
import moe.ouom.neriplayer.ui.component.download.isDownloadedSongDeletionRunning
import moe.ouom.neriplayer.ui.component.download.shouldShowDownloadedSongDeleteProgress
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistSelectionActions
import moe.ouom.neriplayer.ui.screen.playlist.curation.LocalCurationEntryAction
import moe.ouom.neriplayer.ui.screen.playlist.curation.LocalCurationSheet
import moe.ouom.neriplayer.ui.screen.playlist.insert.PlaylistInsertDialog
import moe.ouom.neriplayer.ui.screen.playlist.insert.PlaylistInsertPreview
import moe.ouom.neriplayer.ui.screen.playlist.insert.isPlaylistInsertPreviewCurrent
import moe.ouom.neriplayer.ui.screen.playlist.reorder.LocalPlaylistReorderableItem
import moe.ouom.neriplayer.ui.screen.playlist.reorder.localPlaylistDetectReorder
import moe.ouom.neriplayer.ui.screen.playlist.reorder.localPlaylistReorderable
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistDeleteResultGlobally
import moe.ouom.neriplayer.ui.component.local.LocalSongDetailsDialog
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.ui.screen.playback.shouldConfirmFavoriteSync
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailFrame
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailHeader
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailSearch
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernSongRow
import moe.ouom.neriplayer.R as AppR


@Composable
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)
internal fun LocalPlaylistDetailModernContent(
    contentScope: LocalPlaylistDetailModernContentScope
) {
    with(contentScope) {
        val currentContentScope by rememberUpdatedState(contentScope)
        SideEffect {
            reorderState.updatePresentedOrder { key ->
                val songIndex = displayedSongs.indexOfFirst { it.stableKey() == key }
                if (songIndex < 0) -1 else resolveLocalPlaylistSongListIndex(
                    songIndex, metadataProcessingVisible
                )
            }
        }
        val deleteProgress by vm.downloadedSongDeleteProgress.collectAsStateWithLifecycle()
        val deleteFailureDismissed by
        vm.downloadedSongDeleteFailureDismissed.collectAsStateWithLifecycle()
        var deletingSongCount by remember(playlistId) { mutableIntStateOf(0) }
        var showInsertDialog by remember(playlistId) { mutableStateOf(false) }
        var showBiliBinding by remember(playlistId) { mutableStateOf(false) }
        var insertInProgress by remember(playlistId) { mutableStateOf(false) }
        var curationSongs by remember(playlistId) { mutableStateOf<List<SongItem>?>(null) }
        val curatableTabSongs = remember(tabSongs) { tabSongs.filter { it.isLocalSong() } }
        val selectedDownloadableSongs = remember(selectedSongsForAction) {
            playlistDownloadCandidates(selectedSongsForAction)
        }
        LaunchedEffect(selectionMode, canReorderCurrentSongs) {
            if (!selectionMode || !canReorderCurrentSongs) showInsertDialog = false
        }

        suspend fun scrollToInsertedSongs(newOrder: List<SongIdentity>, movedKeys: Set<String>) {
            val ready = withTimeoutOrNull(PLAYLIST_INSERT_SCROLL_SYNC_TIMEOUT_MS) {
                snapshotFlow {
                    val current = currentContentScope
                    current.playlistId == playlistId &&
                        current.playlist.songs.map { it.identity() } == newOrder &&
                        current.localSongs.map { it.identity() } == newOrder
                }.first { it }
            }
            if (ready != true) return
            // 等新顺序完成布局，再按稳定身份定位，搜索过滤和固定头部都会改变列表索引
            withFrameNanos { }
            val current = currentContentScope
            if (current.playlistId != playlistId ||
                !current.canReorderCurrentSongs || current.blockSync ||
                repo.playlists.value.firstOrNull { it.id == playlistId }
                    ?.songs?.map { it.identity() } != newOrder
            ) return
            val songIndex = current.displayedSongs.indexOfFirst { it.stableKey() in movedKeys }
            if (songIndex >= 0) {
                current.reorderState.listState.animateScrollToItem(
                    resolveLocalPlaylistSongListIndex(
                        songIndex = songIndex,
                        metadataProcessingVisible = current.metadataProcessingVisible
                    )
                )
            }
        }

        fun showInsertSuccess(
            preview: PlaylistInsertPreview,
            originalOrder: List<SongIdentity>,
            newOrder: List<SongIdentity>
        ) {
            scope.launch feedback@{
                val scrollJob = launch {
                    scrollToInsertedSongs(newOrder, preview.movedKeys.toSet())
                }
                // 每次成功提示都对应独立撤销快照，不能按相同文案去重
                snackbarHostState.currentSnackbarData?.dismiss()
                val action = snackbarHostState.showSnackbar(
                    message = composeResources.getString(
                        AppR.string.playlist_insert_success,
                        preview.startPosition
                    ),
                    actionLabel = composeResources.getString(CoreCommonR.string.playlist_batch_export_undo),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long
                )
                if (action != SnackbarResult.ActionPerformed) return@feedback
                scrollJob.cancel()
                if (insertInProgress || blockSync || currentContentScope.playlistId != playlistId) {
                    snackbarHostState.showNeriSnackbar(
                        composeResources.getString(AppR.string.playlist_insert_undo_stale)
                    )
                    return@feedback
                }
                insertInProgress = true
                val undo = try {
                    runLocalPlaylistMutationSafely("undoPlaylistInsert") {
                        repo.reorderSongs(
                            playlistId = playlistId,
                            newOrder = originalOrder,
                            expectedOrder = newOrder
                        )
                    }
                } finally {
                    insertInProgress = false
                }
                val undoMessage = when {
                    undo.isFailure -> AppR.string.playlist_insert_undo_failed
                    undo.getOrNull() != true -> AppR.string.playlist_insert_undo_stale
                    else -> AppR.string.playlist_insert_undone
                }
                snackbarHostState.showNeriSnackbar(composeResources.getString(undoMessage))
            }
        }

        val deletionInProgress = deletingSongCount > 0 ||
                isDownloadedSongDeletionRunning(deleteProgress)
        PlaylistModernDetailFrame(
            header = PlaylistModernDetailHeader(
                key = playlistId,
                headerKey = headerKey,
                title = headerDisplayName,
                coverUrl = headerCover,
                subtitle = stringResource(
                    CoreCommonR.string.local_playlist_total_duration,
                    totalDurationText,
                    tabSongs.size,
                    formatPlayCount(context, playlistPlayCount)
                ),
                offlineMode = offlineMode,
                hasCustomBackground = hasCustomBackground
            ),
            search = PlaylistModernDetailSearch(
                visible = showSearch,
                onVisibleChange = { showSearch = it },
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                inputState = searchInputState,
                focusRequester = searchFocusRequester,
                autoShowKeyboard = autoShowKeyboard,
                headerFocused = headerSearchFocused,
                onHeaderFocusChanged = { headerSearchFocused = it },
                dockedFocused = dockedSearchFocused,
                onDockedFocusChanged = { dockedSearchFocused = it }
            ),
            listState = reorderState.listState,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            selectionCount = if (selectionMode) selectedKeysState.value.size else null,
            onExitSelection = { exitSelectionMode() },
            onLocatePlaying = if (currentIndexInDisplay >= 0) {
                {
                    scope.launch {
                        reorderState.listState.animateScrollToItem(
                            resolveLocalPlaylistPlayingItemIndex(
                                songIndex = currentIndexInDisplay,
                                metadataProcessingVisible = metadataProcessingVisible
                            )
                        )
                    }
                }
            } else null,
            listModifier = Modifier.localPlaylistReorderable(reorderState),
            normalActions = { playlistTopBarContentColor ->
                if (hasDownloadManagerEntry) {
                    HapticIconButton(
                        onClick = { showDownloadManager = true }
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(CoreCommonR.string.cd_download_manager),
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
                            contentDescription = stringResource(CoreCommonR.string.download_scan_local)
                        )
                    }
                }
                if (curatableTabSongs.isNotEmpty()) {
                    LocalCurationEntryAction(
                        onClick = { curationSongs = curatableTabSongs }
                    )
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
                                contentDescription = stringResource(CoreCommonR.string.local_playlist_sync_netease_liked)
                            )
                        }
                    }
                }

                if (!isSystemPlaylist) {
                    HapticIconButton(onClick = { showBiliBinding = true }) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = stringResource(
                                CoreCommonR.string.bili_collection_binding_entry
                            )
                        )
                    }
                    HapticIconButton(onClick = {
                        renameText =
                            playlistNameFieldValue(playlist.name, maxNameLength)
                        renameError = null
                        showRename = true
                    }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(CoreCommonR.string.local_playlist_rename)
                        )
                    }
                    HapticIconButton(onClick = {
                        showDeletePlaylistConfirm = true
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(CoreCommonR.string.local_playlist_delete)
                        )
                    }
                }
            },
            selectionActions = {
                val displayedSongKeys = displayedSongs.map { it.stableKey() }.toSet()
                val allSelected = areDisplayedSongKeysSelected(
                    selectedKeys = selectedKeysState.value,
                    displayedKeys = displayedSongKeys
                )
                if (selectedDownloadableSongs.isNotEmpty()) {
                    HapticIconButton(
                        onClick = {
                            val selectedSongs = selectedDownloadableSongs
                            if (selectedSongs.isNotEmpty()) {
                                showDownloadManager = true
                                exitSelectionMode()
                                GlobalDownloadManager.startBatchDownload(
                                    context,
                                    selectedSongs
                                )
                            }
                        },
                        enabled = !insertInProgress
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(CoreCommonR.string.cd_download_selected)
                        )
                    }
                }
                HapticIconButton(
                    onClick = {
                        if (selectedKeysState.value.isNotEmpty()) {
                            showDeleteMultiConfirm = true
                        }
                    },
                    enabled = selectedKeysState.value.isNotEmpty() &&
                            !insertInProgress &&
                            !(isLocalFilesPlaylist &&
                                    selectedLocalFilesTab == LocalFilesSongTab.DOWNLOADED &&
                                    deletionInProgress)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(CoreCommonR.string.common_delete_selected)
                    )
                }
                PlaylistSelectionActions(
                    allSelected = allSelected,
                    onToggleSelectAll = {
                        selectedKeysState.value = toggleDisplayedSongSelection(
                            selectedKeys = selectedKeysState.value,
                            displayedKeys = displayedSongKeys
                        )
                    },
                    canExport = selectedKeysState.value.isNotEmpty(),
                    onExport = { showExportSheet = true },
                    canSync = selectedKeysState.value.isNotEmpty() && !syncInProgress,
                    onSync = { openNeteaseRemotePlaylistPicker() },
                    canInsert = canReorderCurrentSongs &&
                            selectedKeysState.value.isNotEmpty() &&
                            !blockSync && !insertInProgress,
                    onInsert = if (canReorderCurrentSongs) {
                        { showInsertDialog = true }
                    } else {
                        null
                    },
                    canCurateLocalAssets = selectedSongsForAction.any { it.isLocalSong() },
                    onCurateLocalAssets = if (curatableTabSongs.isNotEmpty()) {
                        {
                            curationSongs = selectedSongsForAction.filter { it.isLocalSong() }
                            exitSelectionMode()
                        }
                    } else {
                        null
                    }
                )
            },
            beforeList = { playlistTopBarColor ->
                if (isLocalFilesPlaylist && shouldShowDownloadedSongDeleteProgress(
                        deleteProgress, deletingSongCount, deleteFailureDismissed
                    )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(playlistTopBarColor)
                    ) {
                        DownloadedSongDeleteProgressCard(
                            progress = deleteProgress,
                            failureDismissed = deleteFailureDismissed,
                            onDismissFailure = vm::dismissDownloadedSongDeleteFailure,
                            requestedSongCount = deletingSongCount,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            },
            playbackActions = {
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
                                        stringResource(CoreCommonR.string.local_files_manual_added)
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
                                        stringResource(CoreCommonR.string.local_files_downloaded)
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
            },
            content = {
                if (metadataProcessingVisible) {
                    item(
                        key = LOCAL_PLAYLIST_METADATA_PROCESSING_KEY,
                        contentType = "local_metadata_processing"
                    ) {
                        PlaylistModernListItemSurface(
                            coverUrl = headerCover,
                            offlineMode = offlineMode
                        ) {
                            LocalMetadataProcessingCard(
                                visibleMetadataProcessingState
                            )
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
                    LocalPlaylistReorderableItem(
                        state = reorderState,
                        key = songKey,
                        animatePlacement = selectionMode && canReorderCurrentSongs
                    ) { isDragging ->
                        val rowScale by animateFloatAsState(
                            targetValue = if (isDragging) 1.02f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "row-scale"
                        )
                        val isFavoriteSong = favoriteSongLookup.contains(song)
                        PlaylistModernSongRow(
                            song = song,
                            index = revIndex,
                            coverUrl = headerCover,
                            offlineMode = offlineMode,
                            selectionMode = selectionMode,
                            selected = songKey in selectedKeysState.value,
                            current = currentSongLookup.contains(song),
                            playing = isPlaying,
                            downloaded = songKey in downloadedSongKeys,
                            downloadPresenceVersion = downloadPresenceVersion,
                            isDragging = isDragging,
                            modifier = Modifier.graphicsLayer {
                                scaleX = rowScale
                                scaleY = rowScale
                            },
                            onClick = {
                                if (selectionMode) toggleSelect(songKey)
                                else {
                                    val pos = queueIndexBySongKey[songKey] ?: -1
                                    if (pos >= 0) onSongClick(tabSongs, pos)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedKeysState.value = setOf(songKey)
                                } else toggleSelect(songKey)
                            },
                            onToggleSelection = { toggleSelect(songKey) }
                        ) {
                            if (!selectionMode) {
                                var showMoreMenu by remember {
                                    mutableStateOf(
                                        false
                                    )
                                }
                                Box {
                                    IconButton(
                                        onClick = {
                                            showMoreMenu = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(
                                                CoreCommonR.string.cd_more_actions
                                            ),
                                            tint = playlistModernListSecondaryContentColor()
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showMoreMenu,
                                        onDismissRequest = {
                                            showMoreMenu = false
                                        }
                                    ) {
                                        if (song.isLocalSong()) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(
                                                            CoreCommonR.string.local_song_open_details
                                                        )
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Info,
                                                        contentDescription = null
                                                    )
                                                },
                                                onClick = {
                                                    detailSong =
                                                        song
                                                    showMoreMenu =
                                                        false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(
                                                            CoreCommonR.string.action_share
                                                        )
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Share,
                                                        contentDescription = null
                                                    )
                                                },
                                                onClick = {
                                                    showMoreMenu =
                                                        false
                                                    scope.launch {
                                                        val shared =
                                                            runCatching {
                                                                LocalMediaSupport.shareSongFile(
                                                                    context,
                                                                    song
                                                                )
                                                            }.getOrElse { false }
                                                        if (!shared) {
                                                            snackbarHostState.showNeriSnackbar(
                                                                composeResources.getString(
                                                                    CoreCommonR.string.local_song_share_failed
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(CoreCommonR.string.local_playlist_play_next)
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                PlayerManager.addToQueueNext(
                                                    song
                                                )
                                                showMoreMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(CoreCommonR.string.playlist_add_to_end)
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                                                    contentDescription = null
                                                )
                                            },
                                            onClick = {
                                                PlayerManager.addToQueueEnd(
                                                    song
                                                )
                                                showMoreMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(
                                                        if (isFavoriteSong) {
                                                            CoreCommonR.string.favorite_remove
                                                        } else {
                                                            CoreCommonR.string.favorite_add
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
                                                toggleSongFavorite(
                                                    song,
                                                    isFavoriteSong
                                                )
                                                showMoreMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(CoreCommonR.string.action_copy_song_info)
                                                )
                                            },
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
                                                    copyText(
                                                        songInfo
                                                    )
                                                    snackbarHostState.showNeriSnackbar(
                                                        composeResources.getString(
                                                            CoreCommonR.string.toast_copied
                                                        )
                                                    )
                                                }
                                                showMoreMenu = false
                                            }
                                        )
                                    }
                                }
                            } else if (canReorderCurrentSongs) {
                                Box(
                                    modifier = Modifier
                                        .then(if (insertInProgress) {
                                            Modifier
                                        } else {
                                            Modifier.localPlaylistDetectReorder(reorderState, songKey)
                                        })
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = stringResource(
                                            CoreCommonR.string.common_drag_handle
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(40.dp))
                            }
                        }
                    }
                }
            },
            overlays = {
                // 删除歌单二次确认
                if (showDeletePlaylistConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeletePlaylistConfirm = false },
                        title = { Text(stringResource(CoreCommonR.string.local_playlist_delete)) },
                        text = { Text(stringResource(CoreCommonR.string.local_playlist_delete_confirm)) },
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
                            }) { Text(stringResource(CoreCommonR.string.action_delete)) }
                        },
                        dismissButton = {
                            HapticTextButton(onClick = {
                                showDeletePlaylistConfirm = false
                            }) { Text(stringResource(CoreCommonR.string.action_cancel)) }
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
                                        CoreCommonR.string.local_files_delete_downloaded_title
                                    } else {
                                        CoreCommonR.string.local_playlist_delete_songs
                                    }
                                )
                            )
                        },
                        text = {
                            Text(
                                if (deletesDownloadedSongs) {
                                    pluralStringResource(
                                        CoreCommonR.plurals.local_files_delete_downloaded_confirm,
                                        count,
                                        count
                                    )
                                } else {
                                    pluralStringResource(
                                        AppR.plurals.local_playlist_delete_songs_confirm,
                                        count,
                                        count
                                    )
                                }
                            )
                        },
                        confirmButton = {
                            HapticTextButton(
                                enabled = !deletesDownloadedSongs || !deletionInProgress,
                                onClick = {
                                    if (deletesDownloadedSongs) {
                                        val songsToDelete = selectedDownloadedSongsForAction
                                        deletingSongCount = songsToDelete.size
                                        showDeleteMultiConfirm = false
                                        exitSelectionMode()
                                        vm.deleteDownloadedSongs(songsToDelete) { result ->
                                            deletingSongCount = 0
                                            scope.launch {
                                                val message = when {
                                                    result.physicalCleanupPending -> {
                                                        context.resources.getQuantityString(
                                                            AppR.plurals.local_files_delete_downloaded_cleanup_pending,
                                                            result.deletedCount + result.notDeletedCount,
                                                            result.deletedCount + result.notDeletedCount
                                                        )
                                                    }

                                                    result.deletedCount > 0 && result.notDeletedCount == 0 -> {
                                                        context.resources.getQuantityString(
                                                            AppR.plurals.local_files_delete_downloaded_success,
                                                            result.deletedCount,
                                                            result.deletedCount
                                                        )
                                                    }

                                                    result.deletedCount > 0 -> {
                                                        composeResources.getQuantityString(
                                                            CoreCommonR.plurals.local_files_delete_downloaded_partial,
                                                            result.deletedCount,
                                                            result.deletedCount,
                                                            result.notDeletedCount
                                                        )
                                                    }

                                                    else -> {
                                                        composeResources.getString(
                                                            CoreCommonR.string.local_files_delete_downloaded_failed
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
                                        val removeIdentities =
                                            songsToRemove.map { it.identity() }.toSet()
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
                                }) {
                                Text(
                                    stringResource(
                                        CoreCommonR.string.local_playlist_delete_count,
                                        count
                                    )
                                )
                            }
                        },
                        dismissButton = {
                            HapticTextButton(onClick = {
                                showDeleteMultiConfirm = false
                            }) { Text(stringResource(CoreCommonR.string.action_cancel)) }
                        }
                    )
                }

                curationSongs?.let { songs ->
                    LocalCurationSheet(
                        songs = songs,
                        onDismissRequest = { curationSongs = null }
                    )
                }

                // 多选导出
                if (showExportSheet) {
                    PlaylistExportSheet(
                        title = stringResource(CoreCommonR.string.local_playlist_export_to),
                        playlists = allPlaylists.filter {
                            it.id != playlist.id && !LocalFilesPlaylist.isSystemPlaylist(
                                it,
                                context
                            )
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
                                actionLabel = CoreCommonR.string.playlist_add_to
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
                                actionLabel = CoreCommonR.string.playlist_add_to,
                                warnForLocalSync = !FavoritesPlaylist.isSystemPlaylist(target, context) ||
                                    shouldConfirmFavoriteSync(songs, context)
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
                        title = stringResource(CoreCommonR.string.playlist_export_to_local),
                        playlists = allPlaylists.filter {
                            it.id != playlist.id && !LocalFilesPlaylist.isSystemPlaylist(
                                it,
                                context
                            )
                        },
                        selectedCount = tabSongs.size,
                        onDismissRequest = { showExportAllSheet = false },
                        onCreateAndExport = { name ->
                            val songs = tabSongs
                            launchWithLocalSyncWarning(
                                songs = songs,
                                actionLabel = CoreCommonR.string.playlist_add_to
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
                                actionLabel = CoreCommonR.string.playlist_add_to,
                                warnForLocalSync = !FavoritesPlaylist.isSystemPlaylist(target, context) ||
                                    shouldConfirmFavoriteSync(songs, context)
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
                                    CoreCommonR.string.local_playlist_sync_netease_partial_confirm_title
                                )
                            )
                        },
                        text = {
                            Text(
                                "${
                                    pluralStringResource(
                                        AppR.plurals.local_playlist_sync_netease_partial_confirm_unsupported,
                                        pending.unsupportedCount,
                                        pending.unsupportedCount
                                    )
                                } ${
                                    pluralStringResource(
                                        AppR.plurals.local_playlist_sync_netease_partial_confirm_target,
                                        supportedCount,
                                        supportedCount,
                                        pending.target.name
                                    )
                                }"
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
                                Text(stringResource(CoreCommonR.string.action_confirm))
                            }
                        },
                        dismissButton = {
                            HapticTextButton(
                                onClick = { pendingNeteaseRemoteSyncConfirm = null }
                            ) {
                                Text(stringResource(CoreCommonR.string.action_cancel))
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
                        onDismiss = { detailSong = null }
                    )
                }

                if (showNeteaseSyncConfirm) {
                    AlertDialog(
                        onDismissRequest = { showNeteaseSyncConfirm = false },
                        title = { Text(stringResource(CoreCommonR.string.local_playlist_sync_netease_confirm_title)) },
                        text = { Text(stringResource(CoreCommonR.string.local_playlist_sync_netease_confirm_message)) },
                        confirmButton = {
                            HapticTextButton(
                                onClick = {
                                    showNeteaseSyncConfirm = false
                                    openNeteaseSyncPreview()
                                }
                            ) { Text(stringResource(CoreCommonR.string.action_confirm)) }
                        },
                        dismissButton = {
                            HapticTextButton(
                                onClick = { showNeteaseSyncConfirm = false }
                            ) { Text(stringResource(CoreCommonR.string.action_cancel)) }
                        }
                    )
                }

                pendingSyncConfirmAction?.let { action ->
                    LocalSongSyncConfirmDialog(
                        actionLabel = stringResource(pendingSyncConfirmLabel),
                        onConfirm = {
                            pendingSyncConfirmAction = null
                            action()
                        },
                        onDismiss = {
                            pendingSyncConfirmAction = null
                        }
                    )
                }

                if (showInsertDialog && selectionMode && canReorderCurrentSongs) {
                    PlaylistInsertDialog(
                        songs = localSongs.toList(),
                        selectedKeys = selectedKeysState.value,
                        offlineMode = offlineMode,
                        onDismiss = { showInsertDialog = false },
                        onConfirm = { preview ->
                            val sourceSongs = localSongs.toList()
                            val currentKeys = sourceSongs.map { it.stableKey() }
                            if (blockSync || insertInProgress || !isPlaylistInsertPreviewCurrent(
                                    preview, currentKeys, selectedKeysState.value
                                )
                            ) {
                                showInsertDialog = false
                                scope.launch {
                                    snackbarHostState.showNeriSnackbar(
                                        composeResources.getString(AppR.string.playlist_insert_stale)
                                    )
                                }
                            } else {
                                val songsByKey = sourceSongs.associateBy { it.stableKey() }
                                val originalOrder = sourceSongs.map { it.identity() }
                                val newOrder = preview.orderedKeys.map { songsByKey.getValue(it).identity() }
                                showInsertDialog = false
                                insertInProgress = true
                                scope.launchLocalPlaylistMutation(
                                    operation = "insertPlaylistSongs",
                                    onResult = { result ->
                                        insertInProgress = false
                                        val message = when {
                                            result.isFailure -> AppR.string.playlist_insert_failed
                                            result.getOrNull() != true -> AppR.string.playlist_insert_stale
                                            else -> null
                                        }
                                        if (message != null) {
                                            scope.launch {
                                                snackbarHostState.showNeriSnackbar(
                                                    composeResources.getString(message)
                                                )
                                            }
                                        } else {
                                            showInsertSuccess(preview, originalOrder, newOrder)
                                        }
                                    }
                                ) {
                                    repo.reorderSongs(
                                        playlistId = playlistId,
                                        newOrder = newOrder,
                                        expectedOrder = originalOrder
                                    )
                                }
                            }
                        }
                    )
                }

                // 多选优先退出
                BackHandler(enabled = selectionMode) { exitSelectionMode() }
            }
        )

        if (showBiliBinding) {
            BiliCollectionBindingDialog(
                target = biliBindingTargetForPlaylist(playlist.id, playlist.name),
                songs = playlist.songs,
                onDismiss = { showBiliBinding = false }
            )
        }
    }
}

private const val PLAYLIST_INSERT_SCROLL_SYNC_TIMEOUT_MS = 1_500L
