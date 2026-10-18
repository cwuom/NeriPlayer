package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicPlaybackSource
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableBiliVideoItem
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableHotVideoItems
import moe.ouom.neriplayer.data.model.bilibili.music.toPlayableRankVideoItems
import moe.ouom.neriplayer.platform.bilibili.api.image.buildBiliThumbnailUrl
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeBiliMusicRankState
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionState
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.util.format.formatDurationSec
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/**
 * 首页「Bilibili 音乐榜」区块。
 *
 * 该区块只负责展示与回调，请求、缓存与竞态控制都在 [moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel] 中，
 * 避免在 Compose 重组中重复请求。
 */
internal fun LazyGridScope.addBiliMusicRankSection(
    registerKey: (String) -> String,
    state: HomeBiliMusicRankState,
    onTypeSelected: (BiliMusicRankType) -> Unit,
    onMoreClick: () -> Unit,
    onPlay: (List<BiliVideoItem>, Int) -> Unit,
    onRetry: () -> Unit,
    offlineMode: Boolean
) {
    val hasContent = state.songs.isNotEmpty()
    if (!hasContent && !state.loading && state.error.isNullOrBlank()) return

    item(
        key = registerKey("home:biliMusic:rank:header"),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        BiliMusicSectionHeader(
            icon = Icons.Outlined.MusicNote,
            title = stringResource(CoreCommonR.string.home_bili_music_rank),
            onMoreClick = onMoreClick,
            typeSelector = {
                BiliMusicRankTypeSelector(
                    selected = state.type,
                    onTypeSelected = onTypeSelected
                )
            }
        )
    }

    when {
        hasContent -> item(
            key = registerKey("home:biliMusic:rank:content"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            BiliMusicRankStrip(
                state = state,
                onPlay = onPlay,
                offlineMode = offlineMode
            )
        }

        state.loading -> item(
            key = registerKey("home:biliMusic:rank:loading"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            BiliMusicSectionLoading(text = stringResource(CoreCommonR.string.home_loading))
        }

        else -> item(
            key = registerKey("home:biliMusic:rank:error"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            BiliMusicSectionError(
                detail = state.error.orEmpty(),
                onRetry = onRetry
            )
        }
    }
}

/**
 * 首页「Bilibili 音乐热门」区块（音乐分区视频榜）。
 *
 * 与榜单区块同构：同样只做展示与回调，数据由 [moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel] 提供，
 * 因此两个区块的卡片尺寸、缩略图裁剪方案与排版层级完全一致。
 */
internal fun LazyGridScope.addBiliMusicHotSection(
    registerKey: (String) -> String,
    state: HomeSectionState<BiliMusicHotVideo>,
    onMoreClick: () -> Unit,
    onPlay: (List<BiliVideoItem>, Int) -> Unit,
    onRetry: () -> Unit,
    offlineMode: Boolean
) {
    val hasContent = state.items.isNotEmpty()
    if (!hasContent && !state.loading && state.error.isNullOrBlank()) return

    item(
        key = registerKey("home:biliMusic:hot:header"),
        span = { GridItemSpan(maxLineSpan) }
    ) {
        BiliMusicSectionHeader(
            icon = Icons.Outlined.LibraryMusic,
            title = stringResource(CoreCommonR.string.home_bili_music_hot),
            onMoreClick = onMoreClick
        )
    }

    when {
        hasContent -> item(
            key = registerKey("home:biliMusic:hot:content"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            BiliMusicHotStrip(
                videos = state.items,
                onPlay = onPlay,
                offlineMode = offlineMode
            )
        }

        state.loading -> item(
            key = registerKey("home:biliMusic:hot:loading"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            BiliMusicSectionLoading(text = stringResource(CoreCommonR.string.home_loading))
        }

        else -> item(
            key = registerKey("home:biliMusic:hot:error"),
            span = { GridItemSpan(maxLineSpan) }
        ) {
            BiliMusicSectionError(
                detail = state.error.orEmpty(),
                onRetry = onRetry
            )
        }
    }
}

/**
 * 两个 B 站音乐区块共用的标题行：图标 + 标题 + 「查看全部」，榜单区块额外挂一个榜单类型选择器。
 */
@Composable
private fun BiliMusicSectionHeader(
    icon: ImageVector,
    title: String,
    onMoreClick: () -> Unit,
    typeSelector: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
            TextButton(onClick = onMoreClick) {
                Text(text = stringResource(CoreCommonR.string.home_bili_music_view_all))
            }
        }
        typeSelector?.invoke()
    }
}

/**
 * 榜单类型切换（热门榜 / 原创榜）：切换后由 ViewModel 清空旧榜数据并按新类型重新加载，避免串期。
 */
@Composable
private fun BiliMusicRankTypeSelector(
    selected: BiliMusicRankType,
    onTypeSelected: (BiliMusicRankType) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 30.dp, bottom = 2.dp)
    ) {
        BiliMusicRankType.values().forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onTypeSelected(type) },
                label = { Text(text = stringResource(type.labelRes)) }
            )
        }
    }
}

/**
 * 榜单横向卡片列表，点击卡片按其在可播放条目中的真实下标起播，保证顺序与榜单一致。
 */
@Composable
private fun BiliMusicRankStrip(
    state: HomeBiliMusicRankState,
    onPlay: (List<BiliVideoItem>, Int) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val playableVideos = remember(state.songs) { state.songs.toPlayableRankVideoItems() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = state.songs,
            key = { index, song -> song.musicId.ifBlank { "rank-$index-${song.creationBvid}" } }
        ) { _, song ->
            BiliMusicRankSongCard(
                song = song,
                offlineMode = offlineMode,
                onClick = {
                    val target = song.toPlayableBiliVideoItem()
                    val index = if (target == null) {
                        -1
                    } else {
                        playableVideos.indexOfFirst { it.id == target.id && it.bvid == target.bvid }
                    }
                    if (index >= 0) {
                        onPlay(playableVideos, index)
                    }
                }
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

/**
 * 榜单曲目卡片：16:9 封面（左上名次、右下时长）、两行标题、歌手与播放来源标注。
 *
 * 不可播放的曲目（既无官方 MV 也无有效投稿）保持可见但点击无效。
 */
@Composable
private fun BiliMusicRankSongCard(
    song: BiliMusicRankSong,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 沿用哔哩哔哩收藏夹的缩略图方案：统一走 buildBiliThumbnailUrl（@WxH_1c.webp 裁剪），
    // 保证同一比例与编码，滚动时不会出现尺寸/清晰度参差。
    val thumbnailUrl = remember(song.videoCover) {
        buildBiliThumbnailUrl(imageUrl = song.videoCover, width = 336, height = 189)
    }
    val coverRequest = remember(context, thumbnailUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = thumbnailUrl,
            sizePx = 336,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(neriRoundedShape(12.dp))
            .clickable(enabled = song.isPlayable, onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(neriRoundedShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = song.rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, neriRoundedShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                text = formatDurationSec(song.displayDurationSec),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), neriRoundedShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title.ifBlank { song.album },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = song.singer.ifBlank { song.playbackUploader },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.playbackSourceLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 如实标注该曲目实际会播放的音频来源：官方 MV、榜单授权投稿，或明确告知暂无可播放音频 */
@Composable
internal fun BiliMusicRankSong.playbackSourceLabel(): String =
    when (playbackSource) {
        BiliMusicPlaybackSource.OFFICIAL_MV ->
            stringResource(CoreCommonR.string.home_bili_music_source_mv)

        BiliMusicPlaybackSource.UPLOAD ->
            stringResource(
                CoreCommonR.string.home_bili_music_source_upload,
                playbackUploader.ifBlank { singer }
            )

        BiliMusicPlaybackSource.UNAVAILABLE ->
            stringResource(CoreCommonR.string.home_bili_music_unavailable)
    }

/**
 * 音乐热门横向卡片列表：点击时按 aid + bvid 在可播放条目中定位真实下标，避免索引错位。
 */
@Composable
private fun BiliMusicHotStrip(
    videos: List<BiliMusicHotVideo>,
    onPlay: (List<BiliVideoItem>, Int) -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val playableVideos = remember(videos) { videos.toPlayableHotVideoItems() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = videos,
            key = { index, video -> video.bvid.ifBlank { "hot-$index-${video.aid}" } }
        ) { index, video ->
            BiliMusicHotVideoCard(
                video = video,
                index = index,
                offlineMode = offlineMode,
                onClick = {
                    val target = playableVideos.indexOfFirst {
                        it.id == video.aid && it.bvid == video.bvid
                    }
                    if (target >= 0) {
                        onPlay(playableVideos, target)
                    }
                }
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

/**
 * 音乐热门视频卡片：与榜单卡片保持同一尺寸与排版，第三行展示分区名与播放量。
 *
 * @param index 在榜单中的位置（从 0 开始），仅用于展示名次角标
 */
@Composable
private fun BiliMusicHotVideoCard(
    video: BiliMusicHotVideo,
    index: Int,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 与榜单卡片统一：同一缩略图裁剪方案、同一卡片尺寸与排版层级
    val thumbnailUrl = remember(video.coverUrl) {
        buildBiliThumbnailUrl(imageUrl = video.coverUrl, width = 336, height = 189)
    }
    val coverRequest = remember(context, thumbnailUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = thumbnailUrl,
            sizePx = 336,
            allowHardware = false,
            offlineMode = offlineMode
        )
    }
    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(neriRoundedShape(12.dp))
            .clickable(enabled = video.isPlayable, onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(neriRoundedShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = coverRequest,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, neriRoundedShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                text = formatDurationSec(video.durationSec),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), neriRoundedShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
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
                formatPlayCount(context, video.viewCount)
            ).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 区块加载中占位，高度与卡片区接近，避免加载完成时页面跳动 */
@Composable
private fun BiliMusicSectionLoading(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 区块加载失败提示：错误详情 + 重试按钮，重试由调用方强制重新加载该模块 */
@Composable
private fun BiliMusicSectionError(
    detail: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(CoreCommonR.string.home_bili_music_section_error_prefix, detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(CoreCommonR.string.recommend_refresh))
        }
    }
}

/** 榜单类型对应的筛选芯片文案 */
private val BiliMusicRankType.labelRes: Int
    get() = when (this) {
        BiliMusicRankType.HOT -> CoreCommonR.string.home_bili_music_rank_hot
        BiliMusicRankType.ORIGINAL -> CoreCommonR.string.home_bili_music_rank_original
    }
