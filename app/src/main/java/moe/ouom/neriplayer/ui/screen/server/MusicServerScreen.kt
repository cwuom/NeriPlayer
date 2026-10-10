package moe.ouom.neriplayer.ui.screen.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel
import moe.ouom.neriplayer.ui.screen.playlist.rememberPlaylistSearchResults
import moe.ouom.neriplayer.util.search.playlistSearchValues

@Composable
fun MusicServerScreen(vm: MusicServerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val queue by PlayerManager.currentQueueFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val repository = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val playlists by repository.playlists.collectAsStateWithLifecycle()
    val displaySnapshot by produceState<Pair<List<SongItem>, List<SongItem>>?>(
        null, state.songs, playlists, queue, currentSong
    ) {
        val source = state.songs
        value = source to withContext(Dispatchers.Default) {
            resolveServerDisplaySongs(source, playlists.flatMap { it.songs }, queue, currentSong)
        }
    }
    // Never briefly project the previous album's songs into a newly opened album.
    val displaySongs = displaySnapshot?.takeIf { it.first === state.songs }?.second ?: state.songs
    val songs = rememberPlaylistSearchResults(
        query = if (state.album != null) state.query else "",
        items = displaySongs, tokens = { it.playlistSearchValues(context) }, buildIndex = state.album != null
    )
    val showingSongs = state.album != null || state.category == ServerLibraryCategory.SONGS
    val favoriteKeys = remember(playlists, context) {
        FavoritesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty().map { it.stableKey() }.toSet()
    }
    val location = vm.locationKey
    val listState = remember(location) {
        val (index, offset) = vm.listPosition(location)
        LazyListState(index, offset)
    }
    var selectionMode by remember(location) { mutableStateOf(false) }
    var selectedKeys by remember(location) { mutableStateOf(emptySet<String>()) }
    var busyFavorites by remember { mutableStateOf(emptySet<String>()) }
    var exportSongs by remember(location) { mutableStateOf<List<SongItem>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val selectedSongs = songs.filter { it.stableKey() in selectedKeys }
    LaunchedEffect(songs) { selectedKeys = selectedKeys.intersect(songs.map { it.stableKey() }.toSet()) }
    DisposableEffect(location, listState) {
        onDispose { vm.saveListPosition(location, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    fun exitSelection() { selectionMode = false; selectedKeys = emptySet() }
    fun select(song: SongItem) {
        val key = song.stableKey()
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        selectionMode = true
    }
    fun toggleFavorite(song: SongItem) {
        val key = song.stableKey()
        if (key in busyFavorites) return
        busyFavorites = busyFavorites + key
        scope.launchLocalPlaylistMutation(
            operation = "toggleServerSongFavorite",
            onResult = { result ->
                busyFavorites = busyFavorites - key
                scope.launch {
                    snackbar.showNeriSnackbar(resources.getString(result.fold(
                        onSuccess = { added -> if (added) CoreCommonR.string.favorite_added else CoreCommonR.string.favorite_removed },
                        onFailure = { CoreCommonR.string.server_action_failed }
                    )))
                }
            }
        ) {
            val wasFavorite = FavoritesPlaylist.firstOrNull(repository.playlists.value, context)
                ?.songs?.any { it.sameIdentityAs(song) } == true
            if (wasFavorite) repository.removeFromFavorites(song) else repository.addToFavorites(song)
            !wasFavorite
        }
    }
    BackHandler(selectionMode || state.album != null || state.query.isNotBlank()) {
        if (selectionMode) exitSelection() else vm.back()
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(CoreCommonR.string.server_my_music), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                TextButton(onClick = vm::refresh, enabled = !state.loading && !state.accountsLoading && state.profileId != null) {
                    Text(stringResource(CoreCommonR.string.server_refresh))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles.filter { it.enabled }, key = { it.id }) { profile ->
                    FilterChip(selected = state.profileId == profile.id, onClick = { vm.select(profile.id) },
                        label = { Text(profile.label) })
                }
            }
            if (state.accountsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (state.accountsError != null) {
                Text(stringResource(state.accountsError!!), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::reloadAccounts) { Text(stringResource(CoreCommonR.string.server_retry)) }
            } else if (profiles.none { it.enabled }) {
                Text(stringResource(CoreCommonR.string.server_empty), Modifier.padding(vertical = 24.dp))
            } else {
                if (state.album == null) ServerLibraryTabs(state.category) { category ->
                    exitSelection()
                    vm.setCategory(category)
                    keyboard?.hide()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.inputQuery, onValueChange = vm::editQuery, modifier = Modifier.weight(1f),
                        singleLine = true, shape = RoundedCornerShape(16.dp),
                        label = { Text(stringResource(when {
                            state.album != null -> CoreCommonR.string.server_search_album_tracks_hint
                            state.category == ServerLibraryCategory.ALBUMS -> CoreCommonR.string.server_search_albums_hint
                            else -> CoreCommonR.string.server_search_songs_hint
                        }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { if (state.inputQuery.isNotEmpty()) IconButton(onClick = { vm.search(""); keyboard?.hide() }) {
                            Icon(Icons.Filled.Clear, stringResource(CoreCommonR.string.settings_search_clear_query))
                        } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(state.inputQuery); keyboard?.hide() })
                    )
                    if (state.album == null) TextButton(onClick = { vm.search(state.inputQuery); keyboard?.hide() }) {
                        Text(stringResource(CoreCommonR.string.server_search))
                    }
                }
                if (state.album != null) TextButton(onClick = {
                    if (selectionMode) exitSelection() else vm.back()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(CoreCommonR.string.action_back))
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                state.error?.let { message ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(message), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::retry, enabled = !state.loading) { Text(stringResource(CoreCommonR.string.server_retry)) }
                    }
                }
                if (selectionMode) ServerSongActions(
                    songs = songs, selectedCount = selectedSongs.size, selectionMode = true,
                    allSelected = songs.isNotEmpty() && selectedSongs.size == songs.size,
                    partial = state.album == null && state.hasMore,
                    onPlay = { PlayerManager.playPlaylist(selectedSongs, 0) },
                    onAddToPlaylist = { exportSongs = selectedSongs.toList() },
                    onSelection = ::exitSelection,
                    onSelectAll = { selectedKeys = if (selectedSongs.size == songs.size) emptySet() else songs.map { it.stableKey() }.toSet() }
                )
                LazyColumn(Modifier.weight(1f), state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = LocalMiniPlayerHeight.current + 24.dp)) {
                    state.album?.let { album -> item(key = "album-header") { ServerAlbumHeader(album) } }
                    if (state.album == null && state.query.isNotBlank()) item(key = "search-title") {
                            Text(stringResource(CoreCommonR.string.server_search_results, state.query),
                                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    if (showingSongs) {
                        if (!selectionMode) item(key = "song-actions") {
                            ServerSongActions(
                                songs = songs, selectedCount = selectedSongs.size, selectionMode = selectionMode,
                                allSelected = songs.isNotEmpty() && selectedSongs.size == songs.size,
                                partial = state.album == null && state.hasMore,
                                onPlay = { PlayerManager.playPlaylist(if (selectionMode) selectedSongs else songs, 0) },
                                onAddToPlaylist = { exportSongs = (if (selectionMode) selectedSongs else songs).toList() },
                                onSelection = { if (selectionMode) exitSelection() else selectionMode = true },
                                onSelectAll = { selectedKeys = if (selectedSongs.size == songs.size) emptySet() else songs.map { it.stableKey() }.toSet() }
                            )
                        }
                        itemsIndexed(songs, key = { _, song -> song.stableKey() }) { index, song ->
                            val key = song.stableKey()
                            MusicServerSongRow(
                                song, index + 1, currentSong?.sameIdentityAs(song) == true, playing,
                                key in favoriteKeys, key in busyFavorites, selectionMode, key in selectedKeys,
                                onClick = { PlayerManager.playPlaylist(songs, index) }, onSelect = { select(song) },
                                onFavorite = { toggleFavorite(song) }, onAddToPlaylist = { exportSongs = listOf(song) },
                                onPlayNext = { PlayerManager.addToQueueNext(song) }, onAddToQueue = { PlayerManager.addToQueueEnd(song) }
                            )
                        }
                    } else items(state.albums, key = { it.id }) { album ->
                        ServerAlbumRow(album) { vm.open(album) }
                    }
                    if (!state.loading && state.error == null && state.albums.isEmpty() && songs.isEmpty()) item(key = "empty") {
                        Text(stringResource(CoreCommonR.string.server_no_results), Modifier.padding(vertical = 24.dp))
                    }
                    if (state.hasMore) item(key = "more") {
                        TextButton(onClick = { vm.load(more = true) }, enabled = !state.loading) {
                            Text(stringResource(CoreCommonR.string.server_more))
                        }
                    }
                }
            }
        }
        NeriOverlaySnackbarHost(hostState = snackbar, bottomPadding = LocalMiniPlayerHeight.current)
    }
    exportSongs?.takeIf { it.isNotEmpty() }?.let { snapshot ->
        PlaylistExportSheet(
            title = stringResource(CoreCommonR.string.playlist_add_to),
            playlists = playlists.filterNot { LocalFilesPlaylist.isSystemPlaylist(it, context) }, selectedCount = snapshot.size,
            onDismissRequest = { exportSongs = null }, createActionLabel = stringResource(CoreCommonR.string.playlist_create_and_add),
            confirmationTitle = stringResource(CoreCommonR.string.server_add_playlist_confirm_title),
            confirmationMessage = { name -> resources.getQuantityString(
                CoreCommonR.plurals.server_add_playlist_confirm_message, snapshot.size, snapshot.size, name
            ) },
            confirmActionLabel = stringResource(CoreCommonR.string.server_add_playlist_confirm_action),
            onCreateAndExport = { name -> scope.launchLocalPlaylistMutation(
                operation = "createPlaylistFromServer",
                onResult = { scope.showPlaylistBatchExportCreatedResult(context, snackbar, repository, it) }
            ) { repository.createPlaylistWithSongs(name, snapshot) } },
            onExportToPlaylist = { playlist -> scope.launchLocalPlaylistMutation(
                operation = "addServerSongsToPlaylist",
                onResult = { scope.showPlaylistBatchExportAddedResult(context, snackbar, repository, playlist.id, playlist.name, it) }
            ) { repository.addSongsToPlaylistWithResult(playlist.id, snapshot) } }
        )
    }
}

@Composable
private fun ServerLibraryTabs(category: ServerLibraryCategory, onSelect: (ServerLibraryCategory) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        PrimaryTabRow(selectedTabIndex = category.ordinal, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            ServerLibraryCategory.entries.forEach { item ->
                Tab(selected = category == item, onClick = { onSelect(item) },
                    text = { Text(stringResource(if (item == ServerLibraryCategory.ALBUMS)
                        CoreCommonR.string.server_tab_albums else CoreCommonR.string.server_tab_songs)) },
                    icon = { Icon(if (item == ServerLibraryCategory.ALBUMS) Icons.Outlined.Album else Icons.Outlined.MusicNote, null) })
            }
        }
    }
}

@Composable
private fun ServerAlbumHeader(album: ServerAlbum) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ServerAlbumCover(album, Modifier.size(88.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(CoreCommonR.plurals.explore_song_count, album.songCount, album.songCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerAlbumRow(album: ServerAlbum, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ServerAlbumCover(album, Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(CoreCommonR.plurals.explore_song_count, album.songCount, album.songCount),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerAlbumCover(album: ServerAlbum, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (album.coverUrl.isNullOrBlank()) Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Album, null) }
        else AsyncImage(model = album.coverUrl, contentDescription = null, contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerSongActions(
    songs: List<SongItem>, selectedCount: Int, selectionMode: Boolean, allSelected: Boolean, partial: Boolean,
    onPlay: () -> Unit, onAddToPlaylist: () -> Unit, onSelection: () -> Unit, onSelectAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectionMode) pluralStringResource(CoreCommonR.plurals.common_selected_count, selectedCount, selectedCount)
                else pluralStringResource(CoreCommonR.plurals.explore_song_count, songs.size, songs.size),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (selectionMode) TextButton(onClick = onSelectAll) {
                Text(stringResource(if (allSelected) CoreCommonR.string.action_deselect_all else CoreCommonR.string.action_select_all))
            }
            TextButton(onClick = onSelection, enabled = selectionMode || songs.isNotEmpty()) {
                Text(stringResource(if (selectionMode) CoreCommonR.string.action_done else CoreCommonR.string.action_multi_select))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, enabled = if (selectionMode) selectedCount > 0 else songs.isNotEmpty()) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(when {
                    selectionMode -> CoreCommonR.string.server_play_selected
                    partial -> CoreCommonR.string.server_play_loaded
                    else -> CoreCommonR.string.player_play_all
                }))
            }
            OutlinedButton(onClick = onAddToPlaylist, enabled = if (selectionMode) selectedCount > 0 else songs.isNotEmpty()) {
                Text(stringResource(CoreCommonR.string.playlist_add_to))
            }
        }
        if (partial) Text(stringResource(CoreCommonR.string.server_loaded_results_note), Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MusicServerEntry() {
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text(stringResource(CoreCommonR.string.server_my_music)) }
    if (opened) Dialog(onDismissRequest = { opened = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column {
                TextButton(onClick = { opened = false }) { Text(stringResource(CoreCommonR.string.server_close)) }
                MusicServerScreen()
            }
        }
    }
}
