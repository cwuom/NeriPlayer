package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.home.PlaylistCard
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest

internal data class NeteaseBrowseState(
    val selectedTag: String,
    val playlists: List<PlaylistSummary>,
    val loading: Boolean,
    val error: String?
)

internal data class YouTubeBrowseState(
    val playlists: List<YouTubeMusicPlaylist>,
    val loading: Boolean,
    val error: String?
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NeteaseDefaultContent(
    gridState: LazyGridState,
    browse: NeteaseBrowseState,
    tagKeys: List<String>,
    tagLabels: List<String>,
    favoriteKeys: Set<String>,
    onTagSelected: (String) -> Unit,
    onPlay: (PlaylistSummary) -> Unit,
    tagChipSelectedAlpha: Float,
    tagChipUnselectedAlpha: Float,
    tagChipBorderAlpha: Float,
    isTabletLayout: Boolean = false
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val gridHorizontalPadding = if (isTabletLayout) 56.dp else 16.dp
    val gridMinCellSize = if (isTabletLayout) 170.dp else 150.dp
    val gridSpacing = if (isTabletLayout) 16.dp else 12.dp
    val tagListState = rememberLazyListState()
    val showTagStartFade by remember(tagListState) {
        derivedStateOf { tagListState.canScrollBackward }
    }
    val showTagEndFade by remember(tagListState) {
        derivedStateOf { tagListState.canScrollForward }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(gridMinCellSize),
        verticalArrangement = Arrangement.spacedBy(gridSpacing),
        horizontalArrangement = Arrangement.spacedBy(gridSpacing),
        contentPadding = PaddingValues(
            start = gridHorizontalPadding,
            end = gridHorizontalPadding,
            top = 16.dp,
            bottom = 16.dp + miniPlayerHeight
        ),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                val displayKeys = tagKeys
                val displayLabels = tagLabels
                LazyRow(
                    state = tagListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .exploreHorizontalEdgeFade(
                            showStartFade = showTagStartFade,
                            showEndFade = showTagEndFade
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayKeys) { index, tagKey ->
                        val selected = (browse.selectedTag == tagKey)
                        ExploreTagChip(
                            label = displayLabels[index],
                            selected = selected,
                            onClick = { if (!selected) onTagSelected(tagKey) },
                            selectedAlpha = tagChipSelectedAlpha,
                            unselectedAlpha = tagChipUnselectedAlpha,
                            borderAlpha = tagChipBorderAlpha
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (browse.loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (browse.playlists.isNotEmpty()) {
            items(items = browse.playlists, key = { it.id }) { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    isFavorite = favoriteKeys.contains("netease:${playlist.id}"),
                    onClick = { onPlay(playlist) }
                )
            }
        } else if (browse.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (browse.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(browse.error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun ExploreTagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedAlpha: Float,
    unselectedAlpha: Float,
    borderAlpha: Float,
    icon: ImageVector? = null
) {
    ExploreGlassPillSurface(
        fallbackColor = tagContainerColor(selected, selectedAlpha, unselectedAlpha),
        tintColor = tagTintColor(selected),
        contentColor = tagContentColor(selected),
        border = BorderStroke(1.dp, tagBorderColor(selected, borderAlpha)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExploreTagIcon(icon)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun tagContainerColor(selected: Boolean, selectedAlpha: Float, unselectedAlpha: Float): Color {
    val colors = MaterialTheme.colorScheme
    return if (selected) colors.secondaryContainer.copy(alpha = selectedAlpha)
    else colors.surface.copy(alpha = unselectedAlpha)
}

@Composable
private fun tagTintColor(selected: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    return if (selected) colors.secondaryContainer else colors.surface
}

@Composable
private fun tagContentColor(selected: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    return if (selected) colors.onSecondaryContainer else colors.onSurface
}

@Composable
private fun tagBorderColor(selected: Boolean, borderAlpha: Float): Color {
    val colors = MaterialTheme.colorScheme
    return if (selected) colors.secondary.copy(alpha = borderAlpha)
    else colors.outline.copy(alpha = borderAlpha)
}

@Composable
private fun ExploreTagIcon(icon: ImageVector?) {
    if (icon == null) return
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
}

@Composable
internal fun YouTubeMusicExploreContent(
    browse: YouTubeBrowseState,
    onRetry: () -> Unit,
    onClick: (YouTubeMusicPlaylist) -> Unit,
    offlineMode: Boolean,
    gridState: LazyGridState,
    isTabletLayout: Boolean = false
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val gridHorizontalPadding = if (isTabletLayout) 56.dp else 16.dp
    val gridMinCellSize = if (isTabletLayout) 156.dp else 120.dp
    val gridSpacing = if (isTabletLayout) 14.dp else 10.dp
    when {
        browse.loading -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = miniPlayerHeight),
                Alignment.Center
            ) { CircularProgressIndicator() }
        }
        browse.error != null -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = miniPlayerHeight),
                Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        browse.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    HapticTextButton(onClick = onRetry) {
                        Text(stringResource(CoreCommonR.string.action_retry))
                    }
                }
            }
        }
        browse.playlists.isEmpty() -> {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = miniPlayerHeight),
                Alignment.Center
            ) {
                Text(
                    stringResource(CoreCommonR.string.explore_tag_youtube_music),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        else -> {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(gridMinCellSize),
                contentPadding = PaddingValues(
                    start = gridHorizontalPadding, end = gridHorizontalPadding,
                    top = 8.dp,
                    bottom = 16.dp + miniPlayerHeight
                ),
                verticalArrangement = Arrangement.spacedBy(gridSpacing),
                horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = browse.playlists,
                    key = { it.browseId }
                ) { playlist ->
                    YtMusicExploreCard(
                        playlist = playlist,
                        onClick = { onClick(playlist) },
                        offlineMode = offlineMode
                    )
                }
            }
        }
    }
}

@Composable
private fun YtMusicExploreCard(
    playlist: YouTubeMusicPlaylist,
    onClick: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = fastScrollableImageRequest(
                context = context,
                data = playlist.coverUrl,
                sizePx = 384,
                offlineMode = offlineMode
            ),
            contentDescription = playlist.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        )
        Column(modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = playlist.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            YouTubeBrowseCardSubtitle(playlist.subtitle)
        }
    }
}

@Composable
private fun YouTubeBrowseCardSubtitle(subtitle: String) {
    if (subtitle.isBlank()) return
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}
