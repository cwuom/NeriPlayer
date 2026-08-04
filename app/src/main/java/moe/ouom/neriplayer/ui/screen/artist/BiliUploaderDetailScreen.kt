package moe.ouom.neriplayer.ui.screen.artist

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
 * File: moe.ouom.neriplayer.ui.screen.artist/BiliUploaderDetailScreen
 * Created: 2026/8/3
 */

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliThumbnailUrl
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.ui.BlurTransformation
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderHeader
import moe.ouom.neriplayer.ui.viewmodel.artist.toBiliVideoItem
import moe.ouom.neriplayer.ui.screen.playlist.preloadBiliPlaylistDetailVisuals
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.util.format.formatDurationSec
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

private const val UPLOADER_AVATAR_BACKDROP_BLUR_RADIUS = 100f
private const val UPLOADER_AVATAR_BACKDROP_SIZE_PX = 256

internal data class BiliUploaderBackdropSources(
    val bannerUrl: String,
    val avatarUrl: String
)

internal fun resolveBiliUploaderBackdropSources(
    bannerUrl: String?,
    avatarUrl: String?
): BiliUploaderBackdropSources {
    return BiliUploaderBackdropSources(
        bannerUrl = bannerUrl?.trim().orEmpty(),
        avatarUrl = avatarUrl?.trim().orEmpty()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliUploaderDetailScreen(
    uploader: BiliUploaderSummary,
    onBack: () -> Unit = {},
    onPlayAudio: (List<BiliVideoItem>, Int) -> Unit = { _, _ -> },
    onPlayParts: (BiliClient.VideoBasicInfo, Int, String) -> Unit = { _, _, _ -> },
    onContentClick: (BiliPlaylist) -> Unit = {},
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: BiliUploaderDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                BiliUploaderDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by viewModel.uiState.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var selectedTab by rememberSaveable(uploader.mid) { mutableIntStateOf(0) }
    val isTabletLayout = currentWindowWidthDp() >= 720.dp
    val listState = rememberSaveable(uploader.mid, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }

    LaunchedEffect(uploader.mid) {
        viewModel.start(uploader)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = ui.header?.name ?: uploader.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
            BiliUploaderContent(
                ui = ui,
                listState = listState,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onRetry = viewModel::retry,
                onLoadMoreVideos = viewModel::loadMoreVideos,
                onLoadMoreContents = viewModel::loadMoreContents,
                onVideoClick = { video, index ->
                    scope.launch {
                        runCatching { viewModel.getVideoInfo(video.bvid) }
                            .onSuccess { info ->
                                if (info.pages.size <= 1) {
                                    onPlayAudio(ui.videos.map(BiliClient.UploaderVideo::toBiliVideoItem), index)
                                } else {
                                    onPlayParts(info, 0, video.coverUrl)
                                }
                            }
                    }
                },
                onContentClick = { content ->
                    val playlist = content.toBiliPlaylist(
                        uploaderName = ui.header?.name ?: uploader.name
                    )
                    preloadBiliPlaylistDetailVisuals(
                        context = context,
                        coverUrl = playlist.coverUrl,
                        offlineMode = offlineMode
                    )
                    onContentClick(playlist)
                },
                offlineMode = offlineMode,
                isTabletLayout = isTabletLayout
            )
        }
    }
}

@Composable
private fun BiliUploaderContent(
    ui: BiliUploaderDetailUiState,
    listState: LazyListState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onRetry: () -> Unit,
    onLoadMoreVideos: () -> Unit,
    onLoadMoreContents: () -> Unit,
    onVideoClick: (BiliClient.UploaderVideo, Int) -> Unit,
    onContentClick: (BiliClient.UploaderContent) -> Unit,
    offlineMode: Boolean,
    isTabletLayout: Boolean
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val collectionsEmptyText = stringResource(R.string.bili_uploader_collections_empty)
    val seriesEmptyText = stringResource(R.string.bili_uploader_series_empty)
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
            item {
                BiliUploaderHeaderCard(
                    header = ui.header,
                    videoCount = ui.videos.size,
                    collectionCount = ui.collections.size,
                    seriesCount = ui.series.size,
                    offlineMode = offlineMode,
                    isTabletLayout = isTabletLayout
                )
            }

            if (ui.error != null && !ui.loading) {
                item {
                    Text(
                        text = ui.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            if (ui.loading && ui.videos.isEmpty() && ui.collections.isEmpty() && ui.series.isEmpty()) {
                item { LoadingBlock() }
                return@LazyColumn
            }

            if (ui.error != null && ui.videos.isEmpty() && ui.collections.isEmpty() && ui.series.isEmpty()) {
                item { ErrorBlock(error = ui.error, onRetry = onRetry) }
                return@LazyColumn
            }

            item {
                BiliUploaderTabs(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected
                )
            }

            when (selectedTab) {
                0 -> {
                    if (ui.videos.isEmpty()) {
                        item { EmptyBlock(stringResource(R.string.bili_uploader_videos_empty)) }
                    } else {
                        itemsIndexed(ui.videos, key = { _, video -> video.bvid }) { index, video ->
                            BiliUploaderVideoRow(
                                video = video,
                                onClick = { onVideoClick(video, index) },
                                offlineMode = offlineMode
                            )
                        }
                    }
                    if (ui.videosHasMore) {
                        item {
                            BiliUploaderLoadMoreButton(
                                loading = ui.videosLoadingMore,
                                onClick = onLoadMoreVideos
                            )
                        }
                    }
                }

                1 -> {
                    BiliUploaderContentRows(
                        items = ui.collections,
                        emptyText = collectionsEmptyText,
                        onContentClick = onContentClick,
                        offlineMode = offlineMode
                    )
                    if (ui.contentsHasMore) {
                        item {
                            BiliUploaderLoadMoreButton(
                                loading = ui.contentsLoadingMore,
                                onClick = onLoadMoreContents
                            )
                        }
                    }
                }

                else -> {
                    BiliUploaderContentRows(
                        items = ui.series,
                        emptyText = seriesEmptyText,
                        onContentClick = onContentClick,
                        offlineMode = offlineMode
                    )
                    if (ui.contentsHasMore) {
                        item {
                            BiliUploaderLoadMoreButton(
                                loading = ui.contentsLoadingMore,
                                onClick = onLoadMoreContents
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.BiliUploaderContentRows(
    items: List<BiliClient.UploaderContent>,
    emptyText: String,
    onContentClick: (BiliClient.UploaderContent) -> Unit,
    offlineMode: Boolean
) {
    if (items.isEmpty()) {
        item { EmptyBlock(emptyText) }
    } else {
        itemsIndexed(items, key = { _, content -> content.id }) { _, content ->
            BiliUploaderContentRow(
                content = content,
                onClick = { onContentClick(content) },
                offlineMode = offlineMode
            )
        }
    }
}

@Composable
private fun BiliUploaderHeaderCard(
    header: BiliUploaderHeader?,
    videoCount: Int,
    collectionCount: Int,
    seriesCount: Int,
    offlineMode: Boolean,
    isTabletLayout: Boolean
) {
    val context = LocalContext.current
    val heroHeight = if (isTabletLayout) 260.dp else 210.dp
    val avatarSize = if (isTabletLayout) 82.dp else 64.dp
    val backdrop = resolveBiliUploaderBackdropSources(
        bannerUrl = header?.bannerUrl,
        avatarUrl = header?.avatarUrl
    )
    val avatarBackdropRequest = remember(
        context,
        backdrop.avatarUrl,
        offlineMode
    ) {
        offlineCachedImageRequest(
            context = context,
            data = buildBiliThumbnailUrl(
                imageUrl = backdrop.avatarUrl,
                width = UPLOADER_AVATAR_BACKDROP_SIZE_PX,
                height = UPLOADER_AVATAR_BACKDROP_SIZE_PX
            ),
            sizePx = UPLOADER_AVATAR_BACKDROP_SIZE_PX,
            allowHardware = false,
            offlineMode = offlineMode,
            transformations = if (backdrop.avatarUrl.isNotEmpty()) {
                listOf(
                    BlurTransformation(
                        context = context,
                        radius = UPLOADER_AVATAR_BACKDROP_BLUR_RADIUS
                    )
                )
            } else {
                emptyList()
            }
        )
    }
    val bannerRequest = remember(context, backdrop.bannerUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = buildBiliThumbnailUrl(
                imageUrl = backdrop.bannerUrl,
                width = 768,
                height = 384
            ),
            sizePx = 768,
            offlineMode = offlineMode
        )
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (backdrop.avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = avatarBackdropRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (backdrop.bannerUrl.isNotEmpty()) {
                AsyncImage(
                    model = bannerRequest,
                    contentDescription = header?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = remember(context, header?.avatarUrl, offlineMode) {
                        offlineCachedImageRequest(
                            context = context,
                            data = buildBiliThumbnailUrl(
                                imageUrl = header?.avatarUrl.orEmpty(),
                                width = 160,
                                height = 160
                            ),
                            sizePx = 160,
                            allowHardware = false,
                            offlineMode = offlineMode
                        )
                    },
                    contentDescription = header?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = header?.name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.bili_uploader_mid, header?.mid ?: 0L),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(R.string.bili_uploader_video_count, videoCount))
                    }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            stringResource(
                                R.string.bili_uploader_collection_count,
                                collectionCount
                            )
                        )
                    }
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(R.string.bili_uploader_series_count, seriesCount))
                    }
                )
            }
            if (!header?.sign.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = header.sign.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BiliUploaderTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            tintColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    text = { Text(stringResource(R.string.bili_uploader_tab_videos)) },
                    icon = { Icon(Icons.Outlined.VideoLibrary, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    text = { Text(stringResource(R.string.bili_uploader_tab_collections)) },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    text = { Text(stringResource(R.string.bili_uploader_tab_series)) },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun BiliUploaderVideoRow(
    video: BiliClient.UploaderVideo,
    onClick: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val thumbnailUrl = remember(video.coverUrl) {
        buildBiliThumbnailUrl(
            imageUrl = video.coverUrl,
            width = 192,
            height = 108
        )
    }
    val coverRequest = remember(context, thumbnailUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = thumbnailUrl,
            sizePx = 192,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 100.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val playCount = video.play?.let { formatPlayCount(context, it) }
            val duration = formatDurationSec(video.durationSec)
            Text(
                text = listOfNotNull(playCount, duration.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BiliUploaderContentRow(
    content: BiliClient.UploaderContent,
    onClick: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val thumbnailUrl = remember(content.coverUrl) {
        buildBiliThumbnailUrl(
            imageUrl = content.coverUrl,
            width = 128,
            height = 128
        )
    }
    val coverRequest = remember(context, thumbnailUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = thumbnailUrl,
            sizePx = 128,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = coverRequest,
            contentDescription = content.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = content.description.ifBlank {
                stringResource(R.string.bili_uploader_content_count, content.total)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = if (content.kind == BiliClient.UploaderContentKind.COLLECTION) {
                Icons.Outlined.Folder
            } else {
                Icons.AutoMirrored.Outlined.PlaylistPlay
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BiliUploaderLoadMoreButton(
    loading: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.TextButton(onClick = onClick, enabled = !loading) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.bili_uploader_load_more))
        }
    }
}

private fun BiliClient.UploaderContent.toBiliPlaylist(uploaderName: String): BiliPlaylist {
    return BiliPlaylist(
        mediaId = id,
        fid = id,
        mid = mid,
        title = title,
        count = total,
        coverUrl = coverUrl,
        kind = when (kind) {
            BiliClient.UploaderContentKind.COLLECTION -> BiliPlaylistKind.COLLECTION
            BiliClient.UploaderContentKind.SERIES -> BiliPlaylistKind.SERIES
        },
        subtitle = uploaderName
    )
}
