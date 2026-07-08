package moe.ouom.neriplayer.ui.screen.playlist

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.countPendingDownloadTasks
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.buildLocalArtistSummaries
import moe.ouom.neriplayer.data.local.playlist.model.localArtistStableId
import moe.ouom.neriplayer.data.local.playlist.model.localArtistStableKey
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.component.BatchDownloadManagerSheet
import moe.ouom.neriplayer.ui.component.PlaylistExportSheet
import moe.ouom.neriplayer.ui.component.SongDownloadSubtitle
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.formatTotalDuration
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.performHapticFeedback

private fun hasCachedLocalArtistDownload(song: SongItem): Boolean {
    return GlobalDownloadManager.hasDownloadedSongCached(song) ||
        ManagedDownloadStorage.peekDownloadedAudio(song) != null
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
    val artistKey = remember(artistName) { localArtistStableKey(artistName) }
    val localArtists = remember(playlists, context) {
        buildLocalArtistSummaries(playlists, context)
    }
    val artist = remember(localArtists, artistKey) {
        localArtists.firstOrNull { it.stableKey == artistKey }
    }
    val songs = artist?.songs.orEmpty()
    val displayedSongs = remember(songs) { songs.asReversed() }
    val headerCover = rememberSongDisplayCoverUrl(artist?.coverSong)
    val title = artist?.name ?: artistName
    val artistId = remember(artistName) { localArtistStableId(artistName) }
    val scope = rememberCoroutineScope()
    var selectionMode by remember(artistKey) { mutableStateOf(false) }
    var selectedKeys by remember(artistKey) { mutableStateOf<Set<String>>(emptySet()) }
    var showExportSheet by remember(artistKey) { mutableStateOf(false) }
    var showDownloadManager by remember(artistKey) { mutableStateOf(false) }
    val downloadTaskSummary by GlobalDownloadManager.downloadTaskSummary.collectAsState()
    val hasDownloadManagerEntry = downloadTaskSummary.hasPendingTasks
    val downloadPresenceVersion by GlobalDownloadManager.downloadPresenceVersion.collectAsState()
    val selectedSongsForAction by remember(songs, selectedKeys) {
        derivedStateOf {
            songs.filter { it.stableKey() in selectedKeys }
        }
    }
    val hasSelectedOnlineSongs by remember(selectedSongsForAction) {
        derivedStateOf { selectedSongsForAction.any { !it.isLocalSong() } }
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

    LaunchedEffect(displayedSongs) {
        val validKeys = displayedSongs.map { it.stableKey() }.toSet()
        selectedKeys = selectedKeys.intersect(validKeys)
        if (selectionMode && selectedKeys.isEmpty()) {
            selectionMode = false
        }
    }

    LaunchedEffect(artist, headerCover) {
        if (artist == null) {
            AppContainer.playlistUsageRepo.removeEntry(
                artistId,
                PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
            )
            return@LaunchedEffect
        }

        AppContainer.playlistUsageRepo.updateInfo(
            id = artist.id,
            name = artist.name,
            picUrl = headerCover ?: artist.coverSong?.displayCoverUrl(context),
            trackCount = artist.songs.size,
            source = PlaylistUsageRepository.SOURCE_LOCAL_ARTIST
        )
    }

    Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                                    contentDescription = stringResource(R.string.action_back)
                                )
                            }
                        },
                        actions = {
                            if (hasDownloadManagerEntry) {
                                HapticIconButton(onClick = { showDownloadManager = true }) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.cd_download_manager),
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
                                    contentDescription = stringResource(R.string.cd_play_all)
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
                                    R.plurals.common_selected_count,
                                    selectedKeys.size,
                                    selectedKeys.size
                                )
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
                                        stringResource(R.string.action_deselect_all)
                                    } else {
                                        stringResource(R.string.action_select_all)
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
                                    contentDescription = stringResource(R.string.cd_export_playlist)
                                )
                            }
                            HapticIconButton(
                                onClick = {
                                    val onlineSongs = selectedSongsForAction.filterNot { it.isLocalSong() }
                                    if (onlineSongs.isNotEmpty()) {
                                        showDownloadManager = true
                                        exitSelectionMode()
                                        GlobalDownloadManager.startBatchDownload(context, onlineSongs)
                                    }
                                },
                                enabled = selectedSongsForAction.isNotEmpty() && hasSelectedOnlineSongs
                            ) {
                                Icon(
                                    Icons.Outlined.Download,
                                    contentDescription = stringResource(R.string.cd_download_selected)
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
                        text = stringResource(R.string.local_artist_detail_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp + LocalMiniPlayerHeight.current
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                item(key = "local_artist_header") {
                    LocalArtistDetailHeader(
                        title = title,
                        coverUrl = headerCover,
                        songCount = songs.size,
                        durationMs = songs.sumOf { it.durationMs },
                        offlineMode = offlineMode
                    )
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
                            if (selectionMode) {
                                toggleSelect(song)
                            } else {
                                onSongClick(displayedSongs, index)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedKeys = setOf(song.stableKey())
                            } else {
                                toggleSelect(song)
                            }
                        },
                        onToggleSelect = { toggleSelect(song) },
                        offlineMode = offlineMode
                    )
                }
            }
        }

        if (showExportSheet) {
            PlaylistExportSheet(
                title = stringResource(R.string.local_playlist_export_to),
                playlists = playlists.filterNot {
                    LocalFilesPlaylist.isSystemPlaylist(it, context)
                },
                selectedCount = selectedKeys.size,
                onDismissRequest = { showExportSheet = false },
                onCreateAndExport = { name ->
                    val selectedSongs = songs.filter { it.stableKey() in selectedKeys }
                    scope.launch {
                        repo.createPlaylistWithSongs(name, selectedSongs)
                    }
                    exitSelectionMode()
                },
                onExportToPlaylist = { target ->
                    val selectedSongs = songs.filter { it.stableKey() in selectedKeys }
                    scope.launch {
                        repo.addSongsToPlaylist(target.id, selectedSongs)
                    }
                    exitSelectionMode()
                }
            )
        }

        if (showDownloadManager) {
            val batchDownloadProgress by AudioDownloadManager.batchProgressFlow.collectAsState()
            val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsState()
            val currentPendingTaskCount = remember(downloadTasks) {
                countPendingDownloadTasks(downloadTasks)
            }
            val progress = batchDownloadProgress
            BatchDownloadManagerSheet(
                batchDownloadProgress = progress,
                downloadTasks = downloadTasks,
                progressSummaryText = if (progress != null) {
                    stringResource(
                        R.string.bili_download_progress_format,
                        progress.completedSongs,
                        progress.totalSongs
                    )
                } else {
                    pluralStringResource(
                        R.plurals.download_tasks_count,
                        currentPendingTaskCount,
                        currentPendingTaskCount
                    )
                },
                onDismiss = { showDownloadManager = false }
            )
        }

        BackHandler(enabled = selectionMode) { exitSelectionMode() }
    }
}

@Composable
private fun LocalArtistDetailHeader(
    title: String,
    coverUrl: String?,
    songCount: Int,
    durationMs: Long,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = coverUrl,
                        sizePx = 256,
                        allowHardware = false,
                        offlineMode = offlineMode
                    ),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.local_playlist_total_duration,
                        formatTotalDuration(context, durationMs),
                        songCount
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
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
    val coverUrl = rememberSongDisplayCoverUrl(song)
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
