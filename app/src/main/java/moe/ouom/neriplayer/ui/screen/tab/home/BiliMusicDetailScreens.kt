package moe.ouom.neriplayer.ui.screen.tab.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableBiliVideoItem
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableHotVideoItems
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableRankVideoItems
import moe.ouom.neriplayer.platform.bilibili.api.image.buildBiliThumbnailUrl
import moe.ouom.neriplayer.ui.component.common.NeriChoiceChip
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel
import moe.ouom.neriplayer.util.format.formatDate
import moe.ouom.neriplayer.util.format.formatDurationSec
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.util.platform.tryStartActivity

/**
 * 「Bilibili 音乐榜」查看全部页面。
 *
 * 与首页共用同一个 [HomeViewModel]（同一 ViewModelStoreOwner），因此榜单类型、期数与曲目状态保持一致，
 * 不在这里重复发起请求，除非用户主动刷新或切换榜单类型。
 *
 * 详情页打开时首页不再组合，[HomeScreen] 的 `setOfflineMode` 不会执行，所以这里必须把 [offlineMode]
 * 同步给共享 ViewModel，刷新、切榜等入口才会走仅缓存分支而不是继续发网络请求。
 *
 * 返回手势由 [moe.ouom.neriplayer.ui.screen.host.HomeHostScreen] 的预测性返回转场统一接管，
 * 此处不注册 [BackHandler]，只保留 toolbar 导航用的 [onBack]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliMusicRankDetailScreen(
    onBack: () -> Unit,
    onPlay: (List<BiliVideoItem>, Int) -> Unit,
    offlineMode: Boolean,
    activeRankType: BiliMusicRankType,
    onRankTypeSelected: (BiliMusicRankType) -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(appContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val state = ui.biliMusicRank
    // 播放中时详情页会被常驻迷你播放器遮住底部，列表末尾需要额外预留它的高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    // 详情页打开时首页不再组合，HomeScreen 的 wasOffline 恢复刷新不会抵达这个目的地；因此这里
    // 自己负责同步 offlineMode，并在从离线切回在线时重启一次因离线被跳过而遗留的空加载。
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(offlineMode) {
        val cameBackOnline = wasOffline && !offlineMode
        vm.setOfflineMode(offlineMode)
        if (cameBackOnline) {
            wasOffline = false
            val current = vm.uiState.value.biliMusicRank
            if (current.songs.isEmpty() && !current.loading) {
                vm.loadBiliMusicRank(force = true)
            }
        } else if (offlineMode) {
            wasOffline = true
        }
    }
    // 榜单类型由宿主持有（首页「查看全部」时记住的选中项，进程恢复后同样带回），而共享
    // HomeViewModel 可能已被重建回默认 HOT；这里把它对齐一次，避免展示与用户选择不一致的榜单。
    LaunchedEffect(activeRankType) {
        if (activeRankType != state.type) {
            vm.selectBiliMusicRankType(activeRankType)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(CoreCommonR.string.home_bili_music_rank)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !offlineMode,
                        onClick = { vm.loadBiliMusicRank(force = true) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(CoreCommonR.string.recommend_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BiliMusicRankTypeRow(
                selected = state.type,
                onTypeSelected = { type ->
                    // 详情页内切换榜单时同步回宿主，保证退出再进入或进程恢复后仍是这个榜单
                    onRankTypeSelected(type)
                    vm.selectBiliMusicRankType(type)
                }
            )
            state.period?.let { period ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(
                            CoreCommonR.string.home_bili_music_rank_period,
                            period.periodNumber
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (period.publishTimeSec > 0L) {
                        Text(
                            text = stringResource(
                                CoreCommonR.string.home_bili_music_rank_updated,
                                formatDate(period.publishTimeSec * 1000L)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            Text(
                text = stringResource(CoreCommonR.string.home_bili_music_source_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = {
                    if (!offlineMode) {
                        vm.loadBiliMusicRank(force = true)
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                val playableVideos = remember(state.songs) {
                    state.songs.toPlayableRankVideoItems()
                }
                when {
                    state.songs.isNotEmpty() -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "bili-music-rank-play-all") {
                            FilledTonalButton(
                                onClick = {
                                    if (playableVideos.isNotEmpty()) {
                                        onPlay(playableVideos, 0)
                                    }
                                },
                                enabled = playableVideos.isNotEmpty(),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(text = stringResource(CoreCommonR.string.home_bili_music_play_all))
                            }
                        }
                        itemsIndexed(
                            items = state.songs,
                            key = { index, song ->
                                song.musicId.ifBlank { "rank-row-$index-${song.creationBvid}" }
                            }
                        ) { _, song ->
                            BiliMusicRankDetailRow(
                                song = song,
                                offlineMode = offlineMode,
                                onClick = {
                                    val target = song.toPlayableBiliVideoItem()
                                    val index = if (target == null) {
                                        -1
                                    } else {
                                        playableVideos.indexOfFirst {
                                            it.id == target.id && it.bvid == target.bvid
                                        }
                                    }
                                    if (index >= 0) {
                                        onPlay(playableVideos, index)
                                    }
                                }
                            )
                        }
                    }

                    state.loading -> BiliMusicDetailHint(text = stringResource(CoreCommonR.string.home_loading))

                    !state.error.isNullOrBlank() -> BiliMusicDetailError(
                        detail = state.error.orEmpty(),
                        onRetry = { vm.loadBiliMusicRank(force = true) }
                    )

                    else -> BiliMusicDetailHint(text = stringResource(CoreCommonR.string.home_bili_music_empty))
                }
            }
        }
    }
}

/**
 * 「Bilibili 音乐热门」查看全部页面（音乐分区视频榜）。
 *
 * 与 [BiliMusicRankDetailScreen] 一样共用首页的 [HomeViewModel]，因此同样需要把 [offlineMode]
 * 同步给 ViewModel，并且不注册根级 [BackHandler]，让宿主的预测性返回转场继续生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliMusicHotDetailScreen(
    onBack: () -> Unit,
    onPlay: (List<BiliVideoItem>, Int) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(appContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val state = ui.biliMusicHot
    // 播放中时详情页会被常驻迷你播放器遮住底部，列表末尾需要额外预留它的高度
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    // 与 [BiliMusicRankDetailScreen] 同理：详情页打开时首页不再组合，HomeScreen 的 wasOffline
    // 恢复刷新不会抵达这个目的地；这里自己同步 offlineMode，并在从离线切回在线时重启一次因离线
    // 被跳过而遗留的空加载，否则会一直停在「暂无内容」直到手动刷新。
    var wasOffline by remember { mutableStateOf(false) }
    LaunchedEffect(offlineMode) {
        val cameBackOnline = wasOffline && !offlineMode
        vm.setOfflineMode(offlineMode)
        if (cameBackOnline) {
            wasOffline = false
            val current = vm.uiState.value.biliMusicHot
            if (current.items.isEmpty() && !current.loading) {
                vm.loadBiliMusicHot(force = true)
            }
        } else if (offlineMode) {
            wasOffline = true
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(CoreCommonR.string.home_bili_music_hot)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !offlineMode,
                        onClick = { vm.loadBiliMusicHot(force = true) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(CoreCommonR.string.recommend_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = {
                if (!offlineMode) {
                    vm.loadBiliMusicHot(force = true)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val playableVideos = remember(state.items) {
                state.items.toPlayableHotVideoItems()
            }
            when {
                state.items.isNotEmpty() -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "bili-music-hot-play-all") {
                        FilledTonalButton(
                            onClick = {
                                if (playableVideos.isNotEmpty()) {
                                    onPlay(playableVideos, 0)
                                }
                            },
                            enabled = playableVideos.isNotEmpty(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(text = stringResource(CoreCommonR.string.home_bili_music_play_all))
                        }
                    }
                    itemsIndexed(
                        items = state.items,
                        key = { index, video -> video.bvid.ifBlank { "hot-row-$index-${video.aid}" } }
                    ) { index, video ->
                        BiliMusicHotDetailRow(
                            rank = index + 1,
                            video = video,
                            offlineMode = offlineMode,
                            onClick = {
                                val index = playableVideos.indexOfFirst {
                                    it.id == video.aid && it.bvid == video.bvid
                                }
                                if (index >= 0) {
                                    onPlay(playableVideos, index)
                                }
                            }
                        )
                    }
                }

                state.loading -> BiliMusicDetailHint(text = stringResource(CoreCommonR.string.home_loading))

                !state.error.isNullOrBlank() -> BiliMusicDetailError(
                    detail = state.error.orEmpty(),
                    onRetry = { vm.loadBiliMusicHot(force = true) }
                )

                else -> BiliMusicDetailHint(text = stringResource(CoreCommonR.string.home_bili_music_empty))
            }
        }
    }
}

/** 详情页顶部的榜单类型切换（热门榜 / 原创榜），切换后由 ViewModel 重新加载对应期数 */
@Composable
private fun BiliMusicRankTypeRow(
    selected: BiliMusicRankType,
    onTypeSelected: (BiliMusicRankType) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        BiliMusicRankType.values().forEach { type ->
            val label = stringResource(
                when (type) {
                    BiliMusicRankType.HOT -> CoreCommonR.string.home_bili_music_rank_hot
                    BiliMusicRankType.ORIGINAL -> CoreCommonR.string.home_bili_music_rank_original
                }
            )
            if (NeriDesign.isClean) {
                NeriChoiceChip(
                    selected = selected == type,
                    onClick = { onTypeSelected(type) },
                    label = label
                )
            } else {
                FilterChip(
                    selected = selected == type,
                    onClick = { onTypeSelected(type) },
                    label = { Text(text = label) }
                )
            }
        }
    }
}

/**
 * 榜单详情行：名次、100x60 缩略图、标题、歌手、播放来源与时长，右侧可跳转原视频页。
 */
@Composable
private fun BiliMusicRankDetailRow(
    song: BiliMusicRankSong,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = song.isPlayable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = song.rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        val rankThumbnailUrl = remember(song.videoCover) {
            buildBiliThumbnailUrl(imageUrl = song.videoCover, width = 192, height = 108)
        }
        AsyncImage(
            model = offlineCachedImageRequest(
                context = context,
                data = rankThumbnailUrl,
                sizePx = 192,
                allowHardware = false,
                offlineMode = offlineMode
            ),
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(100.dp)
                .height(60.dp)
                .clip(neriRoundedShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = song.title.ifBlank { song.album },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.singer.ifBlank { song.uploader },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sourceLabel = song.playbackSourceLabel()
            Text(
                text = listOf(sourceLabel, formatDurationSec(song.displayDurationSec))
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = { openBiliVideoPage(context, song.originalBvid) },
            enabled = song.originalBvid.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(CoreCommonR.string.home_bili_music_open_original),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 音乐热门详情行：与榜单详情行同构，第三行改为分区名、播放量与时长。
 *
 * @param rank 展示用名次（调用方传 index + 1）
 */
@Composable
private fun BiliMusicHotDetailRow(
    rank: Int,
    video: BiliMusicHotVideo,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = video.isPlayable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        val hotThumbnailUrl = remember(video.coverUrl) {
            buildBiliThumbnailUrl(imageUrl = video.coverUrl, width = 192, height = 108)
        }
        AsyncImage(
            model = offlineCachedImageRequest(
                context = context,
                data = hotThumbnailUrl,
                sizePx = 192,
                allowHardware = false,
                offlineMode = offlineMode
            ),
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(100.dp)
                .height(60.dp)
                .clip(neriRoundedShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = video.uploader,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(
                    video.tname,
                    formatPlayCount(context, video.viewCount),
                    formatDurationSec(video.durationSec)
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = { openBiliVideoPage(context, video.bvid) },
            enabled = video.bvid.isNotBlank()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(CoreCommonR.string.home_bili_music_open_original),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 详情页的居中提示（加载中 / 无内容） */
@Composable
private fun BiliMusicDetailHint(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 详情页加载失败提示：错误详情 + 重试按钮 */
@Composable
private fun BiliMusicDetailError(
    detail: String,
    onRetry: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(CoreCommonR.string.home_bili_music_section_error_prefix, detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(CoreCommonR.string.recommend_refresh))
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** 在浏览器或 B 站客户端打开视频页，便于核对榜单曲目的实际来源 */
private fun openBiliVideoPage(context: android.content.Context, bvid: String) {
    if (bvid.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/$bvid"))
    context.tryStartActivity(intent)
}
