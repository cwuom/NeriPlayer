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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.History
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.youtube.YouTubeFeatureGate
import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.PlaybackStatsHotPlaylist
import moe.ouom.neriplayer.data.stats.buildPlaybackStatsHotPlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.tab.toBiliPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.model.buildLocalArtistSummaries
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistDeleteResultGlobally
import moe.ouom.neriplayer.ui.util.shouldAllowCollapsingTopAppBar
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.LibraryViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.favoriteId
import moe.ouom.neriplayer.ui.util.rememberPlaylistDisplayCoverUrl
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialogContent
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
@Composable
internal fun LocalPlaylistList(
    playlists: List<LocalPlaylist>,
    listState: LazyListState,
    onCreate: (String) -> Unit,
    onClick: (LocalPlaylist) -> Unit,
    onArtistClick: (LocalArtistSummary) -> Unit,
    onRename: (Long, String) -> Unit = { _, _ -> },
    onDelete: (List<Long>) -> Unit = {},
    onReorder: (List<Long>) -> Unit = {},
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    var selectedLocalCategory by rememberSaveable {
        mutableIntStateOf(LOCAL_CATEGORY_PLAYLIST)
    }
    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var localArtistSortMode by rememberSaveable {
        mutableStateOf(readLocalArtistSortMode(context))
    }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteSelectedConfirm by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val defaultPlaylistName = composeResources.getString(R.string.library_create_playlist_default)
    val maxNameLength = LocalPlaylistRepository.MAX_PLAYLIST_NAME_LENGTH
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsStateWithLifecycle(
        initialValue = false
    )
    val localArtists = remember(playlists, context) {
        buildLocalArtistSummaries(playlists, context)
    }
    val filteredLocalArtists = remember(localArtists, localSearchQuery) {
        filterLocalArtists(localArtists, localSearchQuery)
    }
    val displayedLocalArtists = remember(filteredLocalArtists, localArtistSortMode) {
        sortLocalArtists(filteredLocalArtists, localArtistSortMode)
    }
    val editablePlaylists = remember(playlists, context) {
        playlists.filterNot { SystemLocalPlaylists.isSystemPlaylist(it, context) }
    }
    val reorderablePlaylists = remember(editablePlaylists) {
        mutableStateListOf<LocalPlaylist>().apply {
            addAll(editablePlaylists)
        }
    }

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

    LaunchedEffect(editablePlaylists) {
        val validIds = editablePlaylists.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
        if (selectionMode && editablePlaylists.isEmpty()) {
            exitSelection()
        }
    }

    fun tryCreate(): Boolean {
        val trimmedInput = newName.trim().take(maxNameLength)
        val finalName = trimmedInput.ifBlank { defaultPlaylistName }.take(maxNameLength)

        val favoritesName = composeResources.getString(R.string.favorite_my_music)
        val localFilesName = composeResources.getString(R.string.local_files)
        if (FavoritesPlaylist.matches(finalName, context)) {
            nameError = composeResources.getString(R.string.library_name_reserved, favoritesName)
            return false
        }
        if (LocalFilesPlaylist.matches(finalName, context)) {
            nameError = composeResources.getString(R.string.library_name_reserved, localFilesName)
            return false
        }
        if (playlists.any { it.name.equals(finalName, ignoreCase = true) }) {
            nameError = composeResources.getString(R.string.library_name_exists)
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

    val displayedFavoritesPlaylist = favoritesPlaylist
        ?.takeIf { playlist -> playlist.matchesLocalPlaylistSearch(localSearchQuery, context) }
    val displayedLocalFilesPlaylist = localFilesPlaylist
        ?.takeIf { playlist -> playlist.matchesLocalPlaylistSearch(localSearchQuery, context) }
    val displayedPlaylists = reorderablePlaylists
        .filter { playlist -> playlist.matchesLocalPlaylistSearch(localSearchQuery, context) }
    val hasPlaylistSearchMatches =
        displayedFavoritesPlaylist != null ||
            displayedPlaylists.isNotEmpty() ||
            displayedLocalFilesPlaylist != null
    val windowWidthDp = currentWindowWidthDp()
    val localArtistColumnCount = remember(windowWidthDp) {
        ((windowWidthDp.value - 16f + 10f) / 130f).toInt().coerceAtLeast(1)
    }
    val localArtistRows = remember(displayedLocalArtists, localArtistColumnCount) {
        displayedLocalArtists.chunked(localArtistColumnCount)
    }

    LazyColumn(
        state = reorderState.listState,
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 8.dp + miniPlayerHeight
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .reorderable(reorderState)
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        item(key = "local_library_header") {
            LocalLibraryHeaderContent(
                selectedLocalCategory = selectedLocalCategory,
                selectionMode = selectionMode,
                searchQuery = localSearchQuery,
                onSearchQueryChange = { localSearchQuery = it },
                artistSortMode = localArtistSortMode,
                onArtistSortModeChange = { sortMode ->
                    localArtistSortMode = sortMode
                    persistLocalArtistSortMode(context, sortMode)
                },
                onPlaylistSelected = {
                    if (selectedLocalCategory != LOCAL_CATEGORY_PLAYLIST) {
                        exitSelection()
                        selectedLocalCategory = LOCAL_CATEGORY_PLAYLIST
                    }
                },
                onArtistSelected = {
                    if (selectedLocalCategory != LOCAL_CATEGORY_ARTIST) {
                        exitSelection()
                        selectedLocalCategory = LOCAL_CATEGORY_ARTIST
                    }
                }
            )
        }

        if (selectedLocalCategory == LOCAL_CATEGORY_ARTIST) {
            if (displayedLocalArtists.isEmpty()) {
                item(key = "local_artist_empty") {
                    LibrarySearchEmptyCard(
                        titleResId = if (localSearchQuery.isBlank()) {
                            R.string.library_local_artist_empty
                        } else {
                            R.string.library_local_search_empty
                        },
                        hintResId = if (localSearchQuery.isBlank()) {
                            R.string.library_local_artist_hint
                        } else {
                            R.string.library_local_search_empty_hint
                        },
                        iconIsArtist = true
                    )
                }
            }
            items(
                items = localArtistRows,
                key = { row -> row.joinToString(separator = "|") { artist -> artist.stableKey } }
            ) { rowArtists ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                ) {
                    rowArtists.forEach { artist ->
                        Box(modifier = Modifier.weight(1f)) {
                            LocalArtistGridCard(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                                offlineMode = offlineMode
                            )
                        }
                    }
                    repeat(localArtistColumnCount - rowArtists.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
                if (selectionMode) {
                    item(key = "local_playlist_selection_header") {
                        val allSelected =
                            selectedIds.size == displayedPlaylists.size && displayedPlaylists.isNotEmpty()
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
                        headlineContent = {
                            Text(
                                pluralStringResource(
                                    R.plurals.common_selected_count,
                                    selectedIds.size,
                                    selectedIds.size
                                )
                            )
                        },
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
                                            displayedPlaylists.map { it.id }.toSet()
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
        if (localSearchQuery.isBlank()) {
            item(key = "local_playlist_create") {
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
                MiuixSettingsDialog(
                    onDismissRequest = {
                        showDialog = false
                        newName = ""
                        nameError = null
                    },
                    title = { Text(stringResource(R.string.playlist_create)) },
                    text = {
                        MiuixSettingsDialogContent(verticalSpacing = 12.dp) {
                            MiuixSettingsTextField(
                                value = newName,
                                onValueChange = {
                                    newName = it.take(maxNameLength)
                                    if (nameError != null) nameError = null
                                },
                                placeholder = { Text(stringResource(R.string.playlist_enter_name)) },
                                singleLine = true,
                                modifier = Modifier.focusRequester(focusRequester),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { tryCreate() })
                            )
                            if (nameError != null) {
                                Text(
                                    text = nameError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    confirmButton = {
                        MiuixSettingsButton(
                            onClick = { tryCreate() },
                            enabled = newName.trim().isNotBlank()
                        ) {
                            Text(stringResource(R.string.action_create))
                        }
                    },
                    dismissButton = {
                        MiuixSettingsTextButton(
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
                            pluralStringResource(
                                R.plurals.library_delete_selected_confirm,
                                selectedIds.size,
                                selectedIds.size
                            )
                        )
                    },
                    confirmButton = {
                        HapticTextButton(
                            onClick = {
                                val idsToDelete = selectedIds.toList()
                                exitSelection()
                                onDelete(idsToDelete)
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
        }

        if (localSearchQuery.isNotBlank() && !hasPlaylistSearchMatches) {
            item(key = "local_playlist_search_empty") {
                LibrarySearchEmptyCard(
                    titleResId = R.string.library_local_search_empty,
                    hintResId = R.string.library_local_search_empty_hint,
                    iconIsArtist = false
                )
            }
        }

        displayedFavoritesPlaylist?.let { system ->
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
                                val cover = rememberPlaylistDisplayCoverUrl(
                                    playlist = system,
                                    resolveLocalFallback = true
                                )
                                if (!cover.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = fastScrollableImageRequest(
                                            context = context,
                                            data = cover,
                                            sizePx = 160,
                                            offlineMode = offlineMode
                                        ),
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
                items = displayedPlaylists,
                key = { it.id }
            ) { pl ->
            ReorderableItem(state = reorderState, key = pl.id) { _ ->
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
                                val cover = rememberPlaylistDisplayCoverUrl(
                                    playlist = pl,
                                    resolveLocalFallback = true
                                )
                                if (!cover.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = fastScrollableImageRequest(
                                            context = context,
                                            data = cover,
                                            sizePx = 160,
                                            offlineMode = offlineMode
                                        ),
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
                    MiuixSettingsDialog(
                        onDismissRequest = { showRenameDialog = false },
                        title = { Text(stringResource(R.string.action_rename)) },
                        text = {
                            MiuixSettingsDialogContent(verticalSpacing = 12.dp) {
                                MiuixSettingsTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it.take(maxNameLength) },
                                    placeholder = { Text(displayName) },
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            MiuixSettingsButton(
                                onClick = {
                                    val trimmed = renameText.trim().take(maxNameLength)
                                    if (trimmed.isNotBlank()) {
                                        onRename(pl.id, trimmed)
                                        showRenameDialog = false
                                    }
                                },
                                enabled = renameText.trim().isNotBlank()
                            ) { Text(stringResource(R.string.action_confirm)) }
                        },
                        dismissButton = {
                            MiuixSettingsTextButton(
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
                                    val playlistId = pl.id
                                    showDeleteDialog = false
                                    onDelete(listOf(playlistId))
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

        displayedLocalFilesPlaylist?.let { system ->
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
                                val cover = rememberPlaylistDisplayCoverUrl(
                                    playlist = system,
                                    resolveLocalFallback = true
                                )
                                if (!cover.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = fastScrollableImageRequest(
                                            context = context,
                                            data = cover,
                                            sizePx = 160,
                                            offlineMode = offlineMode
                                        ),
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
}

@Composable
internal fun LocalLibraryHeaderContent(
    selectedLocalCategory: Int,
    selectionMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    artistSortMode: LocalArtistSortMode,
    onArtistSortModeChange: (LocalArtistSortMode) -> Unit,
    onPlaylistSelected: () -> Unit,
    onArtistSelected: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        LocalCategoryTabs(
            selectedCategory = selectedLocalCategory,
            onPlaylistSelected = onPlaylistSelected,
            onArtistSelected = onArtistSelected
        )
        if (!selectionMode) {
            if (selectedLocalCategory == LOCAL_CATEGORY_ARTIST) {
                LocalArtistSearchAndSortRow(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    sortMode = artistSortMode,
                    onSortModeChange = onArtistSortModeChange
                )
            } else {
                LibraryInlineSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    placeholderResId = R.string.library_local_playlist_search_hint
                )
            }
        }
    }
}

@Composable
internal fun LocalArtistSearchAndSortRow(
    query: String,
    onQueryChange: (String) -> Unit,
    sortMode: LocalArtistSortMode,
    onSortModeChange: (LocalArtistSortMode) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.library_local_artist_search_hint)) },
            singleLine = true,
            shape = LibrarySearchFieldShape,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    HapticIconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_clear)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        Box {
            HapticIconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.library_local_artist_sort)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                LocalArtistSortMenuItem(
                    selected = sortMode == LocalArtistSortMode.SONG_COUNT,
                    text = stringResource(R.string.library_local_artist_sort_count),
                    onClick = {
                        onSortModeChange(LocalArtistSortMode.SONG_COUNT)
                        menuExpanded = false
                    }
                )
                LocalArtistSortMenuItem(
                    selected = sortMode == LocalArtistSortMode.RECENT_ADDED,
                    text = stringResource(R.string.library_local_artist_sort_recent),
                    onClick = {
                        onSortModeChange(LocalArtistSortMode.RECENT_ADDED)
                        menuExpanded = false
                    }
                )
                LocalArtistSortMenuItem(
                    selected = sortMode == LocalArtistSortMode.NAME,
                    text = stringResource(R.string.library_local_artist_sort_name),
                    onClick = {
                        onSortModeChange(LocalArtistSortMode.NAME)
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun LocalArtistSortMenuItem(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(text)
        },
        leadingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.common_selected)
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        },
        onClick = onClick
    )
}

@Composable
internal fun LocalCategoryTabs(
    selectedCategory: Int,
    onPlaylistSelected: () -> Unit,
    onArtistSelected: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            tintColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedCategory,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedCategory == LOCAL_CATEGORY_PLAYLIST,
                    onClick = onPlaylistSelected,
                    text = { Text(stringResource(R.string.library_favorite_tab_playlists)) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null
                        )
                    }
                )
                Tab(
                    selected = selectedCategory == LOCAL_CATEGORY_ARTIST,
                    onClick = onArtistSelected,
                    text = { Text(stringResource(R.string.library_favorite_tab_artists)) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

@Composable
internal fun LibraryInlineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholderResId: Int
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(placeholderResId)) },
        singleLine = true,
        shape = LibrarySearchFieldShape,
        trailingIcon = {
            if (query.isNotEmpty()) {
                HapticIconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
    )
}

@Composable
internal fun LibrarySearchEmptyCard(
    titleResId: Int,
    hintResId: Int,
    iconIsArtist: Boolean
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        ListItem(
            headlineContent = { Text(stringResource(titleResId)) },
            supportingContent = {
                Text(
                    stringResource(hintResId),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Icon(
                    imageVector = if (iconIsArtist) {
                        Icons.Filled.AccountCircle
                    } else {
                        Icons.AutoMirrored.Filled.QueueMusic
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
            }
        )
    }
}

internal fun filterLocalArtists(
    artists: List<LocalArtistSummary>,
    query: String
): List<LocalArtistSummary> {
    if (query.isBlank()) return artists
    return artists.filter { artist -> artist.matchesLocalArtistSearch(query) }
}

internal fun sortLocalArtists(
    artists: List<LocalArtistSummary>,
    sortMode: LocalArtistSortMode
): List<LocalArtistSummary> {
    return when (sortMode) {
        LocalArtistSortMode.SONG_COUNT -> artists.sortedWith(
            compareByDescending<LocalArtistSummary> { artist ->
                artist.songs.size
            }.thenBy { artist ->
                artist.name.lowercase()
            }
        )
        LocalArtistSortMode.RECENT_ADDED -> artists.sortedWith(
            compareByDescending<LocalArtistSummary> { artist ->
                artist.coverSong?.addedAt ?: 0L
            }.thenBy { artist ->
                artist.name.lowercase()
            }
        )
        LocalArtistSortMode.NAME -> artists.sortedBy { artist ->
            artist.name.lowercase()
        }
    }
}

internal fun LocalArtistSummary.matchesLocalArtistSearch(query: String): Boolean {
    return queryMatches(query, name) ||
        songs.any { song ->
            queryMatches(
                query,
                song.displayName(),
                song.displayArtist(),
                song.album,
                song.localFileName
            )
        }
}

internal fun LocalPlaylist.matchesLocalPlaylistSearch(query: String, context: Context): Boolean {
    if (query.isBlank()) return true
    val displayName = SystemLocalPlaylists.resolve(id, name, context)?.currentName ?: name
    return queryMatches(query, id, name, displayName, songs.size) ||
        songs.any { song ->
            queryMatches(
                query,
                song.displayName(),
                song.displayArtist(),
                song.album,
                song.localFileName
            )
        }
}

internal fun filterFavoritePlaylists(
    favorites: List<FavoritePlaylist>,
    query: String
): List<FavoritePlaylist> {
    if (query.isBlank()) return favorites
    return favorites.filter { favorite -> favorite.matchesFavoriteSearch(query) }
}

internal fun FavoritePlaylist.matchesFavoriteSearch(query: String): Boolean {
    return queryMatches(
        query,
        id,
        name,
        subtitle,
        source,
        browseId,
        playlistId,
        trackCount,
        favoriteSourceSearchAliases(source)
    ) || songs.any { song ->
        queryMatches(
            query,
            song.displayName(),
            song.displayArtist(),
            song.album,
            song.localFileName
        )
    }
}

internal fun favoriteSourceSearchAliases(source: String): List<String> {
    return when (source) {
        "youtubeMusic" -> listOf("YouTube", "YouTube Music")
        "neteaseAlbum" -> listOf("Netease Album", "网易云专辑", "专辑")
        "netease" -> listOf("Netease", "网易云", "歌单")
        "bili" -> listOf("Bilibili", "哔哩哔哩", "B站")
        FAVORITE_SOURCE_NETEASE_ARTIST -> listOf("Artist", "歌手")
        else -> listOf(source)
    }
}

internal fun queryMatches(query: String, vararg values: Any?): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return true
    return values.any { value ->
        when (value) {
            null -> false
            is Iterable<*> -> value.any { item ->
                item?.toString()?.contains(normalizedQuery, ignoreCase = true) == true
            }
            else -> value.toString().contains(normalizedQuery, ignoreCase = true)
        }
    }
}
