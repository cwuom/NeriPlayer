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
 * File: moe.ouom.neriplayer.ui.screen.tab/ExploreScreen
 * Created: 2025/8/8
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.SearchSource
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ExploreScreen(
    gridState: LazyGridState,
    onPlay: (NeteasePlaylist) -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val vm: ExploreViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ExploreViewModel(context.applicationContext as Application) }
        }
    )
    val ui by vm.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val allLocalPlaylists by repo.playlists.collectAsState(initial = emptyList())

    var showPartsSheet by remember { mutableStateOf(false) }
    var partsInfo by remember { mutableStateOf<BiliClient.VideoBasicInfo?>(null) }
    var clickedSongCoverUrl by remember { mutableStateOf("") }
    val partsSheetState = rememberModalBottomSheetState()

    var partsSelectionMode by remember { mutableStateOf(false) }
    var selectedParts by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var showExportSheet by remember { mutableStateOf(false) }
    val exportSheetState = rememberModalBottomSheetState()

    val pagerState = rememberPagerState(pageCount = { SearchSource.entries.size })
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    fun exitPartsSelection() {
        partsSelectionMode = false
        selectedParts = emptySet()
    }

    LaunchedEffect(Unit) {
        if (ui.playlists.isEmpty()) vm.loadHighQuality()
    }

    LaunchedEffect(pagerState.currentPage, ui.selectedSearchSource) {
        val currentSource = SearchSource.entries[pagerState.currentPage]
        if (ui.selectedSearchSource != currentSource) {
            vm.setSearchSource(currentSource)
            if (searchQuery.isNotEmpty()) vm.search(searchQuery)
        }
    }

    // Tag keys for API calls
    val tagKeys = listOf(
        "tag_all", "tag_pop", "tag_soundtrack", "tag_chinese", "tag_nostalgia", "tag_rock", "tag_acg", "tag_western", "tag_fresh", "tag_night", "tag_children", "tag_folk", "tag_japanese", "tag_romantic",
        "tag_study", "tag_korean", "tag_work", "tag_electronic", "tag_cantonese", "tag_dance", "tag_sad", "tag_game", "tag_afternoon_tea", "tag_healing", "tag_rap", "tag_light_music"
    )

    // Translated tag labels for display
    val tagLabels = listOf(
        stringResource(R.string.tag_all), stringResource(R.string.tag_pop), stringResource(R.string.tag_soundtrack), stringResource(R.string.tag_chinese), stringResource(R.string.tag_nostalgia), stringResource(R.string.tag_rock), stringResource(R.string.tag_acg), stringResource(R.string.tag_western), stringResource(R.string.tag_fresh), stringResource(R.string.tag_night), stringResource(R.string.tag_children), stringResource(R.string.tag_folk), stringResource(R.string.tag_japanese), stringResource(R.string.tag_romantic),
        stringResource(R.string.tag_study), stringResource(R.string.tag_korean), stringResource(R.string.tag_work), stringResource(R.string.tag_electronic), stringResource(R.string.tag_cantonese), stringResource(R.string.tag_dance), stringResource(R.string.tag_sad), stringResource(R.string.tag_game), stringResource(R.string.tag_afternoon_tea), stringResource(R.string.tag_healing), stringResource(R.string.tag_rap), stringResource(R.string.tag_light_music)
    )

    // Initialize with default tag
    LaunchedEffect(Unit) {
        if (ui.selectedTag == "tag_all" && ui.playlists.isEmpty()) {
            vm.loadHighQuality("tag_all")
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_explore)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        vm.search(searchQuery)
                    },
                    label = { Text(stringResource(R.string.search_keyword)) },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            HapticIconButton(onClick = {
                                searchQuery = ""
                                vm.search("")
                            }) { Icon(Icons.Default.Clear, "Clear") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                    }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    SearchSource.entries.forEachIndexed { index, source ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(source.displayName) }
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val currentSource = SearchSource.entries[page]
                if (searchQuery.isNotEmpty()) {
                    when {
                        ui.searching -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = miniPlayerHeight),
                                Alignment.Center
                            ) { CircularProgressIndicator() }
                        }
                        ui.searchError != null -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = miniPlayerHeight),
                                Alignment.Center
                            ) {
                                Text(ui.searchError!!, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        ui.searchResults.isEmpty() -> {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = miniPlayerHeight),
                                Alignment.Center
                            ) { Text(stringResource(R.string.search_no_result)) }
                        }
                        else -> {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    top = 8.dp,
                                    bottom = 16.dp + miniPlayerHeight
                                )
                            ) {
                                itemsIndexed(ui.searchResults) { index, song ->
                                    SongRow(index + 1, song) {
                                        if (song.album == PlayerManager.BILI_SOURCE_TAG) {
                                            scope.launch {
                                                try {
                                                    val info = vm.getVideoInfoByAvid(song.id)
                                                    if (info.pages.size <= 1) {
                                                        onSongClick(ui.searchResults, index)
                                                    } else {
                                                        partsInfo = info
                                                        clickedSongCoverUrl = song.coverUrl ?: ""
                                                        showPartsSheet = true
                                                    }
                                                } catch (e: Exception) {
                                                    NPLogger.e("ExploreScreen", context.getString(R.string.search_error), e)
                                                }
                                            }
                                        } else {
                                            onSongClick(ui.searchResults, index)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    when (currentSource) {
                        SearchSource.NETEASE -> {
                            NeteaseDefaultContent(gridState, ui, tagKeys, tagLabels, vm, onPlay)
                        }
                        SearchSource.BILIBILI -> {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                Text(stringResource(R.string.explore_bili_desc), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPartsSheet && partsInfo != null) {
        val currentPartsInfo = partsInfo!!
        BackHandler(enabled = partsSelectionMode) { exitPartsSelection() }
        ModalBottomSheet(
            onDismissRequest = {
                showPartsSheet = false
                exitPartsSelection()
            },
            sheetState = partsSheetState
        ) {
            Column(Modifier.padding(bottom = 12.dp)) {
                AnimatedVisibility(visible = partsSelectionMode) {
                    val allSelected = selectedParts.size == currentPartsInfo.pages.size
                    TopAppBar(
                        title = { Text(stringResource(R.string.common_selected_count, selectedParts.size)) },
                        navigationIcon = {
                            HapticIconButton(onClick = { exitPartsSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_exit_selection))
                            }
                        },
                        actions = {
                            HapticIconButton(onClick = {
                                selectedParts = if (allSelected) {
                                    emptySet()
                                } else {
                                    currentPartsInfo.pages.map { it.page }.toSet()
                                }
                            }) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = if (allSelected) stringResource(R.string.explore_deselect_all) else stringResource(R.string.explore_select_all)
                                )
                            }
                            HapticIconButton(
                                onClick = {
                                    if (selectedParts.isNotEmpty()) {
                                        scope.launch { partsSheetState.hide() }.invokeOnCompletion {
                                            if (!partsSheetState.isVisible) {
                                                showPartsSheet = false
                                                showExportSheet = true
                                            }
                                        }
                                    }
                                },
                                enabled = selectedParts.isNotEmpty()
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.explore_export_to_playlist))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }

                AnimatedVisibility(visible = !partsSelectionMode) {
                    Text(
                        text = currentPartsInfo.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider()

                LazyColumn {
                    itemsIndexed(currentPartsInfo.pages, key = { _, page -> page.page }) { index, page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (partsSelectionMode) {
                                            selectedParts = if (selectedParts.contains(page.page)) {
                                                selectedParts - page.page
                                            } else {
                                                selectedParts + page.page
                                            }
                                        } else {
                                            onPlayParts(currentPartsInfo, index, clickedSongCoverUrl)
                                            scope.launch { partsSheetState.hide() }.invokeOnCompletion {
                                                if (!partsSheetState.isVisible) showPartsSheet = false
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!partsSelectionMode) {
                                            partsSelectionMode = true
                                            selectedParts = setOf(page.page)
                                        }
                                    }
                                )
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (partsSelectionMode) {
                                Checkbox(
                                    checked = selectedParts.contains(page.page),
                                    onCheckedChange = {
                                        selectedParts = if (selectedParts.contains(page.page)) {
                                            selectedParts - page.page
                                        } else {
                                            selectedParts + page.page
                                        }
                                    }
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                            Text(
                                text = "P${page.page}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(48.dp)
                            )
                            Text(
                                text = page.part,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExportSheet = false },
            sheetState = exportSheetState
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.playlist_export_to_local), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                LazyColumn {
                    itemsIndexed(allLocalPlaylists) { _, pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clickable {
                                    val songs = partsInfo!!.pages
                                        .filter { selectedParts.contains(it.page) }
                                        .map { page -> vm.toSongItem(page, partsInfo!!, clickedSongCoverUrl) }

                                    scope.launch {
                                        repo.addSongsToPlaylist(pl.id, songs)
                                        showExportSheet = false
                                        exitPartsSelection()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pl.name, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            Text(stringResource(R.string.explore_song_count, pl.songs.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(thickness = DividerDefaults.Thickness, color = DividerDefaults.color)
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
                        enabled = newName.isNotBlank() && selectedParts.isNotEmpty(),
                        onClick = {
                            val name = newName.trim()
                            if (name.isBlank()) return@HapticTextButton

                            val songs = partsInfo!!.pages
                                .filter { selectedParts.contains(it.page) }
                                .map { page -> vm.toSongItem(page, partsInfo!!, clickedSongCoverUrl) }

                            scope.launch {
                                repo.createPlaylist(name)
                                val target = repo.playlists.value.lastOrNull { it.name == name }
                                if (target != null) {
                                    repo.addSongsToPlaylist(target.id, songs)
                                }
                                showExportSheet = false
                                exitPartsSelection()
                            }
                        }
                    ) { Text(stringResource(R.string.playlist_create_and_export)) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NeteaseDefaultContent(
    gridState: LazyGridState,
    ui: ExploreUiState,
    tagKeys: List<String>,
    tagLabels: List<String>,
    vm: ExploreViewModel,
    onPlay: (NeteasePlaylist) -> Unit
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 16.dp + miniPlayerHeight
        ),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                val displayCount = if (ui.expanded) tagKeys.size else 12
                val displayKeys = tagKeys.take(displayCount)
                val displayLabels = tagLabels.take(displayCount)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    displayKeys.forEachIndexed { index, tagKey ->
                        val selected = (ui.selectedTag == tagKey)
                        FilterChip(
                            selected = selected,
                            onClick = { if (!selected) vm.loadHighQuality(tagKey) },
                            label = { Text(displayLabels[index]) },
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = MaterialTheme.colorScheme.outline,
                                selected = selected,
                                enabled = true
                            )
                        )
                    }
                }
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    HapticTextButton(onClick = { vm.toggleExpanded() }) {
                        Text(if (ui.expanded) stringResource(R.string.explore_collapse) else stringResource(R.string.explore_expand))
                    }
                }
            }
        }
        if (ui.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (ui.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(ui.error, color = MaterialTheme.colorScheme.error)
            }
        } else {
            items(items = ui.playlists, key = { it.id }) { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    onClick = { onPlay(playlist) }
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: SongItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.performHapticFeedback()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        }

        if (!song.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(song.coverUrl).build(),
                contentDescription = song.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
