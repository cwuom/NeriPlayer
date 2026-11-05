package moe.ouom.neriplayer.ui.screen.server

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.overlay.SheetNavigationEventBridge
import moe.ouom.neriplayer.ui.component.overlay.SheetNavigationEventForwarding
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.nowplaying.hasCachedLocalDownload
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistPlaybackStartIndex
import moe.ouom.neriplayer.ui.screen.server.album.ServerAlbumDetailScreen
import moe.ouom.neriplayer.ui.screen.server.artist.ServerArtistDetailScreen
import moe.ouom.neriplayer.ui.screen.server.artist.serverArtistDetailKey
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.viewmodel.server.MusicServerViewModel
import moe.ouom.neriplayer.ui.viewmodel.server.musicServerListItemKeys
import moe.ouom.neriplayer.ui.viewmodel.server.musicServerListPositionKey

@Composable
fun MusicServerScreen(
    onAlbumClick: (ServerAlbum) -> Unit,
    modifier: Modifier = Modifier,
    onArtistClick: (ServerArtist) -> Unit = {},
    vm: MusicServerViewModel = viewModel(),
    bottomContentPadding: Dp? = null,
    showTitle: Boolean = true,
    onListStateAvailable: (LazyListState) -> Unit = {},
    offlineMode: Boolean = false
) {
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
    // 新专辑加载期间不显示上一张专辑的歌曲
    val displaySongs = displaySnapshot?.takeIf { it.first === state.songs }?.second ?: state.songs
    val songs = displaySongs
    val favoriteKeys = remember(playlists, context) {
        FavoritesPlaylist.firstOrNull(playlists, context)?.songs.orEmpty().map { it.stableKey() }.toSet()
    }
    val location = vm.locationKey
    val positionKey = musicServerListPositionKey(location, showTitle)
    var selectionMode by remember(location) { mutableStateOf(false) }
    val enabledProfiles = profiles.filter { it.enabled }
    val itemKeys = remember(state, showTitle, enabledProfiles.isNotEmpty(), songs) {
        musicServerListItemKeys(state, showTitle, enabledProfiles.isNotEmpty(), false, songs.map { it.stableKey() })
    }
    val browserListState = remember(positionKey) {
        val (index, offset) = vm.listPosition(positionKey, itemKeys)
        LazyListState(index, offset)
    }
    val selectionListState = remember(positionKey) { LazyListState() }
    val listState = if (selectionMode) selectionListState else browserListState
    SideEffect { onListStateAvailable(listState) }
    var selectedKeys by remember(location) { mutableStateOf(emptySet<String>()) }
    var busyFavorites by remember { mutableStateOf(emptySet<String>()) }
    var exportSongs by remember(location) { mutableStateOf<List<SongItem>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val selectedSongs = songs.filter { it.stableKey() in selectedKeys }
    var showDownloadManager by remember { mutableStateOf(false) }
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsStateWithLifecycle()
    val downloadedKeys = remember(songs, downloadPresenceVersion) {
        songs.filter { it.isLocalSong() || hasCachedLocalDownload(it) }.map { it.stableKey() }.toSet()
    }
    fun downloadSongs(targets: List<SongItem>) {
        val pending = targets.filterNot { it.isLocalSong() || hasCachedLocalDownload(it) }
        if (pending.size == 1) GlobalDownloadManager.startDownload(context, pending.single())
        else if (pending.isNotEmpty()) GlobalDownloadManager.startBatchDownload(context, pending)
        showDownloadManager = true
    }
    LaunchedEffect(songs) { selectedKeys = selectedKeys.intersect(songs.map { it.stableKey() }.toSet()) }
    DisposableEffect(positionKey, browserListState) {
        onDispose {
            val index = browserListState.firstVisibleItemIndex
            val itemKey = browserListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
            vm.saveListPosition(positionKey, index, browserListState.firstVisibleItemScrollOffset, itemKey)
        }
    }
    fun exitSelection() { selectionMode = false; selectedKeys = emptySet() }
    fun enterSelection(song: SongItem? = null) {
        if (selectionMode) return
        val visibleSong = browserListState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            (item.key as? String)?.startsWith("song:") == true
        }
        val anchorKey = visibleSong?.key as? String ?: song?.let { "song:${it.stableKey()}" }
        val selectionItemKeys = musicServerListItemKeys(state, showTitle, enabledProfiles.isNotEmpty(), true,
            songs.map { it.stableKey() })
        val index = selectionItemKeys.indexOf(anchorKey).coerceAtLeast(0)
        // 可见项包含顶部留白中的上一行，保留它的实际偏移以免整行重新出现
        val offset = visibleSong?.offset?.let { (-it).coerceAtLeast(0) } ?: 0
        selectionListState.requestScrollToItem(index, offset)
        keyboard?.hide()
        selectionMode = true
    }
    fun select(song: SongItem) {
        val key = song.stableKey()
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        enterSelection(song)
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
    BackHandler(selectionMode || state.query.isNotBlank()) {
        if (selectionMode) exitSelection() else vm.back()
    }
    val bottomPadding = bottomContentPadding ?: LocalMiniPlayerHeight.current
    val horizontalPadding = NeriDesign.tokens.pageHorizontalPadding
    fun selectAll() {
        selectedKeys = if (selectedSongs.size == songs.size) emptySet() else songs.map { it.stableKey() }.toSet()
    }
    fun playSongs(shuffled: Boolean) {
        val index = resolvePlaylistPlaybackStartIndex(
            songs.size, shuffled, if (songs.isEmpty()) 0 else Random.nextInt(songs.size)
        )
        if (index >= 0) {
            PlayerManager.setShuffle(shuffled)
            PlayerManager.playPlaylist(songs, index)
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                Surface(
                    modifier = Modifier.padding(horizontal = horizontalPadding).padding(top = 12.dp),
                    shape = NeriDesign.tokens.groupShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ServerSongActions(
                            songs, selectedSongs.size, true, songs.isNotEmpty() && selectedSongs.size == songs.size,
                            state.hasMore,
                            onPlay = { PlayerManager.playPlaylist(selectedSongs, 0) },
                            onAddToPlaylist = { exportSongs = selectedSongs.toList() },
                            onDownload = { downloadSongs(selectedSongs) },
                            onSelection = ::exitSelection, onSelectAll = ::selectAll
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(), state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(
                    start = horizontalPadding, end = horizontalPadding, top = 12.dp,
                    bottom = bottomPadding + NeriDesign.tokens.sectionSpacing
                )
            ) {
                if (showTitle && !selectionMode) item(key = "browser-header") {
                    ServerBrowserHeader(
                        title = stringResource(CoreCommonR.string.server_my_music),
                        refreshing = state.loading || state.accountsLoading,
                        canRefresh = state.profileId != null, onRefresh = vm::refresh,
                        onDownloads = { showDownloadManager = true },
                        onBack = null
                    )
                }
                if (!selectionMode) item(key = "profiles") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ServerProfileSelector(enabledProfiles, state.profileId, vm::select) }
                        if (!showTitle) ServerBrowserActions(
                            refreshing = state.loading || state.accountsLoading,
                            canRefresh = state.profileId != null,
                            onRefresh = vm::refresh,
                            onDownloads = { showDownloadManager = true }
                        )
                    }
                }
                if (state.accountsLoading) item(key = "accounts-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                }
                else if (state.accountsError != null) item(key = "accounts-error") {
                    ServerBrowseMessage(stringResource(requireNotNull(state.accountsError)), error = true, onRetry = vm::reloadAccounts)
                }
                else if (enabledProfiles.isEmpty()) item(key = "no-servers") {
                    ServerBrowseMessage(stringResource(CoreCommonR.string.server_empty))
                }
                else {
                    if (!selectionMode) item(key = "categories") {
                        ServerLibraryTabs(state.category) { category ->
                            exitSelection(); vm.setCategory(category); keyboard?.hide()
                        }
                    }
                    if (!selectionMode) item(key = "search") {
                        ServerSearchField(
                            query = state.inputQuery,
                            hint = stringResource(when (state.category) {
                                ServerLibraryCategory.ALBUMS -> CoreCommonR.string.server_search_albums_hint
                                ServerLibraryCategory.SONGS -> CoreCommonR.string.server_search_songs_hint
                                ServerLibraryCategory.ARTISTS -> CoreCommonR.string.server_search_artists_hint
                            }),
                            onQueryChange = vm::editQuery,
                            onSearch = { vm.search(state.inputQuery); keyboard?.hide() },
                            onClear = { vm.search(""); keyboard?.hide() }
                        )
                    }
                    if (state.loading) item(key = "loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                    state.error?.let { message -> item(key = "error") {
                        ServerBrowseMessage(stringResource(message), error = true, onRetry = vm::retry)
                    } }
                    if (!selectionMode && state.query.isNotBlank()) item(key = "search-title") {
                        Text(stringResource(CoreCommonR.string.server_search_results, state.query),
                            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    when (state.category) {
                        ServerLibraryCategory.SONGS -> {
                            if (!selectionMode) item(key = "song-actions") {
                                ServerSongActions(
                                    songs, selectedSongs.size, false, false, state.hasMore,
                                    onPlay = { playSongs(false) }, onShufflePlay = { playSongs(true) },
                                    onAddToPlaylist = { exportSongs = songs.toList() },
                                    onDownload = { downloadSongs(songs) },
                                    onSelection = { enterSelection() }, onSelectAll = ::selectAll
                                )
                            }
                            itemsIndexed(songs, key = { _, song -> "song:${song.stableKey()}" }) { index, song ->
                                val key = song.stableKey()
                                MusicServerSongRow(
                                    song, index + 1, currentSong?.sameIdentityAs(song) == true, playing,
                                    key in favoriteKeys, key in busyFavorites, selectionMode, key in selectedKeys,
                                    downloaded = key in downloadedKeys,
                                    onClick = { PlayerManager.playPlaylist(songs, index) }, onSelect = { select(song) },
                                    onFavorite = { toggleFavorite(song) }, onAddToPlaylist = { exportSongs = listOf(song) },
                                    onPlayNext = { PlayerManager.addToQueueNext(song) }, onAddToQueue = { PlayerManager.addToQueueEnd(song) },
                                    onDownload = { downloadSongs(listOf(song)) },
                                    offlineMode = offlineMode,
                                    downloadPresenceVersion = downloadPresenceVersion
                                )
                            }
                        }
                        ServerLibraryCategory.ALBUMS -> items(state.albums, key = { "album:${it.id}" }) { album ->
                            ServerAlbumRow(album, offlineMode = offlineMode) { onAlbumClick(album) }
                        }
                        ServerLibraryCategory.ARTISTS -> items(state.artists, key = { "artist:${it.id}" }) { artist ->
                            ServerArtistRow(artist, offlineMode = offlineMode) { onArtistClick(artist) }
                        }
                    }
                    val empty = when (state.category) {
                        ServerLibraryCategory.ALBUMS -> state.albums.isEmpty()
                        ServerLibraryCategory.SONGS -> songs.isEmpty()
                        ServerLibraryCategory.ARTISTS -> state.artists.isEmpty()
                    }
                    if (!state.loading && state.error == null && empty) item(key = "empty") {
                        ServerBrowseMessage(stringResource(CoreCommonR.string.server_no_results))
                    }
                }
            }
        }
        NeriOverlaySnackbarHost(hostState = snackbar, bottomPadding = bottomPadding)
    }
    if (showDownloadManager) {
        val tasks by GlobalDownloadManager.downloadTasks.collectAsStateWithLifecycle()
        BatchDownloadManagerSheet(downloadTasks = tasks, onDismiss = { showDownloadManager = false })
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
fun MusicServerEntry() {
    val entry = rememberMusicServerEntryState()
    val stateHolder = rememberSaveableStateHolder()
    MiuixSettingsTextButton(onClick = { entry.opened = true }) { Text(stringResource(CoreCommonR.string.server_my_music)) }
    if (entry.opened) {
        val backBridge = remember { SheetNavigationEventBridge() }
        DisposableEffect(backBridge) { onDispose(backBridge::dispose) }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides backBridge) {
            Dialog(onDismissRequest = entry::close,
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                SheetNavigationEventForwarding(backBridge)
                MusicServerDialogSystemBars()
                BackHandler(onBack = entry::back)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val selectedAlbum = entry.album
                    val selectedArtist = entry.artist
                    if (selectedAlbum != null) {
                        BackHandler(onBack = entry::back)
                        CompositionLocalProvider(LocalMiniPlayerHeight provides 0.dp) {
                            Box(Modifier.navigationBarsPadding()) {
                                ServerAlbumDetailScreen(selectedAlbum, onBack = entry::back,
                                    onSongClick = { songs, index -> PlayerManager.playPlaylist(songs, index) })
                            }
                        }
                    } else if (selectedArtist != null) {
                        BackHandler(onBack = entry::back)
                        CompositionLocalProvider(LocalMiniPlayerHeight provides 0.dp) {
                            Box(Modifier.navigationBarsPadding()) {
                                stateHolder.SaveableStateProvider(serverArtistDetailKey(selectedArtist)) {
                                    ServerArtistDetailScreen(selectedArtist, onBack = entry::back, onAlbumClick = { entry.album = it })
                                }
                            }
                        }
                    } else Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            HapticIconButton(onClick = entry::close) {
                                Icon(Icons.Outlined.Close, stringResource(CoreCommonR.string.server_close))
                            }
                        }
                        MusicServerScreen(onAlbumClick = { entry.album = it },
                            onArtistClick = { entry.artist = it },
                            modifier = Modifier.weight(1f), bottomContentPadding = 0.dp)
                    }
                }
            }
        }
    }
}
