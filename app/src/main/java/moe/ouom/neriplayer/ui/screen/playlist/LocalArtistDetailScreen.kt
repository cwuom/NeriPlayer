package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.identity.stableKey

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.launchLocalPlaylistMutation
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.artist.findLocalArtistSummary
import moe.ouom.neriplayer.data.model.playlist.localArtistStableId
import moe.ouom.neriplayer.data.model.playlist.localArtistStableKey
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.artist.CreatorDetailAdaptiveLayout
import moe.ouom.neriplayer.ui.component.download.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.playlist.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportAddedResult
import moe.ouom.neriplayer.ui.component.playlist.showPlaylistBatchExportCreatedResult
import moe.ouom.neriplayer.ui.component.download.SongDownloadSubtitle
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.util.rememberLocalArtistDisplayCoverUrl
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.format.formatTotalDuration
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.util.search.playlistSearchValues
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import kotlin.time.Duration.Companion.milliseconds

private fun hasCachedLocalArtistDownload(song: SongItem): Boolean {
    return GlobalDownloadManager.hasDownloadedSongCached(song)
}

private fun SongItem.localArtistSongSearchTokens(
    context: android.content.Context
): List<Any?> {
    return playlistSearchValues(context)
}

internal fun shouldRemoveMissingLocalArtistUsage(
    localPlaylistsReady: Boolean,
    artistFound: Boolean
): Boolean = localPlaylistsReady && !artistFound

private data class LocalArtistDetailSnapshot(
    val artist: LocalArtistSummary?,
    val totalDurationMs: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalArtistDetailLoadingScreen(
    artistName: String,
    onBack: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = artistName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        HapticIconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreCommonR.string.action_back)
                            )
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalArtistDetailScreen(
    artistName: String,
    onBack: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val repo = remember(context) { LocalPlaylistRepository.getInstance(context) }
    val playlists by repo.playlists.collectAsState()
    val localPlaylistsReady by repo.initializationReadyFlow.collectAsState()
    val artistKey = remember(artistName) { localArtistStableKey(artistName) }
    val unknownArtist = stringResource(CoreCommonR.string.music_unknown_artist)
    val artistSnapshot by produceState<LocalArtistDetailSnapshot?>(
        initialValue = null,
        key1 = playlists,
        key2 = artistKey,
        key3 = unknownArtist
    ) {
        value = null
        value = withContext(Dispatchers.Default) {
            val artist = findLocalArtistSummary(
                playlists = playlists,
                artistKey = artistKey,
                unknownArtist = unknownArtist
            )
            LocalArtistDetailSnapshot(
                artist = artist,
                totalDurationMs = artist?.songs?.sumOf { song -> song.durationMs } ?: 0L
            )
        }
    }
    val resolvedArtistSnapshot = artistSnapshot
    if (!localPlaylistsReady || resolvedArtistSnapshot == null) {
        LocalArtistDetailLoadingScreen(artistName = artistName, onBack = onBack)
        return
    }

    val artist = resolvedArtistSnapshot.artist
    val songs = artist?.songs.orEmpty()
    val baseSongs = remember(songs) { songs.toList() }
    val headerCover = rememberLocalArtistDisplayCoverUrl(artist)
    val title = artist?.name ?: artistName
    val artistId = remember(artistName) { localArtistStableId(artistName) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSearch by remember(artistKey) { mutableStateOf(false) }
    var searchQuery by remember(artistKey) { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val displayedSongs = rememberPlaylistSearchResults(
        query = searchQuery,
        items = baseSongs,
        tokens = { song -> song.localArtistSongSearchTokens(context) },
        buildIndex = shouldBuildPlaylistSearchIndex(
            searchVisible = showSearch,
            query = searchQuery
        )
    )
    var selectionMode by remember(artistKey) { mutableStateOf(false) }
    var selectedKeys by remember(artistKey) { mutableStateOf<Set<String>>(emptySet()) }
    var showExportSheet by remember(artistKey) { mutableStateOf(false) }
    var showDownloadManager by remember(artistKey) { mutableStateOf(false) }
    val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val hasDownloadManagerEntry = downloadTaskSummary.hasDownloadManagerEntry
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
    val selectedSongsForAction by remember(songs, selectedKeys) {
        derivedStateOf {
            selectedSongsInSourceOrder(songs, selectedKeys)
        }
    }

    fun toggleSelect(song: SongItem) {
        val songKey = song.stableKey()
        selectedKeys = if (songKey in selectedKeys) {
            selectedKeys - songKey
        } else {
            selectedKeys + songKey
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedKeys = emptySet()
    }

    fun closeSearch() {
        showSearch = false
        searchQuery = ""
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    LaunchedEffect(songs) {
        selectedKeys = retainExistingSongSelectionKeys(songs, selectedKeys)
        if (selectionMode && selectedKeys.isEmpty()) {
            selectionMode = false
        }
    }

    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsState(initial = false)

    LaunchedEffect(showSearch, selectionMode) {
        if (showSearch && !selectionMode && autoShowKeyboard) {
            delay(120.milliseconds)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(artist, headerCover, localPlaylistsReady) {
        if (!localPlaylistsReady) return@LaunchedEffect
        if (artist == null) {
            if (shouldRemoveMissingLocalArtistUsage(localPlaylistsReady, artistFound = false)) {
                withContext(Dispatchers.IO) {
                    AppContainer.playlistUsageRepo.removeEntry(
                        artistId,
                        PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
                    )
                }
            }
            return@LaunchedEffect
        }

        val cover = headerCover ?: artist.displayCoverUrl(context)
        withContext(Dispatchers.IO) {
            AppContainer.playlistUsageRepo.updateInfo(
                id = artist.id,
                name = artist.name,
                picUrl = cover,
                trackCount = artist.songs.size,
                source = PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
            )
        }
    }

    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            HapticIconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(CoreCommonR.string.action_back)
                                )
                            }
                        },
                        actions = {
                            HapticIconButton(
                                enabled = songs.isNotEmpty(),
                                onClick = {
                                    if (showSearch) {
                                        closeSearch()
                                    } else {
                                        showSearch = true
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(CoreCommonR.string.cd_search_songs)
                                )
                            }
                            if (hasDownloadManagerEntry) {
                                HapticIconButton(onClick = { showDownloadManager = true }) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(CoreCommonR.string.cd_download_manager),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            HapticIconButton(
                                enabled = displayedSongs.isNotEmpty(),
                                onClick = {
                                    if (displayedSongs.isNotEmpty()) {
                                        onSongClick(displayedSongs, 0)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = stringResource(CoreCommonR.string.cd_play_all)
                                )
                            }
                        },
                        windowInsets = WindowInsets.statusBars,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                } else {
                    val displayedSongKeys = displayedSongs.map { it.stableKey() }.toSet()
                    val allSelected = areDisplayedSongKeysSelected(
                        selectedKeys = selectedKeys,
                        displayedKeys = displayedSongKeys
                    )
                    TopAppBar(
                        title = {
                            Text(
                                pluralStringResource(
                                    CoreCommonR.plurals.common_selected_count,
                                    selectedKeys.size,
                                    selectedKeys.size
                                )
                            )
                        },
                        navigationIcon = {
                            HapticIconButton(onClick = { exitSelectionMode() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(CoreCommonR.string.cd_exit_select)
                                )
                            }
                        },
                        actions = {
                            HapticIconButton(
                                onClick = {
                                    selectedKeys = toggleDisplayedSongSelection(
                                        selectedKeys = selectedKeys,
                                        displayedKeys = displayedSongKeys
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = if (allSelected) {
                                        Icons.Filled.CheckBox
                                    } else {
                                        Icons.Filled.CheckBoxOutlineBlank
                                    },
                                    contentDescription = if (allSelected) {
                                        stringResource(CoreCommonR.string.action_deselect_all)
                                    } else {
                                        stringResource(CoreCommonR.string.action_select_all)
                                    }
                                )
                            }
                            HapticIconButton(
                                onClick = {
                                    if (selectedKeys.isNotEmpty()) {
                                        showExportSheet = true
                                    }
                                },
                                enabled = selectedKeys.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistAdd,
                                    contentDescription = stringResource(CoreCommonR.string.cd_export_playlist)
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
                                    contentDescription = stringResource(CoreCommonR.string.cd_download_selected)
                                )
                            }
                        },
                        windowInsets = WindowInsets.statusBars,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            scrolledContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        ) { padding ->
            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(CoreCommonR.string.local_artist_detail_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            LocalArtistDetailContent(
                title = title,
                headerCover = headerCover,
                songCount = songs.size,
                durationMs = resolvedArtistSnapshot.totalDurationMs,
                displayedSongs = displayedSongs,
                listState = listState,
                showSearch = showSearch,
                searchQuery = searchQuery,
                searchFocusRequester = searchFocusRequester,
                selectionMode = selectionMode,
                selectedKeys = selectedKeys,
                downloadPresenceVersion = downloadPresenceVersion,
                onQueryChange = { searchQuery = it },
                onSongClick = onSongClick,
                onToggleSelect = ::toggleSelect,
                onLongClick = { song ->
                    if (!selectionMode) {
                        selectionMode = true
                        selectedKeys = setOf(song.stableKey())
                    } else {
                        toggleSelect(song)
                    }
                },
                offlineMode = offlineMode,
                modifier = Modifier.padding(padding)
            )
        }

        if (showExportSheet) {
            PlaylistExportSheet(
                title = stringResource(CoreCommonR.string.local_playlist_export_to),
                playlists = playlists.filterNot {
                    LocalFilesPlaylist.isSystemPlaylist(it, context)
                },
                selectedCount = selectedKeys.size,
                onDismissRequest = { showExportSheet = false },
                onCreateAndExport = { name ->
                    val selectedSongs = songs.filter { it.stableKey() in selectedKeys }
                    scope.launchLocalPlaylistMutation(
                        operation = "createPlaylistFromLocalArtist",
                        onResult = { result ->
                            scope.showPlaylistBatchExportCreatedResult(
                                context = context,
                                snackbarHostState = snackbarHostState,
                                repository = repo,
                                result = result
                            )
                        }
                    ) {
                        repo.createPlaylistWithPreparedSongs(name, selectedSongs)
                    }
                    exitSelectionMode()
                },
                onExportToPlaylist = { target ->
                    val selectedSongs = songs.filter { it.stableKey() in selectedKeys }
                    scope.launchLocalPlaylistMutation(
                        operation = "exportSongsFromLocalArtist",
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
                        repo.addPreparedSongsToPlaylistWithResult(target.id, selectedSongs)
                    }
                    exitSelectionMode()
                }
            )
        }

        if (showDownloadManager) {
            val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
            BatchDownloadManagerSheet(
                downloadTasks = downloadTasks,
                onDismiss = { showDownloadManager = false }
            )
        }

        BackHandler(enabled = selectionMode) { exitSelectionMode() }
        BackHandler(enabled = showSearch && !selectionMode) { closeSearch() }
    }
}

@Composable
internal fun LocalArtistDetailContent(
    title: String,
    headerCover: String?,
    songCount: Int,
    durationMs: Long,
    displayedSongs: List<SongItem>,
    listState: LazyListState,
    showSearch: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    downloadPresenceVersion: Int,
    onQueryChange: (String) -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onToggleSelect: (SongItem) -> Unit,
    onLongClick: (SongItem) -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    CreatorDetailAdaptiveLayout(
        tabletDevice = LocalConfiguration.current.smallestScreenWidthDp >= 600,
        miniPlayerHeight = miniPlayerHeight,
        modifier = modifier,
        profile = {
            LocalArtistDetailHeader(
                title = title,
                coverUrl = headerCover,
                songCount = songCount,
                durationMs = durationMs,
                offlineMode = offlineMode,
                tabletProfile = true
            )
        }
    ) { tabletLayout ->
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(showSearch && !selectionMode) {
                PlaylistModernVisualColorsProvider(
                    coverUrl = headerCover,
                    offlineMode = offlineMode
                ) {
                    PlaylistModernDockedSearchField(
                        query = searchQuery,
                        onQueryChange = onQueryChange,
                        placeholder = stringResource(CoreCommonR.string.search_artist_songs),
                        focusRequester = searchFocusRequester
                    )
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (tabletLayout) 12.dp else 12.dp + miniPlayerHeight
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                item(key = "local_artist_header") {
                    if (!tabletLayout) LocalArtistDetailHeader(
                        title = title,
                        coverUrl = headerCover,
                        songCount = songCount,
                        durationMs = durationMs,
                        offlineMode = offlineMode
                    )
                }
                if (displayedSongs.isEmpty()) {
                    item(key = "local_artist_search_empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.search_no_match),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                itemsIndexed(
                    items = displayedSongs,
                    key = { _, song -> song.stableKey() }
                ) { index, song ->
                    LocalArtistSongRow(
                        index = index + 1,
                        song = song,
                        selectionMode = selectionMode,
                        selected = song.stableKey() in selectedKeys,
                        downloaded = remember(downloadPresenceVersion, song) {
                            hasCachedLocalArtistDownload(song)
                        },
                        onClick = {
                            if (selectionMode) onToggleSelect(song)
                            else onSongClick(displayedSongs, index)
                        },
                        onLongClick = { onLongClick(song) },
                        onToggleSelect = { onToggleSelect(song) },
                        offlineMode = offlineMode
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalArtistDetailHeader(
    title: String,
    coverUrl: String?,
    songCount: Int,
    durationMs: Long,
    offlineMode: Boolean,
    tabletProfile: Boolean = false
) {
    val context = LocalContext.current
    val coverModifier = (if (tabletProfile) {
        Modifier.fillMaxWidth().aspectRatio(1f)
    } else {
        Modifier.size(96.dp)
    }).clip(RoundedCornerShape(18.dp))
    val cover: @Composable () -> Unit = {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = offlineCachedImageRequest(
                    context = context,
                    data = coverUrl,
                    sizePx = if (tabletProfile) 512 else 256,
                    allowHardware = false,
                    offlineMode = offlineMode
                ),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = coverModifier
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = coverModifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (tabletProfile) 72.dp else 52.dp)
                )
            }
        }
    }
    val identity: @Composable () -> Unit = {
        Text(
            text = title,
            maxLines = if (tabletProfile) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                CoreCommonR.string.local_artist_total_duration,
                formatTotalDuration(context, durationMs),
                songCount
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (tabletProfile) {
            Column(modifier = Modifier.padding(16.dp)) {
                cover()
                Spacer(Modifier.height(20.dp))
                identity()
            }
        } else Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            cover()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                identity()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalArtistSongRow(
    index: Int,
    song: SongItem,
    selectionMode: Boolean,
    selected: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val coverUrl = rememberSongDisplayCoverUrl(
        song = song,
        resolveLocalFallback = false
    )
    val rowContainerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowContainerColor)
            .combinedClickable(
                onClick = {
                    context.performHapticFeedback()
                    onClick()
                },
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(34.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }

        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = offlineCachedImageRequest(
                    context = context,
                    data = coverUrl,
                    sizePx = 160,
                    allowHardware = false,
                    offlineMode = offlineMode
                ),
                contentDescription = song.displayName(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Spacer(Modifier.size(48.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = song.displayName(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            SongDownloadSubtitle(
                text = song.displayArtist(),
                downloaded = downloaded
            )
        }
    }
}
