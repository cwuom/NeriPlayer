package moe.ouom.neriplayer.ui.screen.server.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistSelectionActions
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.nowplaying.hasCachedLocalDownload
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernPlaybackActions
import moe.ouom.neriplayer.ui.screen.playlist.SongIdentityLookup
import moe.ouom.neriplayer.ui.screen.playlist.rememberPlaylistSearchResults
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistPlaybackStartIndex
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailFrame
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailHeader
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernDetailSearch
import moe.ouom.neriplayer.ui.screen.playlist.detail.PlaylistModernSongRow
import moe.ouom.neriplayer.ui.screen.server.ServerBrowseMessage
import moe.ouom.neriplayer.ui.screen.server.resolveServerDisplaySongs
import moe.ouom.neriplayer.ui.viewmodel.server.ServerAlbumDetailViewModel
import moe.ouom.neriplayer.util.format.formatTotalDuration
import moe.ouom.neriplayer.util.search.playlistSearchValues
import kotlin.random.Random
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
fun ServerAlbumDetailScreen(
    album: ServerAlbum,
    onBack: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    offlineMode: Boolean = false,
    vm: ServerAlbumDetailViewModel = viewModel()
) {
    LaunchedEffect(album) { vm.start(album) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val playlists by repository.playlists.collectAsStateWithLifecycle()
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val queue by PlayerManager.currentQueueFlow.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val shuffle by PlayerManager.shuffleModeFlow.collectAsStateWithLifecycle()
    val repeat by PlayerManager.repeatModeFlow.collectAsStateWithLifecycle()
    val autoKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsStateWithLifecycle(false)
    val background by AppContainer.settingsRepo.backgroundImageUriFlow.collectAsStateWithLifecycle(null)
    val presenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val sourceSongs = state.takeIf { it.album?.profileId == album.profileId && it.album.id == album.id }
        ?.songs.orEmpty()
    val displaySnapshot by produceState<Pair<List<SongItem>, List<SongItem>>?>(
        null, sourceSongs, playlists, queue, currentSong
    ) {
        val source = sourceSongs
        value = source to withContext(Dispatchers.Default) {
            resolveServerDisplaySongs(source, playlists.flatMap { it.songs }, queue, currentSong)
        }
    }
    val allSongs = displaySnapshot?.takeIf { it.first === sourceSongs }?.second ?: sourceSongs
    val location = serverAlbumDetailKey(album)
    var showSearch by rememberSaveable(location) { mutableStateOf(false) }
    val inputState = rememberSaveable(location, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val query = inputState.value.text
    val focusRequester = remember(location) { FocusRequester() }
    var headerFocused by remember(location) { mutableStateOf(false) }
    var dockedFocused by remember(location) { mutableStateOf(false) }
    val songs = rememberPlaylistSearchResults(query, allSongs, { it.playlistSearchValues(context) })
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var selectionMode by remember(location) { mutableStateOf(false) }
    var selectedKeys by remember(location) { mutableStateOf(emptySet<String>()) }
    val selectedSongs = allSongs.filter { it.stableKey() in selectedKeys }
    val visibleKeys = songs.map { it.stableKey() }.toSet()
    var exportSongs by remember(location) { mutableStateOf<List<SongItem>?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    var busyFavorites by remember { mutableStateOf(emptySet<String>()) }
    val downloadedKeys = remember(allSongs, presenceVersion) {
        allSongs.filter { it.isLocalSong() || hasCachedLocalDownload(it) }.map { it.stableKey() }.toSet()
    }
    val favorites = remember(playlists, context) {
        FavoriteSongLookup(FavoritesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty())
    }
    val current = remember(currentSong) { SongIdentityLookup(listOfNotNull(currentSong)) }
    val queueIndices = remember(allSongs) { allSongs.mapIndexed { index, song -> song.stableKey() to index }.toMap() }
    val playingIndex = songs.indexOfFirst { current.contains(it) }
    fun locatePlaying() {
        scope.launch { listState.animateScrollToItem(playingIndex + 2) }
    }
    LaunchedEffect(allSongs) { selectedKeys = selectedKeys.intersect(allSongs.map { it.stableKey() }.toSet()) }
    fun exitSelection() { selectionMode = false; selectedKeys = emptySet() }
    fun toggleSelection(song: SongItem) {
        val key = song.stableKey()
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }
    fun download(targets: List<SongItem>) {
        val pending = targets.filterNot { it.stableKey() in downloadedKeys }
        if (pending.isNotEmpty()) GlobalDownloadManager.startBatchDownload(context, pending)
        showDownloads = true
    }
    fun play(shuffled: Boolean) {
        val index = resolvePlaylistPlaybackStartIndex(
            allSongs.size, shuffled, if (allSongs.isEmpty()) 0 else Random.nextInt(allSongs.size)
        )
        if (index >= 0) { PlayerManager.setShuffle(shuffled); onSongClick(allSongs, index) }
    }
    fun favorite(song: SongItem) {
        val key = song.stableKey()
        if (key in busyFavorites) return
        busyFavorites = busyFavorites + key
        scope.launchLocalPlaylistMutation(
            operation = "toggleServerAlbumFavorite",
            onResult = { result ->
                busyFavorites = busyFavorites - key
                scope.launch {
                    snackbar.showNeriSnackbar(resources.getString(result.fold(
                        onSuccess = { if (it) CoreCommonR.string.favorite_added else CoreCommonR.string.favorite_removed },
                        onFailure = { CoreCommonR.string.server_action_failed }
                    )))
                }
            }
        ) {
            val saved = FavoritesPlaylist.firstOrNull(repository.playlists.value, context)
                ?.songs?.any { it.sameIdentityAs(song) } == true
            if (saved) repository.removeFromFavorites(song) else repository.addToFavorites(song)
            !saved
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun back() {
        if (selectionMode) exitSelection()
        else if (showSearch) {
            showSearch = false; inputState.value = TextFieldValue(); focusManager.clearFocus(); keyboard?.hide()
        } else onBack()
    }
    BackHandler(selectionMode || showSearch, onBack = ::back)
    val duration = if (allSongs.any { it.durationMs <= 0 }) stringResource(CoreCommonR.string.local_playlist_duration_loading)
        else formatTotalDuration(context, allSongs.sumOf { it.durationMs })
    val albumSummary = stringResource(CoreCommonR.string.local_artist_total_duration, duration, allSongs.size)
    PlaylistModernDetailFrame(
        header = PlaylistModernDetailHeader(
            key = location, headerKey = "server-album-header:$location", title = album.name,
            coverUrl = album.coverUrl,
            subtitle = listOfNotNull(album.artist.takeIf { it.isNotBlank() }, albumSummary).joinToString(" · "),
            offlineMode = offlineMode, hasCustomBackground = background != null
        ),
        search = PlaylistModernDetailSearch(
            showSearch, { showSearch = it }, query,
            { inputState.value = TextFieldValue(it, TextRange(it.length)) }, inputState, focusRequester,
            autoKeyboard, headerFocused, { headerFocused = it }, dockedFocused, { dockedFocused = it }
        ),
        listState = listState, snackbarHostState = snackbar, onBack = ::back,
        selectionCount = if (selectionMode) selectedKeys.size else null,
        onExitSelection = ::exitSelection,
        onLocatePlaying = if (playingIndex >= 0) ::locatePlaying else null,
        normalActions = {
            ServerAlbumCollectionMenu(
                canUseSongs = allSongs.isNotEmpty(), refreshing = state.loading,
                onDownloadAll = { download(allSongs) }, onAddToPlaylist = { exportSongs = allSongs },
                onRefresh = vm::retry, onDownloads = { showDownloads = true }
            )
        },
        selectionActions = {
            HapticIconButton(onClick = { download(selectedSongs); exitSelection() }, enabled = selectedSongs.isNotEmpty()) {
                Icon(Icons.Outlined.Download, stringResource(CoreCommonR.string.cd_download_selected))
            }
            PlaylistSelectionActions(
                allSelected = visibleKeys.isNotEmpty() && selectedKeys.containsAll(visibleKeys),
                onToggleSelectAll = {
                    selectedKeys = if (selectedKeys.containsAll(visibleKeys)) selectedKeys - visibleKeys else selectedKeys + visibleKeys
                },
                canExport = selectedSongs.isNotEmpty(), onExport = { exportSongs = selectedSongs }
            )
        },
        beforeList = {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        },
        playbackActions = {
            PlaylistModernPlaybackActions(
                songCount = allSongs.size, shuffleEnabled = shuffle, repeatMode = repeat,
                onPlayInOrder = { play(false) }, onShufflePlay = { play(true) },
                onToggleShuffle = { PlayerManager.setShuffle(!shuffle) },
                onCycleRepeatMode = { PlayerManager.cycleRepeatMode() },
                onExportToLocalPlaylist = { exportSongs = allSongs }
            )
        },
        content = {
            itemsIndexed(songs, key = { _, song -> song.stableKey() }, contentType = { _, _ -> "local_playlist_song" }) { index, song ->
                val key = song.stableKey()
                PlaylistModernSongRow(
                    song, index, album.coverUrl, offlineMode, selectionMode, key in selectedKeys,
                    current.contains(song), playing, key in downloadedKeys, presenceVersion,
                    onClick = {
                        if (selectionMode) toggleSelection(song)
                        else queueIndices[key]?.let { onSongClick(allSongs, it) }
                    },
                    onLongClick = { selectionMode = true; toggleSelection(song) },
                    onToggleSelection = { toggleSelection(song) }
                ) {
                    if (selectionMode) Spacer(Modifier.size(40.dp))
                    else ServerAlbumSongMenu(
                        song, favorites.contains(song), key in busyFavorites, key in downloadedKeys,
                        onFavorite = { favorite(song) }, onDownload = { download(listOf(song)) },
                        onAddToPlaylist = { exportSongs = listOf(song) }
                    )
                }
            }
            if (state.error != null) item(key = "album-error") {
                ServerBrowseMessage(stringResource(requireNotNull(state.error)), error = true, onRetry = vm::retry)
            }
            else if (!state.loading && songs.isEmpty()) item(key = "album-empty") {
                ServerBrowseMessage(stringResource(CoreCommonR.string.server_no_results))
            }
        },
        overlays = {
            if (showDownloads) {
                val tasks by GlobalDownloadManager.downloadTasks.collectAsStateWithLifecycle()
                BatchDownloadManagerSheet(tasks, onDismiss = { showDownloads = false })
            }
            exportSongs?.takeIf { it.isNotEmpty() }?.let { snapshot ->
                ServerAlbumExportSheet(snapshot, playlists, repository, snackbar, onDismiss = { exportSongs = null })
            }
        }
    )
}

internal fun serverAlbumDetailKey(album: ServerAlbum): String =
    "server-album:${album.profileId.length}:${album.profileId}${album.id}"
