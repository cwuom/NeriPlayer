package moe.ouom.neriplayer.ui.screen.artist

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.collect
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorItemsUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorItemsViewModel
import moe.ouom.neriplayer.ui.viewmodel.artist.toCreatorSongItem
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest

internal fun resolveYouTubeMusicCreatorItemsTitle(
    creatorName: String,
    sectionTitle: String,
    loadedTitle: String
): String {
    return listOfNotNull(
        creatorName.trim().takeIf(String::isNotBlank),
        loadedTitle.ifBlank { sectionTitle }.trim().takeIf(String::isNotBlank)
    ).distinct().joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeMusicCreatorItemsScreen(
    section: YouTubeMusicCreatorSection,
    creatorName: String = "",
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: YouTubeMusicCreatorItemsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                YouTubeMusicCreatorItemsViewModel(context.applicationContext as Application)
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val endpoint = section.moreEndpoint
    val listState = rememberSaveable(
        endpoint?.browseId,
        endpoint?.params,
        saver = LazyListState.Saver
    ) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    LaunchedEffect(endpoint?.browseId, endpoint?.params, section.title) {
        viewModel.start(section)
    }
    LaunchedEffect(viewModel, onSongClick) {
        viewModel.playbackRequests.collect { request ->
            onSongClick(request.songs, request.startIndex)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = resolveYouTubeMusicCreatorItemsTitle(
                            creatorName = creatorName,
                            sectionTitle = section.title,
                            loadedTitle = uiState.title
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
            YouTubeMusicCreatorItemsContent(
                uiState = uiState,
                listState = listState,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                onSongClick = viewModel::playSong,
                offlineMode = offlineMode,
                isTabletLayout = currentWindowWidthDp() >= 720.dp
            )
        }
    }
}

@Composable
private fun YouTubeMusicCreatorItemsContent(
    uiState: YouTubeMusicCreatorItemsUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSongClick: (YouTubeMusicCreatorItem) -> Unit,
    offlineMode: Boolean,
    isTabletLayout: Boolean
) {
    val playableItems = uiState.items.mapNotNull { item ->
        item.toCreatorSongItem()?.let { item to it }
    }
    val songs = playableItems.map { (_, song) -> song }
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(max = 1080.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (isTabletLayout) 36.dp else 20.dp,
                end = if (isTabletLayout) 36.dp else 20.dp,
                top = 4.dp,
                bottom = 40.dp + miniPlayerHeight
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if ((uiState.loading || uiState.playbackQueueLoading) && songs.isNotEmpty()) {
                item(key = "creator-items-loading") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (uiState.loading && songs.isEmpty()) {
                item(key = "creator-items-first-loading") {
                    CreatorItemsLoadingBlock()
                }
                return@LazyColumn
            }
            if (uiState.error != null && songs.isEmpty()) {
                item(key = "creator-items-error") {
                    CreatorItemsErrorBlock(
                        message = uiState.error,
                        onRetry = onRetry
                    )
                }
                return@LazyColumn
            }
            if (uiState.error != null) {
                item(key = "creator-items-stale-error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = uiState.error,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        HapticTextButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
            if (uiState.playbackQueueError != null) {
                item(key = "creator-items-playback-error") {
                    Text(
                        text = uiState.playbackQueueError,
                        modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (songs.isEmpty()) {
                item(key = "creator-items-empty") {
                    Text(
                        text = stringResource(R.string.youtube_creator_items_empty),
                        modifier = Modifier.padding(vertical = 28.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                itemsIndexed(
                    playableItems,
                    key = { _, pair -> pair.second.id }
                ) { index, pair ->
                    val (item, song) = pair
                    CreatorItemsSongRow(
                        song = song,
                        index = index + 1,
                        offlineMode = offlineMode,
                        enabled = !uiState.playbackQueueLoading && !uiState.loadingMore,
                        onClick = { onSongClick(item) }
                    )
                }
            }
            if (uiState.loadMoreError != null) {
                item(key = "creator-items-load-more-error") {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.loadMoreError,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        HapticTextButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
            if (uiState.continuation != null) {
                item(key = "creator-items-load-more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = onLoadMore,
                            enabled = !uiState.loadingMore && !uiState.playbackQueueLoading
                        ) {
                            if (uiState.loadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.youtube_creator_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorItemsSongRow(
    song: SongItem,
    index: Int,
    offlineMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val coverUrl = rememberSongDisplayCoverUrl(song)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                context.performHapticFeedback()
                onClick()
            }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = fastScrollableImageRequest(
                        context = context,
                        data = coverUrl,
                        sizePx = 128,
                        offlineMode = offlineMode
                    ),
                    contentDescription = song.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (song.durationMs > 0L) {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreatorItemsLoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CreatorItemsErrorBlock(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        HapticTextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
