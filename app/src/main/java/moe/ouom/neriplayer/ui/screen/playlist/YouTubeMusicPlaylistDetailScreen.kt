package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.ClipData
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.displayCoverUrl
import moe.ouom.neriplayer.data.displayArtist
import moe.ouom.neriplayer.data.displayName
import moe.ouom.neriplayer.data.sameIdentityAs
import moe.ouom.neriplayer.data.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.YouTubeMusicPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.util.HapticFloatingActionButton
import moe.ouom.neriplayer.util.HapticIconButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.formatDuration
import moe.ouom.neriplayer.util.offlineCachedImageRequest
import moe.ouom.neriplayer.util.performHapticFeedback

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun YouTubeMusicPlaylistDetailScreen(
    playlist: YouTubeMusicPlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val viewModel: YouTubeMusicPlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                YouTubeMusicPlaylistDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by viewModel.uiState.collectAsState()
    val currentSong by PlayerManager.currentSongFlow.collectAsState()
    val isPlaying by PlayerManager.isPlayingFlow.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(playlist.browseId) {
        viewModel.start(playlist)
    }

    var latestPlaylist by remember { mutableStateOf<YouTubeMusicPlaylist?>(playlist) }
    LaunchedEffect(ui.playlist) {
        ui.playlist?.let { latestPlaylist = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestPlaylist?.let { updated ->
                AppContainer.playlistUsageRepo.updateInfo(
                    id = updated.playlistId.hashCode().toLong(),
                    name = updated.title,
                    picUrl = updated.coverUrl,
                    trackCount = updated.trackCount,
                    source = "youtubeMusic",
                    browseId = updated.browseId,
                    playlistId = updated.playlistId
                )
            }
        }
    }

    val resolvedPlaylist = ui.playlist ?: playlist
    val resolvedTrackCount = resolvedPlaylist.trackCount.takeIf { it > 0 } ?: ui.tracks.size
    val displayedTracks = remember(ui.tracks, searchQuery) {
        if (searchQuery.isBlank()) {
            ui.tracks
        } else {
            ui.tracks.filter { song ->
                song.displayName().contains(searchQuery, ignoreCase = true) ||
                    song.displayArtist().contains(searchQuery, ignoreCase = true) ||
                    song.name.contains(searchQuery, ignoreCase = true) ||
                    song.artist.contains(searchQuery, ignoreCase = true) ||
                    song.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val currentIndex = displayedTracks.indexOfFirst { it.sameIdentityAs(currentSong) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = resolvedPlaylist.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) {
                                searchQuery = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search_songs)
                        )
                    }
                    if (ui.tracks.isNotEmpty()) {
                        HapticIconButton(onClick = { onSongClick(ui.tracks, 0) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                                contentDescription = stringResource(R.string.player_play_all)
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(showSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.playlist_search_hint)) },
                    singleLine = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = miniPlayerHeight + 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        YouTubeMusicHeroHeader(
                            playlist = resolvedPlaylist,
                            trackCount = resolvedTrackCount
                        )
                    }

                    when {
                        ui.loading && ui.tracks.isEmpty() -> {
                            item {
                                LoadingBlock()
                            }
                        }

                        ui.error != null && ui.tracks.isEmpty() -> {
                            item {
                                ErrorBlock(
                                    message = ui.error.orEmpty(),
                                    onRetry = viewModel::retry
                                )
                            }
                        }

                        displayedTracks.isEmpty() -> {
                            item {
                                EmptyBlock(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.library_youtube_music_empty)
                                    } else {
                                        stringResource(R.string.search_no_match)
                                    }
                                )
                            }
                        }

                        else -> {
                            itemsIndexed(
                                items = displayedTracks,
                                key = { _, song -> song.stableKey() }
                            ) { index, song ->
                                val isCurrent = currentSong?.sameIdentityAs(song) == true
                                YouTubeMusicSongRow(
                                    index = index + 1,
                                    song = song,
                                    isCurrentSong = isCurrent,
                                    animatePlayingIndicator = isCurrent && isPlaying,
                                    snackbarHostState = snackbarHostState,
                                    onClick = {
                                        val targetIndex = ui.tracks.indexOfFirst {
                                            it.sameIdentityAs(song)
                                        }
                                        if (targetIndex >= 0) {
                                            onSongClick(ui.tracks, targetIndex)
                                        }
                                    },
                                    onPlayNext = { PlayerManager.addToQueueNext(song) },
                                    onAddToQueueEnd = { PlayerManager.addToQueueEnd(song) },
                                    onDownload = {
                                        GlobalDownloadManager.startDownload(context, song)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.download_starting, song.displayName())
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (currentIndex >= 0) {
                    HapticFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(currentIndex + 1)
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
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = stringResource(R.string.cd_locate_playing)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeMusicHeroHeader(
    playlist: YouTubeMusicPlaylist,
    trackCount: Int
) {
    val context = LocalContext.current
    val coverModel = playlist.coverUrl.takeUnless { it.isBlank() } ?: "about:blank"
    val surfaceTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.26f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        AsyncImage(
            model = offlineCachedImageRequest(context, coverModel),
            contentDescription = playlist.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.12f),
                                Color.Black.copy(alpha = 0.38f),
                                surfaceTint
                            ),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (playlist.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = playlist.subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.55f),
                            offset = Offset(2f, 2f),
                            blurRadius = 4f
                        )
                    ),
                    color = Color.White.copy(alpha = 0.94f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.library_favorite_source_format,
                    trackCount,
                    "YouTube Music"
                ),
                style = MaterialTheme.typography.bodySmall.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                color = Color.White.copy(alpha = 0.88f)
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = stringResource(R.string.playlist_loading_content))
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.playlist_load_failed_format, message),
            color = MaterialTheme.colorScheme.error
        )
        HapticTextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun EmptyBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeMusicSongRow(
    index: Int,
    song: SongItem,
    isCurrentSong: Boolean,
    animatePlayingIndicator: Boolean,
    snackbarHostState: SnackbarHostState,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueueEnd: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    context.performHapticFeedback()
                    onClick()
                },
                onLongClick = {
                    context.performHapticFeedback()
                    onPlayNext()
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        val coverModel = song.displayCoverUrl(context).takeUnless { it.isNullOrBlank() }
        val displayName = song.displayName()
        val displayArtist = song.displayArtist()
        if (!coverModel.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                AsyncImage(
                    model = offlineCachedImageRequest(context, coverModel),
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    displayArtist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isCurrentSong) {
            PlayingIndicator(
                color = MaterialTheme.colorScheme.primary,
                animate = animatePlayingIndicator
            )
        } else {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.common_more_actions)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_playlist_play_next)) },
                    onClick = {
                        onPlayNext()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_add_to_end)) },
                    onClick = {
                        onAddToQueueEnd()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download_to_local)) },
                    onClick = {
                        onDownload()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_song_info)) },
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        "text",
                                        "${displayName}-${displayArtist}"
                                    )
                                )
                            )
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.toast_copied)
                            )
                        }
                        showMenu = false
                    }
                )
            }
        }
    }
}
