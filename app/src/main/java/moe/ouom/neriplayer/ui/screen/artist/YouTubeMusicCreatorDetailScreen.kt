package moe.ouom.neriplayer.ui.screen.artist

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader as CreatorHeader
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.theme.background.BlurTransformation
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.artist.toCreatorPlaylist
import moe.ouom.neriplayer.ui.viewmodel.artist.toCreatorSongItem
import moe.ouom.neriplayer.ui.viewmodel.artist.toCreatorSummary
import moe.ouom.neriplayer.ui.viewmodel.artist.youtubeMusicCreatorSectionKey
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

private const val CREATOR_HEADER_BACKDROP_BLUR_RADIUS = 100f
private const val CREATOR_HEADER_BACKDROP_SIZE_PX = 512

internal fun shouldShowYouTubeMusicCreatorSectionMore(
    section: YouTubeMusicCreatorSection
): Boolean {
    return section.moreEndpoint != null && section.items.any { item ->
        item.videoId.isNotBlank()
    }
}

internal fun preserveYouTubeMusicCreatorName(
    playlist: YouTubeMusicPlaylist,
    creatorName: String
): YouTubeMusicPlaylist {
    return playlist.copy(
        creatorName = playlist.creatorName.ifBlank { creatorName.trim() }
    )
}

internal fun youtubeMusicCreatorSectionScrollStateKey(
    creatorBrowseId: String,
    section: YouTubeMusicCreatorSection,
    sectionIndex: Int
): String {
    return "$creatorBrowseId|${youtubeMusicCreatorSectionKey(section)}|$sectionIndex"
}

internal fun youtubeMusicCreatorDetailViewModelKey(creatorBrowseId: String): String {
    return "youtube_music_creator_detail_view_model_$creatorBrowseId"
}

internal fun createYouTubeMusicCreatorFavorite(
    creator: YouTubeMusicCreatorSummary,
    header: CreatorHeader?
): FavoritePlaylist {
    val currentHeader = header.matchingCreatorOrBlank(creator)
    return FavoritePlaylist(
        id = stableYouTubeMusicId(creator.browseId),
        name = currentHeader.title.ifBlank { creator.title },
        coverUrl = currentHeader.coverUrl.ifBlank { creator.coverUrl }.takeIf { it.isNotBlank() },
        trackCount = 0,
        source = FAVORITE_SOURCE_YOUTUBE_ARTIST,
        browseId = creator.browseId,
        subtitle = currentHeader.subtitle.ifBlank { creator.subtitle }.takeIf { it.isNotBlank() },
        songs = emptyList()
    )
}

private fun CreatorHeader?.matchingCreatorOrBlank(creator: YouTubeMusicCreatorSummary): CreatorHeader =
    this?.takeIf { it.browseId == creator.browseId }
        ?: CreatorHeader(browseId = creator.browseId, title = "", subtitle = "", coverUrl = "")

internal fun resolveYouTubeMusicCreatorDetail(
    uiState: YouTubeMusicCreatorDetailUiState,
    creator: YouTubeMusicCreatorSummary?
): YouTubeMusicCreatorDetail? {
    return uiState.detail ?: creator?.let {
        YouTubeMusicCreatorDetail(
            header = CreatorHeader(
                browseId = it.browseId,
                title = it.title,
                subtitle = it.subtitle,
                coverUrl = it.coverUrl
            ),
            sections = emptyList()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeMusicCreatorDetailScreen(
    creator: YouTubeMusicCreatorSummary,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit = {},
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit = {},
    onSectionMoreClick: (YouTubeMusicCreatorSection) -> Unit = {},
    offlineMode: Boolean = false,
    detailViewModelFactory: androidx.lifecycle.ViewModelProvider.Factory? = null
) {
    val context = LocalContext.current
    val resolvedViewModelFactory = detailViewModelFactory ?: viewModelFactory {
        initializer {
            YouTubeMusicCreatorDetailViewModel(context.applicationContext as Application)
        }
    }
    val viewModel: YouTubeMusicCreatorDetailViewModel = viewModel(
        key = youtubeMusicCreatorDetailViewModelKey(creator.browseId),
        factory = resolvedViewModelFactory
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val isTabletLayout = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val listState = rememberSaveable(creator.browseId, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val sectionStateHolder = rememberSaveableStateHolder()

    LaunchedEffect(creator.browseId) {
        viewModel.start(creator)
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
                        text = uiState.detail?.header?.title?.ifBlank { creator.title }
                            ?: creator.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.cd_back)
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
            YouTubeMusicCreatorDetailContent(
                uiState = uiState,
                listState = listState,
                creatorBrowseId = creator.browseId,
                sectionStateHolder = sectionStateHolder,
                miniPlayerHeight = miniPlayerHeight,
                offlineMode = offlineMode,
                onRetry = viewModel::retry,
                onSongClick = onSongClick,
                onSectionSongClick = viewModel::playSectionSong,
                onPlaylistClick = { playlist ->
                    onPlaylistClick(preserveYouTubeMusicCreatorName(playlist, creator.title))
                },
                onCreatorClick = onCreatorClick,
                onSectionMoreClick = onSectionMoreClick,
                isTabletLayout = isTabletLayout,
                fallbackCreator = creator,
                followFavorite = createYouTubeMusicCreatorFavorite(creator, uiState.detail?.header)
            )
        }
    }
}

@Composable
internal fun YouTubeMusicCreatorDetailContent(
    uiState: YouTubeMusicCreatorDetailUiState,
    listState: LazyListState,
    creatorBrowseId: String,
    sectionStateHolder: SaveableStateHolder,
    miniPlayerHeight: androidx.compose.ui.unit.Dp,
    offlineMode: Boolean,
    onRetry: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onSectionSongClick: (YouTubeMusicCreatorSection, YouTubeMusicCreatorItem) -> Unit,
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit,
    onSectionMoreClick: (YouTubeMusicCreatorSection) -> Unit,
    isTabletLayout: Boolean,
    fallbackCreator: YouTubeMusicCreatorSummary? = null,
    followFavorite: FavoritePlaylist? = null
) {
    val detail = resolveYouTubeMusicCreatorDetail(uiState, fallbackCreator)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.TopCenter
    ) {
        when {
            detail == null && uiState.loading -> {
                Box(
                    modifier = Modifier
                        .widthIn(max = 1080.dp)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            detail == null -> {
                Box(
                    modifier = Modifier
                        .widthIn(max = 1080.dp)
                        .fillMaxSize()
                        .padding(horizontal = if (isTabletLayout) 36.dp else 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        HapticTextButton(onClick = onRetry) {
                            Text(stringResource(CoreCommonR.string.action_retry))
                        }
                    }
                }
            }
            else -> {
                CreatorDetailAdaptiveLayout(
                    tabletDevice = isTabletLayout,
                    miniPlayerHeight = miniPlayerHeight,
                    profile = {
                        YouTubeMusicCreatorHeader(
                            detail = detail,
                            offlineMode = offlineMode,
                            isTabletLayout = true,
                            followFavorite = followFavorite
                        )
                    }
                ) { splitLayout ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = 1080.dp)
                            .fillMaxSize()
                            .testTag("youtubeCreatorWorksList"),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = if (splitLayout) 8.dp else 4.dp,
                            bottom = if (splitLayout) 20.dp else 40.dp + miniPlayerHeight
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (uiState.loading) {
                            item(key = "creator-loading") {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        item(key = "creator-header") {
                            if (!splitLayout) {
                                YouTubeMusicCreatorHeader(
                                    detail = detail,
                                    offlineMode = offlineMode,
                                    isTabletLayout = false,
                                    followFavorite = followFavorite
                                )
                            }
                        }
                        if (!uiState.error.isNullOrBlank()) {
                            item(key = "creator-error") {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = uiState.error,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    HapticTextButton(onClick = onRetry) {
                                        Text(stringResource(CoreCommonR.string.action_retry))
                                    }
                                }
                            }
                        }
                        if (detail.sections.isEmpty() && uiState.detail != null) {
                            item(key = "creator-empty") {
                                Text(
                                    text = stringResource(CoreCommonR.string.youtube_creator_sections_empty),
                                    modifier = Modifier.padding(vertical = 28.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = detail.sections,
                                key = { index, section -> "creator-section-$index-${section.title}" }
                            ) { sectionIndex, section ->
                                YouTubeMusicCreatorSection(
                                    section = section,
                                    creatorBrowseId = creatorBrowseId,
                                    sectionIndex = sectionIndex,
                                    stateHolder = sectionStateHolder,
                                    offlineMode = offlineMode,
                                    isPlaybackQueueLoading =
                                        uiState.playbackQueueLoadingSectionKey ==
                                            youtubeMusicCreatorSectionKey(section),
                                    playbackQueueError = uiState.playbackQueueError.takeIf {
                                        uiState.playbackQueueErrorSectionKey ==
                                            youtubeMusicCreatorSectionKey(section)
                                    },
                                    onSongClick = onSongClick,
                                    onSectionSongClick = onSectionSongClick,
                                    onPlaylistClick = onPlaylistClick,
                                    onCreatorClick = onCreatorClick,
                                    onSectionMoreClick = onSectionMoreClick,
                                    tabletLayout = splitLayout
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeMusicCreatorHeader(
    detail: YouTubeMusicCreatorDetail,
    offlineMode: Boolean,
    isTabletLayout: Boolean,
    followFavorite: FavoritePlaylist?
) {
    if (isTabletLayout) {
        YouTubeMusicCreatorTabletProfile(detail.header, offlineMode, followFavorite)
        return
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        YouTubeMusicCreatorPhoneHero(header = detail.header, offlineMode = offlineMode)
        YouTubeMusicCreatorPhoneDetails(header = detail.header, followFavorite = followFavorite)
    }
}

internal fun youtubeMusicCreatorPhoneMetadata(header: CreatorHeader): List<String> = listOfNotNull(
    header.monthlyListenerCountText.takeIf { it.isNotBlank() },
    header.subscriberCountText.takeIf { it.isNotBlank() }
).distinct()

private fun creatorHeaderBackdropRequest(
    context: Context,
    coverUrl: String?,
    offlineMode: Boolean
): ImageRequest = offlineCachedImageRequest(
    context = context,
    data = coverUrl,
    sizePx = CREATOR_HEADER_BACKDROP_SIZE_PX,
    allowHardware = false,
    offlineMode = offlineMode,
    transformations = if (coverUrl != null) {
        listOf(
            BlurTransformation(
                context = context,
                radius = CREATOR_HEADER_BACKDROP_BLUR_RADIUS
            )
        )
    } else {
        emptyList()
    }
)

@Composable
private fun YouTubeMusicCreatorPhoneHero(
    header: CreatorHeader,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val coverUrl = header.coverUrl.takeIf { it.isNotBlank() }
    val backdropRequest = remember(context, coverUrl, offlineMode) {
        creatorHeaderBackdropRequest(context, coverUrl, offlineMode)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YouTubeMusicCreatorHeroAvatar(coverUrl = coverUrl, title = header.title, offlineMode = offlineMode)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (header.subtitle.isNotBlank()) {
                    Text(
                        text = header.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeMusicCreatorHeroAvatar(
    coverUrl: String?,
    title: String,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val avatarSize = 64.dp
    val avatarRequest = remember(context, coverUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = coverUrl,
            sizePx = 192,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Box(
        modifier = Modifier
            .size(avatarSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = avatarRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(avatarSize * 0.68f),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun YouTubeMusicCreatorPhoneDetails(
    header: CreatorHeader,
    followFavorite: FavoritePlaylist?
) {
    Column(modifier = Modifier.padding(20.dp)) {
        val metadata = youtubeMusicCreatorPhoneMetadata(header)
        val hasMetadata = metadata.isNotEmpty()
        val hasDescription = header.description.isNotBlank()
        if (hasMetadata) {
            CreatorMetadataChips(metadata = metadata, verticalSpacing = 8.dp)
        }
        if (hasDescription) {
            if (hasMetadata) {
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = header.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (followFavorite != null) {
            if (hasMetadata || hasDescription) {
                Spacer(Modifier.height(14.dp))
            }
            CreatorFollowButton(favorite = followFavorite)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreatorMetadataChips(metadata: List<String>, verticalSpacing: Dp) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        metadata.forEach { value ->
            AssistChip(
                onClick = {},
                label = { Text(value) }
            )
        }
    }
}

@Composable
private fun YouTubeMusicCreatorSection(
    section: YouTubeMusicCreatorSection,
    creatorBrowseId: String,
    sectionIndex: Int,
    stateHolder: SaveableStateHolder,
    offlineMode: Boolean,
    isPlaybackQueueLoading: Boolean,
    playbackQueueError: String?,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onSectionSongClick: (YouTubeMusicCreatorSection, YouTubeMusicCreatorItem) -> Unit,
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit,
    onSectionMoreClick: (YouTubeMusicCreatorSection) -> Unit,
    tabletLayout: Boolean
) {
    val playableItems = section.items.mapNotNull { item ->
        item.toCreatorSongItem()?.let { item to it }
    }
    val playableSongs = playableItems.map { (_, song) -> song }
    val onlyPlayableItems = playableItems.size == section.items.size
    val canShowAll = shouldShowYouTubeMusicCreatorSectionMore(section)
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = section.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (canShowAll) {
                HapticIconButton(onClick = { onSectionMoreClick(section) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = stringResource(
                            CoreCommonR.string.youtube_creator_view_all,
                            section.title
                        )
                    )
                }
            }
            if (isPlaybackQueueLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        if (!playbackQueueError.isNullOrBlank()) {
            Text(
                text = playbackQueueError,
                modifier = Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (onlyPlayableItems) {
            playableItems.forEachIndexed { index, (item, song) ->
                CreatorPlayableRow(
                    song = song,
                    index = index + 1,
                    offlineMode = offlineMode,
                    enabled = !isPlaybackQueueLoading,
                    onClick = {
                        if (section.moreEndpoint == null) {
                            onSongClick(playableSongs, index)
                        } else {
                            onSectionSongClick(section, item)
                        }
                    }
                )
            }
        } else {
            stateHolder.SaveableStateProvider(
                key = youtubeMusicCreatorSectionScrollStateKey(
                    creatorBrowseId = creatorBrowseId,
                    section = section,
                    sectionIndex = sectionIndex
                )
            ) {
                val listState = rememberSaveable(saver = LazyListState.Saver) {
                    LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
                }
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = section.items,
                        key = { index, item -> "${item.type}-${item.browseId}-${item.videoId}-$index" }
                    ) { _, item ->
                        CreatorSectionCard(
                            item = item,
                            offlineMode = offlineMode,
                            onSongClick = onSongClick,
                            onPlaylistClick = onPlaylistClick,
                            onCreatorClick = onCreatorClick,
                            tabletLayout = tabletLayout
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreatorPlayableRow(
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
        CreatorSongCover(coverUrl = coverUrl, contentDescription = song.name, offlineMode = offlineMode)
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
internal fun CreatorSongCover(
    coverUrl: String?,
    contentDescription: String,
    offlineMode: Boolean
) {
    val context = LocalContext.current
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
                contentDescription = contentDescription,
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
}

@Composable
private fun CreatorSectionCard(
    item: YouTubeMusicCreatorItem,
    offlineMode: Boolean,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit,
    tabletLayout: Boolean
) {
    val context = LocalContext.current
    val onClick = creatorItemClickAction(context, item, onSongClick, onPlaylistClick, onCreatorClick)
    Column(
        modifier = Modifier
            .width(if (tabletLayout) 176.dp else 148.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = fastScrollableImageRequest(
                        context = context,
                        data = item.coverUrl,
                        sizePx = 320,
                        offlineMode = offlineMode
                    ),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = creatorItemIcon(item.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun creatorItemClickAction(
    context: Context,
    item: YouTubeMusicCreatorItem,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit
): () -> Unit = {
    context.performHapticFeedback()
    openYouTubeMusicCreatorItem(item, onSongClick, onPlaylistClick, onCreatorClick)
}

internal fun openYouTubeMusicCreatorItem(
    item: YouTubeMusicCreatorItem,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onPlaylistClick: (YouTubeMusicPlaylist) -> Unit,
    onCreatorClick: (YouTubeMusicCreatorSummary) -> Unit
) {
    val song = item.toCreatorSongItem()
    if (song != null) {
        onSongClick(listOf(song), 0)
        return
    }
    when (item.type) {
        YouTubeMusicCreatorItemType.Creator -> {
            item.toCreatorSummary()?.let(onCreatorClick)
        }
        YouTubeMusicCreatorItemType.Album,
        YouTubeMusicCreatorItemType.Playlist -> {
            item.toCreatorPlaylist()?.let(onPlaylistClick)
        }
        YouTubeMusicCreatorItemType.Song,
        YouTubeMusicCreatorItemType.Video -> Unit
    }
}

private fun creatorItemIcon(type: YouTubeMusicCreatorItemType) = when (type) {
    YouTubeMusicCreatorItemType.Song,
    YouTubeMusicCreatorItemType.Video -> Icons.Filled.PlayCircle
    YouTubeMusicCreatorItemType.Album -> Icons.Filled.Album
    YouTubeMusicCreatorItemType.Playlist -> Icons.AutoMirrored.Filled.QueueMusic
    YouTubeMusicCreatorItemType.Creator -> Icons.Filled.AccountCircle
}
