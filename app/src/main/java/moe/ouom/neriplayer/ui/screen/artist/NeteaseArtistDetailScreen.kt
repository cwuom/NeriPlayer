package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.remember
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.i18n.asString
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.navigation.scrollToTopForReselect
import moe.ouom.neriplayer.ui.navigation.scrollToTopOnTitleBarTap
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistHeader
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.haptic.HapticButton
import androidx.compose.material3.LocalContentColor
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.R as AppR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseArtistDetailScreen(
    artist: NeteaseArtistSummary,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    onAlbumClick: (AlbumSummary) -> Unit = {},
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val viewModel: NeteaseArtistDetailViewModel = viewModel(
        key = "netease_artist_detail_view_model_${artist.id}",
        factory = viewModelFactory {
            initializer {
                NeteaseArtistDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable(artist.id) { mutableIntStateOf(0) }
    val songsListState = rememberSaveable(artist.id, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val albumsListState = rememberSaveable(artist.id, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
    }
    val listState = if (selectedTab == 0) songsListState else albumsListState

    LaunchedEffect(artist.id) {
        viewModel.start(artist)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = ui.header?.name ?: artist.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.cd_back)
                        )
                    }
                },
                modifier = Modifier.scrollToTopOnTitleBarTap { listState.scrollToTopForReselect() },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            NeteaseArtistDetailContent(
                ui = ui,
                listState = listState,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onRetry = viewModel::retry,
                onToggleFollow = viewModel::toggleFollow,
                onLoadMoreSongs = viewModel::loadMoreSongs,
                onLoadMoreAlbums = viewModel::loadMoreAlbums,
                onSongClick = onSongClick,
                onAlbumClick = onAlbumClick,
                offlineMode = offlineMode
            )
        }
    }
}

@Composable
internal fun NeteaseArtistDetailContent(
    ui: NeteaseArtistDetailUiState,
    listState: LazyListState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onRetry: () -> Unit,
    onToggleFollow: () -> Unit,
    onLoadMoreSongs: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    offlineMode: Boolean
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val isTabletDevice = LocalConfiguration.current.smallestScreenWidthDp >= 600
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        if (isTabletDevice && maxWidth >= 600.dp) {
            val sidebarWidth = (maxWidth * 0.3f).coerceIn(180.dp, 320.dp)
            val gutter = if (maxWidth >= 840.dp) 24.dp else 20.dp
            Row(
                modifier = Modifier
                    .widthIn(max = 1280.dp)
                    .fillMaxSize()
                    .padding(
                        start = gutter,
                        end = gutter,
                        top = 8.dp,
                        bottom = 8.dp + miniPlayerHeight
                    ),
                horizontalArrangement = Arrangement.spacedBy(gutter)
            ) {
                Column(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .verticalScroll(rememberScrollState())
                ) {
                    NeteaseArtistProfileCard(
                        header = ui.header,
                        followUpdating = ui.followUpdating,
                        offlineMode = offlineMode,
                        onToggleFollow = onToggleFollow
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    shape = neriRoundedShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (ui.songs.isNotEmpty() || ui.albums.isNotEmpty() ||
                            (!ui.loading && ui.error == null)
                        ) {
                            ArtistWorkTabs(
                                selectedTab = selectedTab,
                                onTabSelected = onTabSelected,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            artistWorks(
                                ui = ui,
                                selectedTab = selectedTab,
                                onRetry = onRetry,
                                onLoadMoreSongs = onLoadMoreSongs,
                                onLoadMoreAlbums = onLoadMoreAlbums,
                                onSongClick = onSongClick,
                                onAlbumClick = onAlbumClick,
                                offlineMode = offlineMode
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = 1080.dp)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 40.dp + miniPlayerHeight
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    ArtistHeaderCard(
                        header = ui.header,
                        followUpdating = ui.followUpdating,
                        offlineMode = offlineMode,
                        onToggleFollow = onToggleFollow,
                        onPlayTopSongs = if (ui.songs.isNotEmpty()) {
                            { onSongClick(ui.songs, 0) }
                        } else {
                            null
                        }
                    )
                }
                artistWorks(
                    ui = ui,
                    selectedTab = selectedTab,
                    onRetry = onRetry,
                    onLoadMoreSongs = onLoadMoreSongs,
                    onLoadMoreAlbums = onLoadMoreAlbums,
                    onSongClick = onSongClick,
                    onAlbumClick = onAlbumClick,
                    offlineMode = offlineMode,
                    tabs = {
                        ArtistWorkTabs(selectedTab = selectedTab, onTabSelected = onTabSelected)
                    }
                )
            }
        }
    }
}

private fun LazyListScope.artistWorks(
    ui: NeteaseArtistDetailUiState,
    selectedTab: Int,
    onRetry: () -> Unit,
    onLoadMoreSongs: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    offlineMode: Boolean,
    tabs: (@Composable () -> Unit)? = null
) {
    if (ui.error != null && !ui.loading && (ui.songs.isNotEmpty() || ui.albums.isNotEmpty())) {
        item {
            Text(
                text = ui.error.asString(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }

    if (ui.loading && ui.songs.isEmpty() && ui.albums.isEmpty()) {
        item {
            LoadingBlock()
        }
        return
    }

    if (ui.error != null && ui.songs.isEmpty() && ui.albums.isEmpty()) {
        item {
            ErrorBlock(error = ui.error.asString(), onRetry = onRetry)
        }
        return
    }

    if (tabs != null) item { tabs() }

    if (selectedTab == 0) {
        if (ui.songs.isEmpty()) {
            item { EmptyBlock(text = stringResource(CoreCommonR.string.artist_songs_empty)) }
        } else {
            itemsIndexed(ui.songs, key = { _, item -> item.id }) { index, song ->
                ArtistSongRow(
                    index = index + 1,
                    song = song,
                    onClick = { onSongClick(ui.songs, index) },
                    offlineMode = offlineMode
                )
            }
        }
        if (ui.songsHasMore) {
            item {
                LoadMoreButton(
                    loading = ui.songsLoadingMore,
                    onClick = onLoadMoreSongs
                )
            }
        }
    } else {
        if (ui.albums.isEmpty()) {
            item { EmptyBlock(text = stringResource(CoreCommonR.string.artist_albums_empty)) }
        } else {
            itemsIndexed(ui.albums, key = { _, item -> item.id }) { _, album ->
                ArtistAlbumRow(
                    album = album,
                    onClick = { onAlbumClick(album) },
                    offlineMode = offlineMode
                )
            }
        }
        if (ui.albumsHasMore) {
            item {
                LoadMoreButton(
                    loading = ui.albumsLoadingMore,
                    onClick = onLoadMoreAlbums
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistWorkTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(
            labels = listOf(
                stringResource(CoreCommonR.string.artist_tab_songs),
                stringResource(CoreCommonR.string.artist_tab_albums)
            ),
            selectedIndex = selectedTab,
            onSelected = onTabSelected,
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = neriRoundedShape(24.dp),
        color = Color.Transparent
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = Modifier.fillMaxWidth(),
            shape = neriRoundedShape(24.dp),
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
                    text = { Text(stringResource(CoreCommonR.string.artist_tab_songs)) },
                    icon = { Icon(Icons.Outlined.MusicNote, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    text = { Text(stringResource(CoreCommonR.string.artist_tab_albums)) },
                    icon = { Icon(Icons.Outlined.LibraryMusic, contentDescription = null) }
                )
            }
        }
    }
}

internal data class NeteaseArtistHero(
    val coverUrl: String,
    val avatarUrl: String,
    val name: String?,
    val alias: String?
)

internal fun NeteaseArtistHeader?.toNeteaseArtistHero(): NeteaseArtistHero {
    if (this == null) return NeteaseArtistHero(coverUrl = "", avatarUrl = "", name = null, alias = null)
    val cover = coverUrl.ifBlank { avatarUrl }
    return NeteaseArtistHero(
        coverUrl = cover,
        avatarUrl = avatarUrl,
        name = name,
        alias = alias.takeIf { it.isNotBlank() }
    )
}

/**
 * 手机端歌手头部: 大幅封面铺满卡片, 名字和作品数压在底部暗角上, 右侧一枚播放热门歌曲的圆钮;
 * 关注和简介放在封面下方, 不再套第二层卡片
 */
@Composable
private fun ArtistHeaderCard(
    header: NeteaseArtistHeader?,
    followUpdating: Boolean,
    offlineMode: Boolean,
    onToggleFollow: () -> Unit,
    onPlayTopSongs: (() -> Unit)?
) {
    val context = LocalContext.current
    val hero = header.toNeteaseArtistHero()
    val coverRequest = remember(context, hero.coverUrl, offlineMode) {
        offlineCachedImageRequest(context = context, data = hero.coverUrl, offlineMode = offlineMode)
    }
    val songCount = header?.musicSize ?: 0
    val albumCount = header?.albumSize ?: 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        CreatorHeroBanner(
            title = hero.name.orEmpty(),
            subtitle = hero.alias,
            summaryItems = listOf(
                pluralStringResource(AppR.plurals.artist_song_count, songCount, songCount),
                pluralStringResource(AppR.plurals.artist_album_count, albumCount, albumCount)
            ),
            trailing = onPlayTopSongs?.let { onPlay ->
                {
                    HapticFilledIconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(CoreCommonR.string.player_play_all),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        ) {
            AsyncImage(
                model = coverRequest,
                contentDescription = hero.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        Spacer(Modifier.height(14.dp))
        ArtistFollowButton(header, followUpdating, onToggleFollow)
        ArtistBriefDescription(header, maxLines = 3)
    }
}

/** 平板侧栏的歌手资料; 手机端由 [ArtistHeaderCard] 自己排版 */
@Composable
internal fun ArtistHeaderDetails(
    header: NeteaseArtistHeader?,
    followUpdating: Boolean,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ArtistTabletHeaderDetails(header, followUpdating, onToggleFollow)
    }
}

@Composable
private fun ArtistTabletHeaderDetails(
    header: NeteaseArtistHeader?,
    followUpdating: Boolean,
    onToggleFollow: () -> Unit
) {
    val hero = header.toNeteaseArtistHero()
    Text(
        text = hero.name.orEmpty(),
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    hero.alias?.let { alias ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = alias,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ArtistReleaseCounts(header)
    }
    Spacer(Modifier.height(14.dp))
    ArtistFollowButton(header, followUpdating, onToggleFollow, Modifier.fillMaxWidth())
    ArtistBriefDescription(header, maxLines = 6)
}

@Composable
private fun ArtistBriefDescription(header: NeteaseArtistHeader?, maxLines: Int) {
    val briefDesc = header?.briefDesc
    if (briefDesc.isNullOrBlank()) return
    Spacer(Modifier.height(10.dp))
    CreatorExpandableText(text = briefDesc, collapsedMaxLines = maxLines)
}

@Composable
private fun ArtistFollowButton(
    header: NeteaseArtistHeader?,
    followUpdating: Boolean,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val followed = header?.followed == true
    val content: @Composable RowScope.() -> Unit = {
        ArtistFollowButtonContent(followed, followUpdating)
    }
    val enabled = header != null && !followUpdating
    HapticButton(
        enabled = enabled,
        onClick = onToggleFollow,
        colors = creatorFollowButtonColors(followed),
        border = creatorFollowButtonBorder(followed, enabled),
        modifier = modifier,
        content = content
    )
}

@Composable
private fun RowScope.ArtistFollowButtonContent(followed: Boolean, followUpdating: Boolean) {
    if (followUpdating) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = LocalContentColor.current,
            strokeWidth = 2.dp
        )
    } else {
        Icon(
            imageVector = if (followed) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null
        )
    }
    Spacer(Modifier.width(8.dp))
    Text(stringResource(if (followed) CoreCommonR.string.artist_followed else CoreCommonR.string.artist_follow))
}

@Composable
private fun ArtistReleaseCounts(header: NeteaseArtistHeader?) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                pluralStringResource(
                    AppR.plurals.artist_song_count,
                    header?.musicSize ?: 0,
                    header?.musicSize ?: 0
                )
            )
        }
    )
    AssistChip(
        onClick = {},
        label = {
            Text(
                pluralStringResource(
                    AppR.plurals.artist_album_count,
                    header?.albumSize ?: 0,
                    header?.albumSize ?: 0
                )
            )
        }
    )
}
