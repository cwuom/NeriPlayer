package moe.ouom.neriplayer.ui.screen.tab.library

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
 * File: moe.ouom.neriplayer.ui.screen.tab.library/LibraryScreenFavoriteContent
 * Created: 2025/8/8
 */

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Bolt
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.isArtistFavoriteSource
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.tab.toBiliPlaylist
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.FollowedArtistImportViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.FollowedArtistImportError
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import org.burnoutcrew.reorderable.ItemPosition
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorder
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun FavoritePlaylistList(
    listState: LazyListState,
    onHotPlaylistClick: (PlaybackStatsPeriod) -> Unit,
    onNeteasePlaylistClick: (PlaylistSummary) -> Unit,
    onNeteaseAlbumClick: (AlbumSummary) -> Unit,
    onNeteaseArtistClick: (NeteaseArtistSummary) -> Unit,
    onBiliPlaylistClick: (BiliPlaylist) -> Unit,
    onYouTubeMusicPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onBiliUploaderClick: (BiliUploaderSummary) -> Unit,
    onYouTubeMusicCreatorClick: (YouTubeMusicCreatorSummary) -> Unit,
    offlineMode: Boolean,
    importViewModel: FollowedArtistImportViewModel = viewModel()
) {
    val context = LocalContext.current
    val favoriteRepo = remember(context) { FavoritePlaylistRepository.getInstance(context) }
    val favorites by favoriteRepo.favorites.collectAsStateWithLifecycle()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val scope = rememberCoroutineScope()
    val importState by importViewModel.uiState.collectAsStateWithLifecycle()
    val importFeedback = when (importState.error) {
        FollowedArtistImportError.LOGIN_REQUIRED -> stringResource(CoreCommonR.string.library_artist_import_login)
        FollowedArtistImportError.ACCOUNT_CHANGED -> stringResource(CoreCommonR.string.library_artist_import_account_changed)
        FollowedArtistImportError.FAILED -> stringResource(CoreCommonR.string.library_artist_import_failed)
        null -> importState.importedCount?.let { count ->
            if (count == 0) stringResource(CoreCommonR.string.library_artist_import_no_new)
            else pluralStringResource(CoreCommonR.plurals.library_artist_import_success, count, count)
        }
    }
    LaunchedEffect(importState, importFeedback) {
        if (importFeedback != null) {
            AppFeedback.show(context, message = importFeedback)
            importViewModel.consumeFeedback(importState)
        }
    }
    var selectedArtistPlatform by rememberSaveable { mutableStateOf(FavoriteArtistPlatform.NETEASE) }
    var sortMode by rememberSaveable { mutableStateOf(false) }
    var selectedFavoriteCategory by rememberSaveable {
        mutableIntStateOf(FAVORITE_CATEGORY_PLAYLIST)
    }
    var favoriteSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteSelectedConfirm by rememberSaveable { mutableStateOf(false) }
    val reorderableFavorites = remember { mutableStateListOf<FavoritePlaylist>() }
    val playlistFavorites = remember(favorites) {
        favorites.filterNot { isArtistFavoriteSource(it.source) }
    }
    val artistFavorites = remember(favorites, selectedArtistPlatform) {
        favorites.filter { it.source == selectedArtistPlatform.source }
    }
    val isHotCategory = selectedFavoriteCategory == FAVORITE_CATEGORY_HOT
    val visibleFavorites = remember(playlistFavorites, artistFavorites, selectedFavoriteCategory) {
        when (selectedFavoriteCategory) {
            FAVORITE_CATEGORY_ARTIST -> artistFavorites
            FAVORITE_CATEGORY_PLAYLIST -> playlistFavorites
            else -> emptyList()
        }
    }
    var hotPlaylistRetry by remember { mutableStateOf(0) }
    val hotPlaylistResult = if (isHotCategory) rememberHotPlaylists(hotPlaylistRetry) else null
    val hotPlaylists = hotPlaylistResult?.getOrNull()

    fun favoriteKey(favorite: FavoritePlaylist): String {
        return "${favorite.source}:${favorite.id}"
    }

    fun exitEditMode() {
        sortMode = false
        selectedKeys = emptySet()
        showDeleteSelectedConfirm = false
    }

    fun toggleSelection(key: String) {
        selectedKeys = if (selectedKeys.contains(key)) {
            selectedKeys - key
        } else {
            selectedKeys + key
        }
    }

    BackHandler(enabled = sortMode) { exitEditMode() }

    LaunchedEffect(visibleFavorites) {
        reorderableFavorites.clear()
        reorderableFavorites.addAll(visibleFavorites)
        val validKeys = visibleFavorites.map(::favoriteKey).toSet()
        selectedKeys = selectedKeys.intersect(validKeys)
        if (sortMode && visibleFavorites.isEmpty()) {
            exitEditMode()
        }
    }

    LaunchedEffect(sortMode, visibleFavorites) {
        if (sortMode && visibleFavorites.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    val reorderState = rememberReorderableLazyListState(
        listState = listState,
        onMove = { from: ItemPosition, to: ItemPosition ->
            if (!sortMode) return@rememberReorderableLazyListState
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState
            val fromIndex = reorderableFavorites.indexOfFirst { favoriteKey(it) == fromKey }
            val toIndex = reorderableFavorites.indexOfFirst { favoriteKey(it) == toKey }
            if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                reorderableFavorites.add(toIndex, reorderableFavorites.removeAt(fromIndex))
            }
        },
        canDragOver = { _, over -> sortMode && over.key is String },
        onDragEnd = { _, _ ->
            if (sortMode) {
                scope.launch {
                    favoriteRepo.reorderFavorites(
                        reorderableFavorites.map { "${it.source}:${it.id}" }
                    )
                }
            }
        }
    )

    LazyColumn(
        state = reorderState.listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .reorderable(reorderState)
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        val displayedFavorites = filterFavoritePlaylists(reorderableFavorites, favoriteSearchQuery)
        item(key = "favorite_category_tabs") {
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
                        selectedTabIndex = selectedFavoriteCategory,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Tab(
                            selected = selectedFavoriteCategory == FAVORITE_CATEGORY_PLAYLIST,
                            onClick = {
                                if (selectedFavoriteCategory != FAVORITE_CATEGORY_PLAYLIST) {
                                    selectedFavoriteCategory = FAVORITE_CATEGORY_PLAYLIST
                                    exitEditMode()
                                }
                            },
                            text = { Text(stringResource(CoreCommonR.string.library_favorite_tab_playlists)) },
                            icon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null
                                )
                            }
                        )
                        Tab(
                            selected = selectedFavoriteCategory == FAVORITE_CATEGORY_ARTIST,
                            onClick = {
                                if (selectedFavoriteCategory != FAVORITE_CATEGORY_ARTIST) {
                                    selectedFavoriteCategory = FAVORITE_CATEGORY_ARTIST
                                    exitEditMode()
                                }
                            },
                            text = { Text(stringResource(CoreCommonR.string.library_favorite_tab_artists)) },
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.AccountCircle,
                                    contentDescription = null
                                )
                            }
                        )
                        Tab(
                            selected = selectedFavoriteCategory == FAVORITE_CATEGORY_HOT,
                            onClick = {
                                if (selectedFavoriteCategory != FAVORITE_CATEGORY_HOT) {
                                    selectedFavoriteCategory = FAVORITE_CATEGORY_HOT
                                    exitEditMode()
                                }
                            },
                            text = { Text(stringResource(CoreCommonR.string.library_favorite_tab_hot)) },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Bolt,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }
        if (selectedFavoriteCategory == FAVORITE_CATEGORY_ARTIST) {
            item(key = "favorite_artist_platforms") {
                FavoriteArtistPlatformHeader(
                    selected = selectedArtistPlatform,
                    count = artistFavorites.size,
                    onSelect = {
                        if (selectedArtistPlatform != it) {
                            selectedArtistPlatform = it
                            exitEditMode()
                        }
                    },
                    importState = importState,
                    onImport = { importViewModel.importArtists(selectedArtistPlatform.source) },
                    offlineMode = offlineMode,
                    editMode = sortMode
                )
            }
        }
        if (!sortMode && !isHotCategory) {
            item(key = "favorite_search") {
                LibraryInlineSearchField(
                    query = favoriteSearchQuery,
                    onQueryChange = { favoriteSearchQuery = it },
                    placeholderResId = CoreCommonR.string.library_favorite_search_hint
                )
            }
        }
        if (sortMode && !isHotCategory) {
            item(key = "favorite_sort_mode_header") {
                val allSelected =
                    selectedKeys.size == displayedFavorites.size && displayedFavorites.isNotEmpty()
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
                                    CoreCommonR.plurals.common_selected_count,
                                    selectedKeys.size,
                                    selectedKeys.size
                                )
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            HapticIconButton(onClick = { exitEditMode() }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(CoreCommonR.string.action_exit_multi_select)
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HapticTextButton(
                                    onClick = {
                                        selectedKeys = if (allSelected) {
                                            emptySet()
                                        } else {
                                            displayedFavorites.map(::favoriteKey).toSet()
                                        }
                                    }
                                ) {
                                    Text(
                                        if (allSelected) {
                                            stringResource(CoreCommonR.string.action_deselect_all)
                                        } else {
                                            stringResource(CoreCommonR.string.action_select_all)
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                HapticTextButton(
                                    enabled = selectedKeys.isNotEmpty(),
                                    onClick = { showDeleteSelectedConfirm = true }
                                ) {
                                    Text(stringResource(CoreCommonR.string.common_delete_selected))
                                }
                            }
                        }
                    )
                }
            }
        }
        if (isHotCategory) {
            if (hotPlaylistResult?.isFailure == true) {
                item(key = "favorite_hot_failed") {
                    HapticTextButton(onClick = { hotPlaylistRetry += 1 }) { Text(stringResource(CoreCommonR.string.stats_load_failed)) }
                }
            } else if (hotPlaylists == null) {
                item(key = "favorite_hot_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(
                    items = hotPlaylists,
                    key = { playlist -> "hot_playlist_${playlist.period.name}" }
                ) { playlist ->
                    val titleResId = hotPlaylistTitleResId(playlist.period)
                    val subtitle = if (playlist.tracks.isEmpty()) {
                        stringResource(CoreCommonR.string.library_hot_empty_hint)
                    } else {
                        stringResource(
                            CoreCommonR.string.library_hot_playlist_summary,
                            playlist.trackCount,
                            formatPlayCount(context, playlist.totalPlayCount)
                        )
                    }
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
                            .clickable { onHotPlaylistClick(playlist.period) }
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(text = stringResource(titleResId))
                            },
                            supportingContent = {
                                Text(
                                    text = subtitle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        )
                    }
                }
            }
        } else if (displayedFavorites.isEmpty()) {
            item {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    val isArtistCategory = selectedFavoriteCategory == FAVORITE_CATEGORY_ARTIST
                    val isSearchEmpty = favoriteSearchQuery.isNotBlank() && visibleFavorites.isNotEmpty()
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (isSearchEmpty) {
                                        CoreCommonR.string.library_favorite_search_empty
                                    } else if (isArtistCategory) {
                                        CoreCommonR.string.library_no_favorite_artist
                                    } else {
                                        CoreCommonR.string.playlist_no_favorite
                                    }
                                )
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (isSearchEmpty) {
                                        CoreCommonR.string.library_favorite_search_empty_hint
                                    } else if (isArtistCategory) {
                                        CoreCommonR.string.library_favorite_artist_hint
                                    } else {
                                        CoreCommonR.string.playlist_favorite_hint
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent
                        ),
                        leadingContent = {
                            Icon(
                                imageVector = if (isArtistCategory) {
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
        } else {
            items(
                items = displayedFavorites,
                key = { favoriteKey(it) }
            ) { favorite ->
                val itemKey = favoriteKey(favorite)
                val isSelected = sortMode && selectedKeys.contains(itemKey)
                ReorderableItem(state = reorderState, key = itemKey) {
                    Card(
                        shape = cardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f)
                            } else if (sortMode) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            }
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .animateItem()
                            .clip(cardShape)
                            .combinedClickable(
                                onClick = {
                                    if (sortMode) {
                                        toggleSelection(itemKey)
                                        return@combinedClickable
                                    }
                                    when (favorite.source) {
                                        "netease" -> {
                                            onNeteasePlaylistClick(
                                                PlaylistSummary(
                                                    id = favorite.id,
                                                    name = favorite.name,
                                                    picUrl = favorite.coverUrl ?: "",
                                                    playCount = 0,
                                                    trackCount = favorite.trackCount
                                                )
                                            )
                                        }
                                        "neteaseAlbum" -> {
                                            onNeteaseAlbumClick(
                                                AlbumSummary(
                                                    id = favorite.id,
                                                    name = favorite.name,
                                                    picUrl = favorite.coverUrl.orEmpty(),
                                                    size = favorite.trackCount
                                                )
                                            )
                                        }
                                        FAVORITE_SOURCE_NETEASE_ARTIST -> {
                                            onNeteaseArtistClick(
                                                NeteaseArtistSummary(
                                                    id = favorite.id,
                                                    name = favorite.name
                                                )
                                            )
                                        }
                                        FAVORITE_SOURCE_BILI_ARTIST -> {
                                            favorite.toFavoriteBiliUploader()?.let(onBiliUploaderClick)
                                        }
                                        FAVORITE_SOURCE_YOUTUBE_ARTIST -> {
                                            favorite.toFavoriteYouTubeCreator()?.let(onYouTubeMusicCreatorClick)
                                        }
                                        "youtubeMusic" -> {
                                            val resolvedBrowseId = favorite.browseId
                                                ?.takeIf { it.isNotBlank() }
                                                ?: favorite.playlistId
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let { "VL$it" }
                                            val resolvedPlaylistId = favorite.playlistId
                                                ?.takeIf { it.isNotBlank() }
                                                ?: resolvedBrowseId?.removePrefix("VL")
                                            if (
                                                !resolvedBrowseId.isNullOrBlank() &&
                                                !resolvedPlaylistId.isNullOrBlank()
                                            ) {
                                                onYouTubeMusicPlaylistClick(
                                                    YouTubeMusicPlaylist(
                                                        browseId = resolvedBrowseId,
                                                        playlistId = resolvedPlaylistId,
                                                        title = favorite.name,
                                                        subtitle = favorite.subtitle.orEmpty(),
                                                        coverUrl = favorite.coverUrl.orEmpty(),
                                                        trackCount = favorite.trackCount
                                                    )
                                                )
                                            }
                                        }
                                        "bili" -> {
                                            onBiliPlaylistClick(favorite.toBiliPlaylist())
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!sortMode) sortMode = true
                                    toggleSelection(itemKey)
                                }
                            )
                    ) {
                        ListItem(
                            headlineContent = { Text(favorite.name) },
                            supportingContent = {
                                Text(
                                    if (isArtistFavoriteSource(favorite.source)) {
                                        favorite.subtitle?.takeIf { it.isNotBlank() }
                                            ?: favoriteSourceLabel(favorite.source)
                                    } else stringResource(
                                        CoreCommonR.string.library_favorite_source_format,
                                        favorite.trackCount,
                                        favoriteSourceLabel(favorite.source)
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            leadingContent = {
                                if (!favorite.coverUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = offlineCachedImageRequest(
                                            context = context,
                                            data = favorite.coverUrl,
                                            sizePx = 192,
                                            allowHardware = false,
                                            offlineMode = offlineMode
                                        ),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(
                                                if (isArtistFavoriteSource(favorite.source)) CircleShape
                                                else RoundedCornerShape(8.dp)
                                            )
                                    )
                                } else {
                                    Icon(
                                        imageVector = when (favorite.source) {
                                            FAVORITE_SOURCE_NETEASE_ARTIST,
                                            FAVORITE_SOURCE_BILI_ARTIST,
                                            FAVORITE_SOURCE_YOUTUBE_ARTIST -> Icons.Filled.AccountCircle
                                            "neteaseAlbum" -> Icons.Filled.Album
                                            else -> Icons.AutoMirrored.Filled.QueueMusic
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(56.dp)
                                    )
                                }
                            },
                            trailingContent = {
                                if (sortMode) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { toggleSelection(itemKey) }
                                        )
                                        Box(
                                            modifier = Modifier
                                                .detectReorder(reorderState)
                                                .padding(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DragHandle,
                                                contentDescription = stringResource(CoreCommonR.string.common_drag_handle),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = { Text(stringResource(CoreCommonR.string.dialog_confirm_delete)) },
            text = {
                Text(
                    pluralStringResource(
                        CoreCommonR.plurals.library_delete_selected_confirm,
                        selectedKeys.size,
                        selectedKeys.size
                    )
                )
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        val targets = reorderableFavorites.filter { favoriteKey(it) in selectedKeys }
                        scope.launch {
                            targets.forEach { favoriteRepo.removeFavorite(it.id, it.source) }
                            exitEditMode()
                        }
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.action_delete))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showDeleteSelectedConfirm = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun favoriteSourceLabel(source: String): String {
    return when (source) {
        "youtubeMusic" -> "YouTube"
        "neteaseAlbum" -> "Netease Album"
        "netease" -> "Netease"
        "bili" -> "Bilibili"
        FAVORITE_SOURCE_NETEASE_ARTIST -> stringResource(CoreCommonR.string.library_favorite_source_artist)
        FAVORITE_SOURCE_BILI_ARTIST -> stringResource(CoreCommonR.string.library_artist_platform_bili)
        FAVORITE_SOURCE_YOUTUBE_ARTIST -> "YouTube"
        else -> source
    }
}

@Composable
internal fun QqMusicPlaylistList(
    listState: LazyListState
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + miniPlayerHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        // TODO: Implement QQ Music playlist list when type is available
        item {
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(cardShape)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(CoreCommonR.string.library_qqmusic_coming)) },
                    supportingContent = {
                        Text(stringResource(CoreCommonR.string.library_coming_soon), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                )
            }
        }
    }
}
